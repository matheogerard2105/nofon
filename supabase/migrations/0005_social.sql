-- Nofon — Phase 7 : social.
--
-- Recherche, follow, profil public (compteurs agrégés), fil chronologique auto-généré
-- (PRODUCT_SPEC-2.md §10 : "aucune publication manuelle" — uniquement nouvel exploit /
-- objectif terminé), commentaires, réactions, notifications internes, blocage,
-- signalement. Hors périmètre, documenté inline plus bas : notifications de type
-- "réponse" (pas de fil de discussion, commentaires à plat) et "invitation/acceptation/
-- événements de groupe" (Phase 8, non atteinte).
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor),
-- après 0001_init.sql, 0002_goals.sql, 0003_money.sql et 0004_exploits.sql.

-- ---------------------------------------------------------------- app_config (ajouts)
-- TODO-28 (PRODUCT_TODOS-2.md) : seuils du mécanisme de limitation de débit, décision
-- provisoire. Ne couvre pas la recherche (lecture seule, aucun point d'ancrage INSERT).
insert into public.app_config (key, value) values
    ('rate_limit_follow_per_hour', '60'),
    ('rate_limit_comment_per_hour', '30'),
    ('rate_limit_report_per_hour', '10');

-- ---------------------------------------------------------------- follows
create table public.follows (
    follower_id uuid not null references public.users (id) on delete cascade,
    followee_id uuid not null references public.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (follower_id, followee_id),
    constraint follows_no_self_follow check (follower_id <> followee_id)
);

create index follows_followee_id_idx on public.follows (followee_id);

comment on table public.follows is
    'Abonnements. Liste publique (PRODUCT_SPEC-2.md §10.1 : abonnés/abonnements sont des '
    'champs publics du profil).';

-- ---------------------------------------------------------------- blocks
-- Liste privée (contrairement à follows) : PRODUCT_SPEC-2.md ne liste jamais "utilisateurs
-- bloqués" parmi les champs publics du profil (§10.1).
create table public.blocks (
    blocker_id uuid not null references public.users (id) on delete cascade,
    blocked_id uuid not null references public.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    constraint blocks_no_self_block check (blocker_id <> blocked_id)
);

-- ---------------------------------------------------------------- reports
-- PRODUCT_TODOS-2.md, "AJOUTS NÉCESSAIRES IDENTIFIÉS À L'AUDIT" : blocage + signalement,
-- exigence DSA (UE). Capture uniquement — AUCUNE action de modération automatisée n'est
-- implémentée ici (revue externe hors périmètre applicatif, même logique que TODO-13/14
-- pour la dette : pas de politique inventée sans être demandée).
create table public.reports (
    id uuid primary key default gen_random_uuid(),
    reporter_id uuid not null references public.users (id) on delete cascade,
    target_type text not null check (target_type in ('USER', 'COMMENT', 'FEED_EVENT')),
    target_id uuid not null,
    reason text not null check (char_length(reason) between 1 and 500),
    status text not null default 'OPEN' check (status in ('OPEN', 'REVIEWED')),
    created_at timestamptz not null default now()
);

-- ---------------------------------------------------------------- feed_events
-- Aucune écriture client (ARCHITECTURE-2.md : même principe que daily_results/
-- user_exploits). Peuplée uniquement par award_exploit() et evaluate_measurement_batch()
-- ci-dessous, étendues pour cette phase. content fige un instantané d'affichage au
-- moment de l'événement — jamais recalculé après coup (même idiome que
-- user_exploits.rarity_at_unlock, 0004_exploits.sql).
create table public.feed_events (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.users (id) on delete cascade,
    event_type text not null check (event_type in ('GOAL_COMPLETED', 'EXPLOIT_UNLOCKED')),
    source_id uuid not null,
    content jsonb not null,
    created_at timestamptz not null default now(),
    -- Idempotence (même pattern que ledger_entries.idempotency_key, 0003_money.sql) :
    -- un événement source ne produit jamais deux entrées de fil.
    unique (event_type, source_id)
);

create index feed_events_user_id_created_at_idx on public.feed_events (user_id, created_at desc);

