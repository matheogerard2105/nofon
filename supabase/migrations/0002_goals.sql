-- Nofon — Phase 4 : objectifs.
--
-- Périmètre : objectifs gratuits ET payants (montant engagé, parts journalières
-- TODO-10, statut sécurisé/perdu par part) — SANS le registre financier réel ni
-- PaymentProvider simulé, qui restent Phase 5. Sans groupe (Phase 8 non atteinte),
-- §4.3 se simplifie : daily_success = personal_success toujours.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL
-- Editor, ou CLI `supabase db push`), comme 0001_init.sql en Phase 2.

-- ---------------------------------------------------------------- users (ajout)
alter table public.users add column if not exists best_streak_days integer not null default 0;

-- ---------------------------------------------------------------- goals
create table public.goals (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references public.users (id) on delete cascade,
    kind text not null check (kind in ('FREE', 'STAKED')),
    status text not null default 'ACTIVE' check (status in ('ACTIVE', 'COMPLETED')),
    duration_days integer not null check (duration_days between 3 and 365),
    start_date date not null,
    end_date date not null,
    constraint_type text not null check (constraint_type in ('TIME', 'OPENS', 'BOTH')),
    -- 15 min .. 5 h (PRODUCT_SPEC-2.md §2.2)
    time_limit_seconds integer check (time_limit_seconds between 900 and 18000),
    opens_limit integer check (opens_limit between 5 and 100),
    -- Figé au lancement (§3.5) : jamais recalculé à partir du fuseau courant de l'appareil.
    timezone text not null,
    -- 0 pour un objectif gratuit. Contrainte "1 €/jour minimum" (§5.1) vérifiée côté
    -- client contre app_config avant l'envoi ; pas revalidée ici pour rester simple —
    -- à durcir si un vrai registre financier est introduit en Phase 5.
    max_committed_cents bigint not null default 0,
    current_streak_days integer not null default 0,
    created_at timestamptz not null default now(),
    check (constraint_type != 'OPENS' and time_limit_seconds is not null or constraint_type = 'OPENS'),
    check (constraint_type != 'TIME' and opens_limit is not null or constraint_type = 'TIME'),
    check (end_date > start_date)
);

comment on column public.goals.timezone is
    'Figé au lancement (PRODUCT_SPEC-2.md §3.5). Toute décision serveur (le trigger '
    'evaluate_measurement_batch) utilise ce fuseau, jamais celui, courant, de l''appareil.';

-- ---------------------------------------------------------------- goal_excluded_apps
create table public.goal_excluded_apps (
    goal_id uuid not null references public.goals (id) on delete cascade,
    package_name text not null,
    primary key (goal_id, package_name)
);

-- ---------------------------------------------------------------- daily_stake_shares
create table public.daily_stake_shares (
    goal_id uuid not null references public.goals (id) on delete cascade,
    local_date date not null,
    amount_cents bigint not null,
    outcome text not null default 'PENDING' check (outcome in ('PENDING', 'SECURED', 'FORFEITED')),
    primary key (goal_id, local_date)
);

