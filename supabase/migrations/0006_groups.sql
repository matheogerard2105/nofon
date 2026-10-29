-- Nofon — Phase 8 : groupes.
--
-- Création, code d'invitation, demandes/acceptation, départ volontaire/exclusion
-- (délai de 24 h), fin naturelle d'objectif (immédiate), évaluation collective
-- (`group_success`), série de groupe. TODO-21 et TODO-22 (PRODUCT_TODOS-2.md),
-- explicitement non tranchés par la spec d'origine, ont été tranchés avec
-- l'utilisateur avant cette migration — voir les décisions citées dans les
-- commentaires ci-dessous et dans PRODUCT_TODOS-2.md.
--
-- Changement de comportement pour les membres de groupe UNIQUEMENT : `evaluate_
-- measurement_batch` ne finalise plus `daily_success` immédiatement mais passe par
-- l'état `PROVISIONAL` (prévu dans le schéma depuis 0002_goals.sql, jamais utilisé
-- jusqu'ici) en attendant la résolution collective. Comportement des utilisateurs
-- sans groupe strictement inchangé.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL
-- Editor), après 0001 à 0005.

-- ---------------------------------------------------------------- app_config (ajout)
-- TODO-22 : délai de finalisation forcée, décision tranchée avec l'utilisateur.
insert into public.app_config (key, value) values
    ('group_finalisation_deadline_hours', '48');

-- ---------------------------------------------------------------- groups
-- timezone/end_date figés à la création (SPEC §12.3/§12.4, déjà tranchés) — calculés
-- par create_group() ci-dessous depuis creator_goal_id, jamais fournis directement par
-- le client. creator_goal_id conservé : c'est "son propre objectif" au sens §12.1,
-- même rôle que goal_id sur group_members pour les autres membres.
create table public.groups (
    id uuid primary key default gen_random_uuid(),
    creator_id uuid not null references public.users (id) on delete cascade,
    creator_goal_id uuid not null references public.goals (id),
    code text not null unique,
    timezone text not null,
    end_date date not null,
    created_at timestamptz not null default now()
);

comment on table public.groups is
    'timezone/end_date immuables après création (PRODUCT_SPEC-2.md §12.3/§12.4). '
    'Calculés par create_group(), jamais acceptés tels quels du client.';