-- ---------------------------------------------------------------- comments
create table public.comments (
    id uuid primary key default gen_random_uuid(),
    feed_event_id uuid not null references public.feed_events (id) on delete cascade,
    author_id uuid not null references public.users (id) on delete cascade,
    body text not null check (char_length(body) between 1 and 500),
    created_at timestamptz not null default now()
);

create index comments_feed_event_id_idx on public.comments (feed_event_id);

-- ---------------------------------------------------------------- reactions
-- Un seul type de réaction : PRODUCT_SPEC-2.md §10 dit seulement "réagir", aucune
-- taxonomie donnée — pas d'invention de types non demandés.
create table public.reactions (
    feed_event_id uuid not null references public.feed_events (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (feed_event_id, user_id)
);

-- ---------------------------------------------------------------- notifications
-- Aucune écriture client — peuplée par les triggers AFTER INSERT ci-dessous sur
-- follows/reactions/comments (PRODUCT_SPEC-2.md §11 : "nouvel abonné, réaction,
-- commentaire, réponse, invitation groupe, acceptation, événements de groupe"). Cette
-- phase ne couvre que les trois premiers types : pas de fil de discussion sur les
-- commentaires (donc pas de "réponse"), groupes = Phase 8 non atteinte (donc pas
-- d'invitation/acceptation/événement de groupe) — même séquencement que TODO-26 pour
-- les exploits "réseaux sociaux/jeux/groupes", pas une ambiguïté produit à trancher ici.
create table public.notifications (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.users (id) on delete cascade,
    type text not null check (type in ('NEW_FOLLOWER', 'REACTION', 'COMMENT')),
    actor_id uuid references public.users (id) on delete set null,
    feed_event_id uuid references public.feed_events (id) on delete cascade,
    comment_id uuid references public.comments (id) on delete cascade,
    read_at timestamptz,
    created_at timestamptz not null default now()
);

create index notifications_user_id_created_at_idx on public.notifications (user_id, created_at desc);

-- ---------------------------------------------------------------- rate_limit_hits
-- TODO-28 (PRODUCT_TODOS-2.md) : décision provisoire. Fenêtre fixe d'une heure (pas de
-- fenêtre glissante) : granularité suffisante pour dissuader un abus grossier sans
-- introduire de logique de bucket roulant non demandée. Table purement interne — voir
-- RLS plus bas (aucune policy, donc aucun accès via l'API cliente).
create table public.rate_limit_hits (
    user_id uuid not null references public.users (id) on delete cascade,
    action text not null,
    window_start timestamptz not null,
    hit_count integer not null default 1,
    primary key (user_id, action, window_start)
);

-- ---------------------------------------------------------------- public_profile_stats
-- Vue calculée à la demande (même idiome que exploit_rarity_stats, 0004_exploits.sql :
-- pas de table dénormalisée, toujours exacte). PAS de security_invoker : les compteurs
-- doivent agréger goals/daily_results de N'IMPORTE QUEL utilisateur, alors que
-- goals_select_self (0002_goals.sql) restreint la lecture directe au seul propriétaire.
-- N'expose que des totaux/maximums agrégés, jamais une ligne goals brute — même principe
-- de fuite nulle que count_active_users (0004_exploits.sql). Champs alignés sur
-- PRODUCT_SPEC-2.md §10.1 : pseudo, avatar, série actuelle, meilleure série, abonnés,
-- abonnements, objectifs en cours/terminés, taux de réussite.
create view public.public_profile_stats as
select
    u.id as user_id,
    u.handle,
    u.avatar_url,
    u.best_streak_days,
    (select max(g.current_streak_days) from public.goals g where g.user_id = u.id and g.status = 'ACTIVE')
        as current_streak_days,
    (select count(*) from public.follows f where f.followee_id = u.id) as followers_count,
    (select count(*) from public.follows f where f.follower_id = u.id) as following_count,
    (select count(*) from public.goals g where g.user_id = u.id and g.status = 'ACTIVE') as active_goals_count,
    (select count(*) from public.goals g where g.user_id = u.id and g.status = 'COMPLETED') as completed_goals_count,
    (
        select round(100.0 * count(*) filter (where dr.daily_success) / nullif(count(*), 0), 1)
        from public.daily_results dr
        join public.goals g on g.id = dr.goal_id
        where g.user_id = u.id and dr.status in ('SUCCESS', 'FAILED')
    ) as success_rate_percent
from public.users u
where u.deleted_at is null;

-- ---------------------------------------------------------------- can_view_feed_event
-- Prédicat de visibilité réutilisé par les policies de feed_events/comments/reactions
-- (même logique de factorisation qu'award_exploit : un point d'entrée unique plutôt que
-- de dupliquer le exists trois fois). SECURITY DEFINER pour évaluer follows/blocks sans
-- dépendre de la policy appelante elle-même (évite une évaluation RLS circulaire).
create function public.can_view_feed_event(p_feed_event_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
    select exists (
        select 1 from public.feed_events fe
        where fe.id = p_feed_event_id
          and (
              fe.user_id = auth.uid()
              or exists (
                  select 1 from public.follows f
                  where f.follower_id = auth.uid() and f.followee_id = fe.user_id
              )
          )
          and not exists (
              select 1 from public.blocks b
              where (b.blocker_id = fe.user_id and b.blocked_id = auth.uid())
                 or (b.blocker_id = auth.uid() and b.blocked_id = fe.user_id)
          )
    );
$$;

-- ---------------------------------------------------------------- feed_event_engagement_stats
-- Vue calculée à la demande (même idiome que exploit_rarity_stats/public_profile_stats).
-- security_invoker = true (contrairement à public_profile_stats) : ici on VEUT que les
-- compteurs respectent la visibilité de l'appelant sur reactions/comments (déjà correcte
-- pour un feed_event qu'il peut voir, via can_view_feed_event). Évite un aller-retour par
-- item (N+1) côté client pour afficher un compteur de réactions/commentaires et "ai-je
-- réagi" sur une page de fil.
create view public.feed_event_engagement_stats
with (security_invoker = true) as
select
    fe.id as feed_event_id,
    (select count(*) from public.reactions r where r.feed_event_id = fe.id) as reaction_count,
    (select count(*) from public.comments c where c.feed_event_id = fe.id) as comment_count,
    exists (
        select 1 from public.reactions r where r.feed_event_id = fe.id and r.user_id = auth.uid()
    ) as did_i_react
from public.feed_events fe;

-- ---------------------------------------------------------------- check_rate_limit / enforce_rate_limit
-- TODO-28. check_rate_limit est le point d'entrée générique (idempotent par construction
-- via on conflict ... do update, même famille d'idiome que award_exploit) ;
-- enforce_rate_limit est le trigger générique qui l'appelle, paramétré par TG_ARGV pour
-- éviter de dupliquer une fonction de trigger quasi identique par table.
create function public.check_rate_limit(p_action text, p_max_per_hour integer)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_bucket timestamptz := date_trunc('hour', now());
    v_count integer;
begin
    insert into public.rate_limit_hits (user_id, action, window_start, hit_count)
    values (auth.uid(), p_action, v_bucket, 1)
    on conflict (user_id, action, window_start)
    do update set hit_count = public.rate_limit_hits.hit_count + 1
    returning hit_count into v_count;

    if v_count > p_max_per_hour then
        raise exception 'rate_limit_exceeded: % (% par heure)', p_action, p_max_per_hour;
    end if;
end;
$$;

create function public.enforce_rate_limit()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_action text := TG_ARGV[0];
    v_config_key text := TG_ARGV[1];
    v_max integer;
begin
    select (value #>> '{}')::integer into v_max from public.app_config where key = v_config_key;
    perform public.check_rate_limit(v_action, v_max);
    return new;
end;
$$;

create trigger on_follow_insert_rate_limit
    before insert on public.follows
    for each row execute function public.enforce_rate_limit('follow', 'rate_limit_follow_per_hour');

create trigger on_comment_insert_rate_limit
    before insert on public.comments
    for each row execute function public.enforce_rate_limit('comment', 'rate_limit_comment_per_hour');

create trigger on_report_insert_rate_limit
    before insert on public.reports
    for each row execute function public.enforce_rate_limit('report', 'rate_limit_report_per_hour');

-- ---------------------------------------------------------------- trigger : blocage annule le suivi
create function public.unfollow_on_block()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    delete from public.follows
    where (follower_id = new.blocker_id and followee_id = new.blocked_id)
       or (follower_id = new.blocked_id and followee_id = new.blocker_id);
    return new;
end;
$$;

create trigger on_block_insert_unfollow
    after insert on public.blocks
    for each row execute function public.unfollow_on_block();

-- ---------------------------------------------------------------- triggers : notifications internes
create function public.notify_new_follower()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.notifications (user_id, type, actor_id)
    values (new.followee_id, 'NEW_FOLLOWER', new.follower_id);
    return new;
end;
$$;

create trigger on_follow_insert_notify
    after insert on public.follows
    for each row execute function public.notify_new_follower();

create function public.notify_reaction()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_recipient uuid;
begin
    select user_id into v_recipient from public.feed_events where id = new.feed_event_id;
    if v_recipient is not null and v_recipient <> new.user_id then
        insert into public.notifications (user_id, type, actor_id, feed_event_id)
        values (v_recipient, 'REACTION', new.user_id, new.feed_event_id);
    end if;
    return new;
end;
$$;

create trigger on_reaction_insert_notify
    after insert on public.reactions
    for each row execute function public.notify_reaction();

create function public.notify_comment()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    v_recipient uuid;
begin
    select user_id into v_recipient from public.feed_events where id = new.feed_event_id;
    if v_recipient is not null and v_recipient <> new.author_id then
        insert into public.notifications (user_id, type, actor_id, feed_event_id, comment_id)
        values (v_recipient, 'COMMENT', new.author_id, new.feed_event_id, new.id);
    end if;
    return new;
end;
$$;

create trigger on_comment_insert_notify
    after insert on public.comments
    for each row execute function public.notify_comment();

-- ---------------------------------------------------------------- award_exploit (étendu, Phase 7)
-- Remplace la fonction posée par 0004_exploits.sql. Ajout marqué "-- PHASE 7" : alimente
-- le fil uniquement sur un déblocage réel (returning ... into v_inserted_id est NULL sur
-- un rappel idempotent d'un exploit déjà possédé) — évite un doublon dans feed_events.
create or replace function public.award_exploit(p_user_id uuid, p_exploit_code text)
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_rarity text;
    v_name text;
    v_inserted_id uuid;
begin
    select designed_rarity, name into v_rarity, v_name
    from public.exploit_definitions
    where code = p_exploit_code;

    if v_rarity is null then
        return;
    end if;

    insert into public.user_exploits (user_id, exploit_code, rarity_at_unlock)
    values (p_user_id, p_exploit_code, v_rarity)
    on conflict (user_id, exploit_code) do nothing
    returning id into v_inserted_id;

    -- PHASE 7
    if v_inserted_id is not null then
        insert into public.feed_events (user_id, event_type, source_id, content)
        values (
            p_user_id, 'EXPLOIT_UNLOCKED', v_inserted_id,
            jsonb_build_object('exploit_code', p_exploit_code, 'exploit_name', v_name, 'rarity', v_rarity)
        )
        on conflict (event_type, source_id) do nothing;
    end if;
end;
$$;

-- ---------------------------------------------------------------- evaluate_measurement_batch (étendu, Phase 7)
-- Remplace la fonction posée par 0002_goals.sql, étendue par 0003_money.sql puis
-- 0004_exploits.sql. Corps identique à la version 0004, seul l'ajout marqué "-- PHASE 7"
-- dans la branche "fin d'objectif" est nouveau : alimente le fil sur un objectif terminé
-- (PRODUCT_SPEC-2.md §10 : "objectif terminé" est l'un des deux seuls événements de fil).
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
    share_cents bigint;
    debt_row record;
    new_streak integer;
    debt_amount_after bigint;
    completed_staked_count integer;
begin
    select * into g from public.goals where id = new.goal_id;
    last_day := g.start_date + (g.duration_days - 1);

    if new.data_quality = 'UNAVAILABLE' then
        insert into public.daily_results (goal_id, local_date, status, measured_time_seconds, measured_opens, evaluated_at)
        values (new.goal_id, new.local_date, 'NO_DATA', new.measured_time_seconds, new.measured_opens, now())
        on conflict (goal_id, local_date) do update set
            status = 'NO_DATA', evaluated_at = now();
    else
        success := true;
        if g.constraint_type in ('TIME', 'BOTH') then
            success := success and new.measured_time_seconds <= g.time_limit_seconds;
        end if;
        if g.constraint_type in ('OPENS', 'BOTH') then
            success := success and new.measured_opens <= g.opens_limit;
        end if;

        insert into public.daily_results (
            goal_id, local_date, status, personal_success, daily_success,
            measured_time_seconds, measured_opens, evaluated_at
        )
        values (
            new.goal_id, new.local_date, case when success then 'SUCCESS' else 'FAILED' end,
            success, success, new.measured_time_seconds, new.measured_opens, now()
        )
        on conflict (goal_id, local_date) do update set
            status = excluded.status,
            personal_success = excluded.personal_success,
            daily_success = excluded.daily_success,
            measured_time_seconds = excluded.measured_time_seconds,
            measured_opens = excluded.measured_opens,
            evaluated_at = now();

        if g.kind = 'STAKED' then
            update public.daily_stake_shares
            set outcome = case when success then 'SECURED' else 'FORFEITED' end
            where goal_id = new.goal_id and local_date = new.local_date;

            if not success then
                select amount_cents into share_cents
                from public.daily_stake_shares
                where goal_id = new.goal_id and local_date = new.local_date;

                select * into debt_row from public.debts where goal_id = new.goal_id;
                if debt_row.status = 'PENDING' then
                    update public.debts
                    set amount_cents = amount_cents + share_cents
                    where id = debt_row.id;

                    insert into public.ledger_entries (
                        goal_id, user_id, debt_id, local_date, entry_type, amount_cents, simulated, idempotency_key
                    )
                    values (
                        new.goal_id, g.user_id, debt_row.id, new.local_date, 'DEBT_ACCRUED', share_cents, true,
                        new.goal_id::text || ':' || new.local_date::text || ':DEBT_ACCRUED'
                    )
                    on conflict (idempotency_key) do nothing;
                end if;
            end if;
        end if;

        update public.goals
        set current_streak_days = case when success then current_streak_days + 1 else 0 end,
            best_streak_days = greatest(best_streak_days, case when success then current_streak_days + 1 else best_streak_days end)
        where id = new.goal_id
        returning current_streak_days into new_streak;

        if success then
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

            if g.constraint_type in ('TIME', 'BOTH') and new.measured_time_seconds <= g.time_limit_seconds / 2 then
                perform public.award_exploit(g.user_id, 'TIME_HALF_LIMIT');
            end if;
            if g.constraint_type in ('OPENS', 'BOTH') and new.measured_opens <= g.opens_limit / 2 then
                perform public.award_exploit(g.user_id, 'OPENS_HALF_LIMIT');
            end if;
        end if;
    end if;

    if new.local_date = last_day and g.status = 'ACTIVE' then
        update public.goals set status = 'COMPLETED' where id = new.goal_id;

        if g.kind = 'STAKED' then
            update public.debts
            set status = 'SETTLED', settled_at = now()
            where goal_id = new.goal_id and status = 'PENDING' and amount_cents = 0;
        end if;

        -- PHASE 7 : "objectif terminé" alimente le fil, quel que soit le kind (FREE ou
        -- STAKED) — PRODUCT_SPEC-2.md §10 ne restreint pas aux objectifs payants.
        insert into public.feed_events (user_id, event_type, source_id, content)
        values (
            g.user_id, 'GOAL_COMPLETED', g.id,
            jsonb_build_object('kind', g.kind, 'constraint_type', g.constraint_type, 'duration_days', g.duration_days)
        )
        on conflict (event_type, source_id) do nothing;

        if g.kind = 'STAKED' then
            select amount_cents into debt_amount_after from public.debts where goal_id = new.goal_id;

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
        end if;
    end if;

    return new;
end;
$$;

-- ---------------------------------------------------------------- RLS
alter table public.follows enable row level security;

create policy follows_select_all
    on public.follows for select
    to anon, authenticated
    using (true);
-- Publique par construction (§10.1) : pas de restriction, y compris pour un utilisateur
-- bloqué (voir unfollow_on_block ci-dessus, qui supprime la ligne au moment du blocage).

create policy follows_insert_self
    on public.follows for insert
    to authenticated
    with check (
        follower_id = auth.uid()
        and not exists (
            select 1 from public.blocks b
            where (b.blocker_id = followee_id and b.blocked_id = auth.uid())
               or (b.blocker_id = auth.uid() and b.blocked_id = followee_id)
        )
    );

create policy follows_delete_self
    on public.follows for delete
    to authenticated
    using (follower_id = auth.uid());

alter table public.blocks enable row level security;

create policy blocks_select_self
    on public.blocks for select
    to authenticated
    using (blocker_id = auth.uid());

create policy blocks_insert_self
    on public.blocks for insert
    to authenticated
    with check (blocker_id = auth.uid());

create policy blocks_delete_self
    on public.blocks for delete
    to authenticated
    using (blocker_id = auth.uid());

alter table public.reports enable row level security;

create policy reports_insert_self
    on public.reports for insert
    to authenticated
    with check (reporter_id = auth.uid());

create policy reports_select_self
    on public.reports for select
    to authenticated
    using (reporter_id = auth.uid());
-- Pas d'UPDATE ni de DELETE côté client : la revue (status -> REVIEWED) est un processus
-- externe hors périmètre applicatif (voir commentaire sur la table plus haut).

alter table public.feed_events enable row level security;

create policy feed_events_select_visible
    on public.feed_events for select
    to authenticated
    using (public.can_view_feed_event(id));
-- Aucune policy d'écriture cliente : voir commentaire sur la table plus haut.

alter table public.comments enable row level security;

create policy comments_select_visible
    on public.comments for select
    to authenticated
    using (public.can_view_feed_event(feed_event_id));

create policy comments_insert_self
    on public.comments for insert
    to authenticated
    with check (author_id = auth.uid() and public.can_view_feed_event(feed_event_id));

create policy comments_delete_own_or_event_author
    on public.comments for delete
    to authenticated
    using (
        author_id = auth.uid()
        or exists (select 1 from public.feed_events fe where fe.id = feed_event_id and fe.user_id = auth.uid())
    );
-- Règle exacte d'ARCHITECTURE-2.md §8 : "suppression par l'auteur du commentaire ou par
-- l'auteur du contenu".

alter table public.reactions enable row level security;

create policy reactions_select_visible
    on public.reactions for select
    to authenticated
    using (public.can_view_feed_event(feed_event_id));

create policy reactions_insert_self
    on public.reactions for insert
    to authenticated
    with check (user_id = auth.uid() and public.can_view_feed_event(feed_event_id));

create policy reactions_delete_self
    on public.reactions for delete
    to authenticated
    using (user_id = auth.uid());

alter table public.notifications enable row level security;

-- Écriture limitée à read_at (accusé de lecture) — même technique que la colonne users
-- en 0001_init.sql : revoke complet puis grant colonne par colonne.
revoke all on public.notifications from authenticated;
grant select on public.notifications to authenticated;
grant update (read_at) on public.notifications to authenticated;

create policy notifications_select_self
    on public.notifications for select
    to authenticated
    using (user_id = auth.uid());

create policy notifications_update_self_read
    on public.notifications for update
    to authenticated
    using (user_id = auth.uid())
    with check (user_id = auth.uid());
-- Aucune policy d'INSERT cliente : peuplée uniquement par les triggers ci-dessus.

alter table public.rate_limit_hits enable row level security;
-- Aucune policy : table interne, inaccessible via l'API cliente (anon/authenticated).
-- Seule check_rate_limit() (SECURITY DEFINER) y écrit.