-- ---------------------------------------------------------------- measurement_batches
create table public.measurement_batches (
    id uuid primary key default gen_random_uuid(),
    goal_id uuid not null references public.goals (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    local_date date not null,
    measured_time_seconds integer not null,
    measured_opens integer not null,
    -- Reprend le vocabulaire Quality du domaine (:core:tracking). UNAVAILABLE ->
    -- NO_DATA côté daily_results, jamais un zéro silencieux (PRODUCT_SPEC-2.md §4.5).
    data_quality text not null check (data_quality in ('MEASURED', 'RECONSTRUCTED', 'ESTIMATED', 'UNAVAILABLE')),
    received_at timestamptz not null default now(),
    unique (goal_id, local_date)
);

-- ---------------------------------------------------------------- daily_results
-- Écrite UNIQUEMENT par le trigger evaluate_measurement_batch (SECURITY DEFINER) —
-- jamais par le client (ARCHITECTURE-2.md §3 : "le client n'écrit jamais dans
-- daily_results").
create table public.daily_results (
    goal_id uuid not null references public.goals (id) on delete cascade,
    local_date date not null,
    status text not null default 'PENDING' check (status in ('PENDING', 'PROVISIONAL', 'SUCCESS', 'FAILED', 'NO_DATA')),
    personal_success boolean,
    daily_success boolean,
    measured_time_seconds integer,
    measured_opens integer,
    evaluated_at timestamptz,
    primary key (goal_id, local_date)
);

-- ---------------------------------------------------------------- trigger : parts journalières
-- Implémente TODO-10 en base, jamais côté client : base = total / jours, le reste va
-- sur les PREMIERS jours. Invariant sum(daily_stake_shares) = max_committed_cents.
create function public.generate_stake_shares()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    base_cents bigint;
    remainder_cents bigint;
    i integer;
    d date;
    amt bigint;
begin
    if new.kind <> 'STAKED' or new.max_committed_cents <= 0 then
        return new;
    end if;

    base_cents := new.max_committed_cents / new.duration_days;
    remainder_cents := new.max_committed_cents % new.duration_days;
    d := new.start_date;

    for i in 0 .. new.duration_days - 1 loop
        amt := base_cents + (case when i < remainder_cents then 1 else 0 end);
        insert into public.daily_stake_shares (goal_id, local_date, amount_cents)
        values (new.id, d, amt);
        d := d + 1;
    end loop;

    return new;
end;
$$;

create trigger on_goal_insert_generate_shares
    after insert on public.goals
    for each row execute function public.generate_stake_shares();

-- ---------------------------------------------------------------- trigger : évaluation d'un lot
create function public.evaluate_measurement_batch()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    g record;
    success boolean;
begin
    select * into g from public.goals where id = new.goal_id;

    if new.data_quality = 'UNAVAILABLE' then
        insert into public.daily_results (goal_id, local_date, status, measured_time_seconds, measured_opens, evaluated_at)
        values (new.goal_id, new.local_date, 'NO_DATA', new.measured_time_seconds, new.measured_opens, now())
        on conflict (goal_id, local_date) do update set
            status = 'NO_DATA', evaluated_at = now();
        -- Aucune conséquence sur la série ni la part : TODO-06 reste ouvert.
        return new;
    end if;

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
    end if;

    update public.goals
    set current_streak_days = case when success then current_streak_days + 1 else 0 end
    where id = new.goal_id;

    if success then
        update public.users
        set best_streak_days = greatest(best_streak_days, (select current_streak_days from public.goals where id = new.goal_id))
        where id = g.user_id;
    end if;

    return new;
end;
$$;

create trigger on_measurement_batch_insert
    after insert on public.measurement_batches
    for each row execute function public.evaluate_measurement_batch();

-- ---------------------------------------------------------------- RLS
alter table public.goals enable row level security;
create policy goals_select_self on public.goals for select to authenticated using (user_id = auth.uid());
create policy goals_insert_self on public.goals for insert to authenticated with check (user_id = auth.uid());
-- Pas d'UPDATE ni de DELETE côté client : immutabilité de PRODUCT_SPEC-2.md §2.3
-- imposée en base, pas seulement dans l'UI.

alter table public.goal_excluded_apps enable row level security;
create policy goal_excluded_apps_select_self on public.goal_excluded_apps for select to authenticated
    using (exists (select 1 from public.goals g where g.id = goal_id and g.user_id = auth.uid()));
create policy goal_excluded_apps_insert_self on public.goal_excluded_apps for insert to authenticated
    with check (exists (select 1 from public.goals g where g.id = goal_id and g.user_id = auth.uid()));

alter table public.daily_stake_shares enable row level security;
create policy daily_stake_shares_select_self on public.daily_stake_shares for select to authenticated
    using (exists (select 1 from public.goals g where g.id = goal_id and g.user_id = auth.uid()));
-- Écriture uniquement par les triggers SECURITY DEFINER, jamais le client.

alter table public.measurement_batches enable row level security;
create policy measurement_batches_insert_self on public.measurement_batches for insert to authenticated
    with check (user_id = auth.uid());
create policy measurement_batches_select_self on public.measurement_batches for select to authenticated
    using (user_id = auth.uid());
-- Pas d'UPDATE ni de DELETE côté client (ARCHITECTURE-2.md §8).

alter table public.daily_results enable row level security;
create policy daily_results_select_self on public.daily_results for select to authenticated
    using (exists (select 1 from public.goals g where g.id = goal_id and g.user_id = auth.uid()));
-- Écriture uniquement par le trigger SECURITY DEFINER.