-- ---------------------------------------------------------------- group_members
-- status : mêmes valeurs que documentées dans ARCHITECTURE-2.md §6. goal_id = "son
-- propre objectif" du membre (§12.1) — figé à la demande d'adhésion, jamais recalculé
-- (même idiome que groups.creator_goal_id/goals.timezone : une référence figée, pas
-- une dérivation dynamique qui pourrait changer si le membre change d'objectif actif).
create table public.group_members (
    group_id uuid not null references public.groups (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    goal_id uuid not null references public.goals (id),
    status text not null default 'PENDING'
        check (status in ('PENDING', 'ACTIVE', 'LEAVING', 'LEFT', 'REMOVED')),
    -- 'SELF' (départ volontaire) / 'CREATOR' (exclusion) / 'NATURAL_END' (TODO-05,
    -- immédiat, jamais de délai) — distinction d'affichage uniquement, TODO-21 traite
    -- les trois cas de façon identique pour group_success (voir active_group_members_for).
    leave_initiated_by text check (leave_initiated_by in ('SELF', 'CREATOR', 'NATURAL_END')),
    leave_requested_at timestamptz,
    -- Instant réel de sortie : leave_requested_at + 24h (départ/exclusion) ou l'instant
    -- de fin d'objectif (fin naturelle, immédiat). TODO-21 : le membre compte pour la
    -- journée collective ENTIÈRE qui contient cet instant, disparaît à partir de la
    -- suivante — voir active_group_members_for().
    left_effective_at timestamptz,
    created_at timestamptz not null default now(),
    primary key (group_id, user_id)
);

create index group_members_user_id_idx on public.group_members (user_id);

comment on table public.group_members is
    'status suit ARCHITECTURE-2.md §6 : PENDING (demande), ACTIVE, LEAVING (délai de '
    '24h en cours, compte toujours dans group_success), LEFT/REMOVED (terminal). '
    'TODO-21 (PRODUCT_TODOS-2.md) : périmètre exact tranché dans active_group_members_for().';

-- ---------------------------------------------------------------- group_daily_results
-- Écrite uniquement par resolve_group_day() — même principe que daily_results
-- (ARCHITECTURE-2.md : aucune écriture client sur l'évaluation). Une ligne n'existe
-- que pour un jour RÉSOLU (SUCCESS/FAILED) ; son absence == "en attente du groupe"
-- (SPEC §12.8 point 5) côté client, pas un état PENDING/PROVISIONAL stocké séparément.
create table public.group_daily_results (
    group_id uuid not null references public.groups (id) on delete cascade,
    local_date date not null,
    status text not null check (status in ('SUCCESS', 'FAILED')),
    resolved_at timestamptz not null default now(),
    primary key (group_id, local_date)
);

-- ---------------------------------------------------------------- group_finalization_requests
-- Filet de sécurité TODO-22 uniquement (délai de 48h dépassé, membre bloqué sans
-- donnée) — même pattern que debt_settlement_requests (0003_money.sql) : le client
-- constate une condition et poste une demande idempotente, aucun pg_cron disponible
-- sur ce projet (même contrainte que TODO-25).
create table public.group_finalization_requests (
    id uuid primary key default gen_random_uuid(),
    group_id uuid not null references public.groups (id) on delete cascade,
    local_date date not null,
    requested_at timestamptz not null default now(),
    unique (group_id, local_date)
);

-- ---------------------------------------------------------------- generate_group_code
create function public.generate_group_code()
returns text
language plpgsql
security definer
set search_path = public
as $$
declare
    v_alphabet text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'; -- sans 0/O/1/I ambigus
    v_code text;
begin
    loop
        v_code := '';
        for i in 1..6 loop
            v_code := v_code || substr(v_alphabet, 1 + floor(random() * length(v_alphabet))::int, 1);
        end loop;
        exit when not exists (select 1 from public.groups where code = v_code);
    end loop;
    return v_code;
end;
$$;

-- ---------------------------------------------------------------- create_group
-- SPEC §12.1 : objectif payant actif obligatoire, imposé en base — jamais seulement
-- côté client. Insère aussi la ligne group_members ACTIVE du créateur dans la même
-- transaction (le créateur ne "demande" pas à rejoindre son propre groupe).
create function public.create_group()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    g record;
begin
    select * into g from public.goals
    where id = new.creator_goal_id and user_id = new.creator_id and kind = 'STAKED' and status = 'ACTIVE';

    if g.id is null then
        raise exception 'creator_goal_id doit être un objectif payant actif appartenant au créateur (SPEC §12.1)';
    end if;

    new.code := public.generate_group_code();
    new.timezone := g.timezone;
    new.end_date := g.end_date;

    return new;
end;
$$;

create trigger on_group_insert_create
    before insert on public.groups
    for each row execute function public.create_group();

create function public.insert_creator_membership()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.group_members (group_id, user_id, goal_id, status)
    values (new.id, new.creator_id, new.creator_goal_id, 'ACTIVE');
    return new;
end;
$$;

create trigger on_group_insert_add_creator
    after insert on public.groups
    for each row execute function public.insert_creator_membership();

-- ---------------------------------------------------------------- apply_daily_outcome
-- Factorise la logique financière/série/exploits déclenchée par un daily_success
-- CONNU — appelée immédiatement pour un utilisateur solo (depuis evaluate_measurement_
-- batch) et en différé pour un membre de groupe (depuis resolve_group_day). Ne jamais
-- dupliquer cette logique entre les deux chemins : c'est exactement le code qui
-- existait déjà en ligne dans evaluate_measurement_batch depuis 0002/0003/0004/0005,
-- déplacé ici sans changement de comportement pour reproduire fidèlement TODO-01/
-- TODO-09/TODO-10.
create function public.apply_daily_outcome(p_goal_id uuid, p_local_date date, p_daily_success boolean)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    g record;
    dr record;
    share_cents bigint;
    debt_row record;
    new_streak integer;
begin
    select * into g from public.goals where id = p_goal_id;

    update public.daily_results
    set status = case when p_daily_success then 'SUCCESS' else 'FAILED' end,
        daily_success = p_daily_success,
        evaluated_at = now()
    where goal_id = p_goal_id and local_date = p_local_date
    returning * into dr;

    if g.kind = 'STAKED' then
        update public.daily_stake_shares
        set outcome = case when p_daily_success then 'SECURED' else 'FORFEITED' end
        where goal_id = p_goal_id and local_date = p_local_date;

        if not p_daily_success then
            select amount_cents into share_cents
            from public.daily_stake_shares
            where goal_id = p_goal_id and local_date = p_local_date;

            select * into debt_row from public.debts where goal_id = p_goal_id;
            if debt_row.status = 'PENDING' then
                update public.debts
                set amount_cents = amount_cents + share_cents
                where id = debt_row.id;

                insert into public.ledger_entries (
                    goal_id, user_id, debt_id, local_date, entry_type, amount_cents, simulated, idempotency_key
                )
                values (
                    p_goal_id, g.user_id, debt_row.id, p_local_date, 'DEBT_ACCRUED', share_cents, true,
                    p_goal_id::text || ':' || p_local_date::text || ':DEBT_ACCRUED'
                )
                on conflict (idempotency_key) do nothing;
            end if;
        end if;
    end if;

    update public.goals
    set current_streak_days = case when p_daily_success then current_streak_days + 1 else 0 end,
        best_streak_days = greatest(
            best_streak_days,
            case when p_daily_success then current_streak_days + 1 else best_streak_days end
        )
    where id = p_goal_id
    returning current_streak_days into new_streak;

    if p_daily_success then
        update public.users
        set best_streak_days = greatest(best_streak_days, new_streak)
        where id = g.user_id;

        if new_streak >= 7 then
            perform public.award_exploit(g.user_id, 'STREAK_7');
        end if;
        if new_streak >= 30 then
            perform public.award_exploit(g.user_id, 'STREAK_30');
        end if;
        if new_streak >= 100 then
            perform public.award_exploit(g.user_id, 'STREAK_100');
        end if;

        if g.constraint_type in ('TIME', 'BOTH') and dr.measured_time_seconds <= g.time_limit_seconds / 2 then
            perform public.award_exploit(g.user_id, 'TIME_HALF_LIMIT');
        end if;
        if g.constraint_type in ('OPENS', 'BOTH') and dr.measured_opens <= g.opens_limit / 2 then
            perform public.award_exploit(g.user_id, 'OPENS_HALF_LIMIT');
        end if;
    end if;

    if p_local_date = g.start_date + (g.duration_days - 1) then
        perform public.finalize_goal_completion(p_goal_id);
    end if;
end;
$$;

-- ---------------------------------------------------------------- finalize_goal_completion
-- Volet financier de la fin d'objectif (règlement à zéro, exploits liés au montant final
-- de la dette) — distinct du basculement `goals.status = 'COMPLETED'` (calendaire,
-- immédiat, voir evaluate_measurement_batch) car pour un membre de groupe, le montant
-- final de la dette n'est connu qu'après résolution collective du dernier jour :
-- appelée depuis apply_daily_outcome (chemin solo immédiat OU groupe différé), jamais
-- directement depuis evaluate_measurement_batch pour un jour non-NO_DATA.
create function public.finalize_goal_completion(p_goal_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    g record;
    debt_amount_after bigint;
    completed_staked_count integer;
begin
    select * into g from public.goals where id = p_goal_id;
    if g.kind != 'STAKED' then
        return;
    end if;

    update public.debts
    set status = 'SETTLED', settled_at = now()
    where goal_id = p_goal_id and status = 'PENDING' and amount_cents = 0;

    select amount_cents into debt_amount_after from public.debts where goal_id = p_goal_id;

    if debt_amount_after = 0 then
        perform public.award_exploit(g.user_id, 'STAKED_GOAL_FLAWLESS');
    end if;

    if g.duration_days >= 90 then
        perform public.award_exploit(g.user_id, 'LONG_GOAL_COMPLETED');
    end if;

    select count(*) into completed_staked_count
    from public.goals
    where user_id = g.user_id and kind = 'STAKED' and status = 'COMPLETED';

    if completed_staked_count >= 3 then
        perform public.award_exploit(g.user_id, 'THREE_STAKED_GOALS_COMPLETED');
    end if;
end;
$$;

-- ---------------------------------------------------------------- finalize_departed_members
-- Bascule paresseuse LEAVING -> LEFT/REMOVED une fois left_effective_at dépassé.
-- Appelée en tête de resolve_group_day() : pas de job planifié séparé (pas de pg_cron,
-- même contrainte que TODO-25), l'état est à jour au moment où il compte réellement.
create function public.finalize_departed_members(p_group_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.group_members
    set status = case when leave_initiated_by = 'CREATOR' then 'REMOVED' else 'LEFT' end
    where group_id = p_group_id
      and status = 'LEAVING'
      and left_effective_at <= now();
end;
$$;

-- ---------------------------------------------------------------- active_group_members_for
-- TODO-21 (tranché avec l'utilisateur, PRODUCT_TODOS-2.md) : un membre compte pour la
-- journée collective D s'il est ACTIVE/LEAVING, OU s'il est LEFT/REMOVED mais que D est
-- antérieure ou égale à la journée (dans le fuseau du GROUPE, pour l'étiquetage — SPEC
-- §12.8) contenant son instant de sortie effective. Il disparaît du calcul à partir de
-- la journée collective suivante.
create function public.active_group_members_for(p_group_id uuid, p_local_date date)
returns table (user_id uuid, goal_id uuid)
language sql
security definer
set search_path = public
stable
as $$
    select gm.user_id, gm.goal_id
    from public.group_members gm
    join public.groups g on g.id = gm.group_id
    where gm.group_id = p_group_id
      and (
          gm.status in ('ACTIVE', 'LEAVING')
          or (
              gm.status in ('LEFT', 'REMOVED')
              and (gm.left_effective_at at time zone g.timezone)::date >= p_local_date
          )
      );
$$;

-- ---------------------------------------------------------------- resolve_group_day
-- Cœur du moteur collectif (ARCHITECTURE-2.md §5 : evaluerJourneeGroupe/
-- resoudreJourneeFinale). Jamais déclenché par une horloge (§12.8) : rappelée par
-- evaluate_measurement_batch à chaque journée personnelle qui se clôture pour un
-- membre du groupe, et par le filet de sécurité TODO-22 (request_group_day_
-- finalization). Idempotente : ne fait rien si déjà résolue ou si des membres
-- manquent encore et que le délai n'est pas dépassé.
--
-- PAS de paramètre "forcer" : le client ne peut jamais faire confiance à sa propre
-- horloge pour une décision financière (ARCHITECTURE-2.md §3 : "heure serveur faisant
-- foi"). Le filet de sécurité TODO-22 (request_group_day_finalization) appelle EXACTEMENT
-- la même fonction que le chemin normal — c'est le délai vérifié ci-dessous, calculé
-- côté serveur à chaque appel, qui décide seul si une finalisation forcée est légitime.
create function public.resolve_group_day(p_group_id uuid, p_local_date date)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    g record;
    deadline_hours integer;
    member record;
    dr record;
    all_ready boolean := true;
    group_success boolean := true;
    considered_count integer := 0;
begin
    -- Verrou transactionnel sur (group_id, local_date) : deux membres du même groupe
    -- peuvent soumettre leur mesure quasi simultanément (deux transactions séparées),
    -- chacune appelant resolve_group_day — sans ce verrou, les deux pourraient se voir
    -- mutuellement "pas encore prêtes" puis finaliser en double (dette/série comptées
    -- deux fois). Relâché automatiquement à la fin de la transaction.
    perform pg_advisory_xact_lock(hashtext(p_group_id::text || ':' || p_local_date::text));

    if exists (select 1 from public.group_daily_results where group_id = p_group_id and local_date = p_local_date) then
        return; -- déjà résolue
    end if;

    perform public.finalize_departed_members(p_group_id);

    select * into g from public.groups where id = p_group_id;
    select (value #>> '{}')::integer into deadline_hours
    from public.app_config where key = 'group_finalisation_deadline_hours';

    -- "Dernière journée personnelle attendue close" = fin de la fenêtre du membre le
    -- plus en retard, approximée ici par la fin de la journée collective D dans le
    -- fuseau du groupe (SPEC §12.8 : décalage de finalisation = écart d'offset + délai
    -- de grâce, borné à 26h dans le pire cas mondial ; le délai TODO-22 s'ajoute à
    -- cette borne, pas à l'instant D lui-même).
    if now() < ((p_local_date + 1)::timestamp at time zone g.timezone) + (deadline_hours || ' hours')::interval then
        for member in select * from public.active_group_members_for(p_group_id, p_local_date) loop
            select * into dr from public.daily_results
            where goal_id = member.goal_id and local_date = p_local_date;
            -- daily_results n'a pas de colonne id (clé primaire composite goal_id +
            -- local_date) — local_date (not null sur la table) sert de test "ligne
            -- trouvée" : reste null sur dr si le select ci-dessus ne trouve rien.
            if dr.local_date is null or dr.status = 'PENDING' then
                all_ready := false;
                exit;
            end if;
        end loop;
        if not all_ready then
            return; -- pas encore prêt, pas encore le délai : on réessaiera au prochain appel
        end if;
    end if;

    for member in select * from public.active_group_members_for(p_group_id, p_local_date) loop
        select * into dr from public.daily_results
        where goal_id = member.goal_id and local_date = p_local_date;

        -- Membre encore sans donnée à l'échéance forcée (TODO-22) : exclu du calcul,
        -- ni pour ni contre — décision d'implémentation documentée dans PRODUCT_TODOS-2.md.
        if dr.local_date is null or dr.status = 'PENDING' then
            continue;
        end if;
        -- NO_DATA : sans conséquence financière automatique (TODO-06), exclu du calcul
        -- de group_success au même titre.
        if dr.status = 'NO_DATA' then
            continue;
        end if;

        considered_count := considered_count + 1;
        if not dr.personal_success then
            group_success := false;
        end if;
    end loop;

    if considered_count = 0 then
        return; -- rien à résoudre (groupe vide de données ce jour-là)
    end if;

    insert into public.group_daily_results (group_id, local_date, status)
    values (p_group_id, p_local_date, case when group_success then 'SUCCESS' else 'FAILED' end)
    on conflict (group_id, local_date) do nothing;

    for member in select * from public.active_group_members_for(p_group_id, p_local_date) loop
        select * into dr from public.daily_results
        where goal_id = member.goal_id and local_date = p_local_date;
        if dr.local_date is not null and dr.status = 'PROVISIONAL' then
            perform public.apply_daily_outcome(member.goal_id, p_local_date, dr.personal_success and group_success);
        end if;
    end loop;
end;
$$;

-- ---------------------------------------------------------------- request_group_day_finalization
-- Filet de sécurité TODO-22 : le client poste une demande (idempotente, unique(group_id,
-- local_date)) quand il constate qu'une de ses journées est PROVISIONAL depuis plus du
-- délai — voir GroupRepository.requestFinalizationIfStale, appelée depuis
-- TrackingSyncWorker comme settlePendingDebtIfGoalCompleted.
create function public.request_group_day_finalization()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    perform public.resolve_group_day(new.group_id, new.local_date);
    return new;
end;
$$;

create trigger on_group_finalization_request_insert
    after insert on public.group_finalization_requests
    for each row execute function public.request_group_day_finalization();

-- ---------------------------------------------------------------- evaluate_measurement_batch (étendu, Phase 8)
-- Remplace la fonction posée par 0002_goals.sql, étendue par 0003/0004/0005. Ajouts
-- marqués "-- PHASE 8". Comportement d'un utilisateur SANS groupe strictement
-- inchangé : personal_success = daily_success, résolu immédiatement via
-- apply_daily_outcome (même résultat qu'avant, juste factorisé).
create or replace function public.evaluate_measurement_batch()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    g record;
    success boolean;
    last_day date;
    membership record; -- PHASE 8
begin
    select * into g from public.goals where id = new.goal_id;
    last_day := g.start_date + (g.duration_days - 1);

    -- PHASE 8 : appartenance de groupe active (au plus une, SPEC §12.1).
    select gm.* into membership
    from public.group_members gm
    where gm.goal_id = new.goal_id and gm.status in ('ACTIVE', 'LEAVING');

    if new.data_quality = 'UNAVAILABLE' then
        insert into public.daily_results (goal_id, local_date, status, measured_time_seconds, measured_opens, evaluated_at)
        values (new.goal_id, new.local_date, 'NO_DATA', new.measured_time_seconds, new.measured_opens, now())
        on conflict (goal_id, local_date) do update set
            status = 'NO_DATA', evaluated_at = now();
        -- PHASE 8 : un membre NO_DATA ne bloque pas les autres indéfiniment — rappelle
        -- la résolution du groupe (sans effet tant que le délai TODO-22 n'est pas atteint).
        if membership.group_id is not null then
            perform public.resolve_group_day(membership.group_id, new.local_date);
        end if;
    else
        success := true;
        if g.constraint_type in ('TIME', 'BOTH') then
            success := success and new.measured_time_seconds <= g.time_limit_seconds;
        end if;
        if g.constraint_type in ('OPENS', 'BOTH') then
            success := success and new.measured_opens <= g.opens_limit;
        end if;

        if membership.group_id is null then
            -- PHASE 8 : pas de groupe — chemin solo inchangé, résolution immédiate.
            insert into public.daily_results (
                goal_id, local_date, status, personal_success, daily_success,
                measured_time_seconds, measured_opens, evaluated_at
            )
            values (
                new.goal_id, new.local_date, 'PENDING', success, null,
                new.measured_time_seconds, new.measured_opens, now()
            )
            on conflict (goal_id, local_date) do update set
                personal_success = excluded.personal_success,
                measured_time_seconds = excluded.measured_time_seconds,
                measured_opens = excluded.measured_opens;

            perform public.apply_daily_outcome(new.goal_id, new.local_date, success);
        else
            -- PHASE 8 : membre de groupe — s'arrête à PROVISIONAL, daily_success
            -- connu seulement après resolve_group_day (ARCHITECTURE-2.md §4).
            insert into public.daily_results (
                goal_id, local_date, status, personal_success, daily_success,
                measured_time_seconds, measured_opens, evaluated_at
            )
            values (
                new.goal_id, new.local_date, 'PROVISIONAL', success, null,
                new.measured_time_seconds, new.measured_opens, now()
            )
            on conflict (goal_id, local_date) do update set
                status = 'PROVISIONAL',
                personal_success = excluded.personal_success,
                measured_time_seconds = excluded.measured_time_seconds,
                measured_opens = excluded.measured_opens;

            perform public.resolve_group_day(membership.group_id, new.local_date);
        end if;
    end if;

    -- Fin d'objectif (PRODUCT_SPEC-2.md §13) : bascule calendaire, immédiate, jamais
    -- déclenchée par une horloge — indépendante de la résolution financière collective
    -- (voir apply_daily_outcome/finalize_goal_completion, qui gèrent le volet
    -- financier séparément pour un membre de groupe). Dernière journée évaluée, quel
    -- que soit son statut (y compris NO_DATA, comportement inchangé depuis 0002).
    if new.local_date = last_day and g.status = 'ACTIVE' then
        update public.goals set status = 'COMPLETED' where id = new.goal_id;

        -- PHASE 8 : fin naturelle d'objectif (TODO-05, déjà tranché) — sortie
        -- immédiate du groupe, sans délai de 24h, contrairement au départ volontaire.
        if membership.group_id is not null then
            update public.group_members
            set status = 'LEFT', leave_initiated_by = 'NATURAL_END', left_effective_at = now()
            where group_id = membership.group_id and user_id = g.user_id;
        end if;

        insert into public.feed_events (user_id, event_type, source_id, content)
        values (
            g.user_id, 'GOAL_COMPLETED', g.id,
            jsonb_build_object('kind', g.kind, 'constraint_type', g.constraint_type, 'duration_days', g.duration_days)
        )
        on conflict (event_type, source_id) do nothing;

        -- Volet financier (règlement à zéro, exploits liés au montant final) :
        -- uniquement ici pour un jour NO_DATA. Pour tout autre cas (solo, ou membre de
        -- groupe dont le dernier jour se résout — immédiatement ou plus tard), c'est
        -- déjà fait par apply_daily_outcome (appelée directement ci-dessus pour un
        -- utilisateur solo, ou via resolve_group_day pour un membre de groupe) — ne
        -- jamais appeler finalize_goal_completion deux fois pour le même goal_id.
        if new.data_quality = 'UNAVAILABLE' then
            perform public.finalize_goal_completion(new.goal_id);
        end if;
    end if;

    return new;
end;
$$;

-- ---------------------------------------------------------------- helpers RLS group_members
-- Piège RLS classique : une policy sur group_members qui interroge group_members
-- lui-même (même via un alias) déclenche "infinite recursion detected in policy for
-- relation group_members" — Postgres réévalue la policy à chaque référence à la
-- table. Constaté en conditions réelles (création de groupe) avant que la Phase 8 ne
-- soit déclarée acquise. Corrigé en passant par des fonctions SECURITY DEFINER (même
-- idiome que can_view_feed_event, 0005_social.sql) : elles s'exécutent avec les
-- privilèges du propriétaire de la fonction, qui contourne RLS sur les tables qu'elles
-- lisent — la référence à group_members à l'intérieur ne redéclenche donc jamais la
-- policy appelante. Définies ici, avant les policies de groups/group_members qui les
-- utilisent toutes les deux (l'ordre de définition compte en SQL, contrairement à
-- l'ordre d'appel).
create function public.is_active_group_member(p_user_id uuid, p_group_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
    select exists (
        select 1 from public.group_members
        where user_id = p_user_id and group_id = p_group_id and status in ('ACTIVE', 'LEAVING')
    );
$$;

create function public.user_has_group_membership(p_user_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
    select exists (
        select 1 from public.group_members
        where user_id = p_user_id and status in ('PENDING', 'ACTIVE', 'LEAVING')
    );
$$;

create function public.group_active_member_count(p_group_id uuid)
returns integer
language sql
security definer
set search_path = public
stable
as $$
    select count(*)::integer from public.group_members
    where group_id = p_group_id and status in ('ACTIVE', 'LEAVING');
$$;

-- ---------------------------------------------------------------- RLS
alter table public.groups enable row level security;

create policy groups_select_by_code
    on public.groups for select
    to authenticated
    using (true);
-- Lecture ouverte à tout authentifié : nécessaire pour résoudre un code en id de
-- groupe avant la demande d'adhésion (GroupRepository.joinByCode interroge `groups`
-- par `code` directement — pas de RPC dans ce projet, voir SupabaseSocialGraphRepository
-- pour le même choix). Champs non sensibles (pas de solde) ; un code d'invitation à 6
-- caractères n'a pas vocation à rester confidentiel une fois partagé (§10.1 ne le
-- classe pas comme donnée privée, contrairement à birth_date sur users).

create policy groups_insert_self
    on public.groups for insert
    to authenticated
    with check (creator_id = auth.uid() and not public.user_has_group_membership(auth.uid()));
-- Pas d'UPDATE ni de DELETE côté client : timezone/end_date immuables (§12.3/§12.4).

alter table public.group_members enable row level security;

create policy group_members_select_same_group
    on public.group_members for select
    to authenticated
    using (user_id = auth.uid() or public.is_active_group_member(auth.uid(), group_id));

create policy group_members_insert_self
    on public.group_members for insert
    to authenticated
    with check (
        user_id = auth.uid()
        and not public.user_has_group_membership(auth.uid())
        and exists (
            select 1 from public.goals gl
            join public.groups g on g.id = group_id
            where gl.id = goal_id and gl.user_id = auth.uid() and gl.kind = 'STAKED' and gl.status = 'ACTIVE'
              and gl.end_date >= g.end_date
        )
        and public.group_active_member_count(group_id)
            < (select (value #>> '{}')::integer from public.app_config where key = 'max_group_members')
    );
-- Les 3 conditions cumulatives de SPEC §12.5 : objectif payant actif propre au
-- demandeur, end_date >= end_date du groupe, groupe pas plein (demandes PENDING non
-- comptées dans le plafond — ce sont des demandes, pas des membres, choix
-- d'implémentation non tranché explicitement par la SPEC).

create policy group_members_update_creator
    on public.group_members for update
    to authenticated
    using (
        exists (select 1 from public.groups g where g.id = group_id and g.creator_id = auth.uid())
        and status in ('PENDING', 'ACTIVE')
    )
    with check (
        exists (select 1 from public.groups g where g.id = group_id and g.creator_id = auth.uid())
        and status in ('ACTIVE', 'LEAVING')
    );
-- Le créateur accepte (PENDING -> ACTIVE) ou exclut (ACTIVE -> LEAVING,
-- leave_initiated_by = 'CREATOR', leave_requested_at = now(), left_effective_at =
-- now() + 24h). `using`/`with check` bornent l'état avant/après à ce que ces deux
-- actions produisent réellement (jamais de résurrection d'une ligne LEFT/REMOVED) ;
-- la distinction fine entre les deux transitions reste une responsabilité
-- applicative, comme goals_insert_self ne valide pas toutes les règles de contenu.

create policy group_members_update_self_leave
    on public.group_members for update
    to authenticated
    using (user_id = auth.uid() and status = 'ACTIVE')
    with check (user_id = auth.uid() and status = 'LEAVING');
-- Départ volontaire : ACTIVE -> LEAVING uniquement (leave_initiated_by = 'SELF',
-- même mécanique de délai de 24h).

create policy group_members_delete_pending
    on public.group_members for delete
    to authenticated
    using (
        status = 'PENDING'
        and (user_id = auth.uid() or exists (select 1 from public.groups g where g.id = group_id and g.creator_id = auth.uid()))
    );
-- Une demande jamais acceptée n'a pas vocation à laisser une trace (contrairement à
-- LEFT/REMOVED, terminaux et conservés pour l'historique/la série).

alter table public.group_daily_results enable row level security;

create policy group_daily_results_select_members
    on public.group_daily_results for select
    to authenticated
    using (
        exists (
            select 1 from public.group_members gm
            where gm.group_id = group_id and gm.user_id = auth.uid() and gm.status in ('ACTIVE', 'LEAVING', 'LEFT', 'REMOVED')
        )
    );
-- Aucune policy d'écriture cliente : voir commentaire sur la table plus haut.

alter table public.group_finalization_requests enable row level security;

create policy group_finalization_requests_insert_member
    on public.group_finalization_requests for insert
    to authenticated
    with check (
        exists (
            select 1 from public.group_members gm
            where gm.group_id = group_id and gm.user_id = auth.uid() and gm.status in ('ACTIVE', 'LEAVING')
        )
    );
-- Pas de policy SELECT : le client n'a jamais besoin de relire ces lignes, seulement
-- d'en poster (le trigger AFTER INSERT fait le travail).
