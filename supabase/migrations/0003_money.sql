-- Nofon — Phase 5 : argent (simulé).
--
-- Registre financier (ledger_entries), dette accumulée (debts), déclenchement d'un
-- règlement unique en fin d'objectif (PaymentProvider simulé côté client — cf.
-- PRODUCT_SPEC-2.md §5.3). Aucun mouvement d'argent réel : `simulated = true` partout.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL
-- Editor), après 0001_init.sql et 0002_goals.sql.

-- ---------------------------------------------------------------- goals (ajouts)
-- Fige §5.4 à la création (jamais recalculé rétroactivement si app_config change).
alter table public.goals add column if not exists config_snapshot jsonb;
-- "Meilleure série" DE CET OBJECTIF (§13) — distinct de users.best_streak_days, qui
-- est global au compte et ne redescend jamais.
alter table public.goals add column if not exists best_streak_days integer not null default 0;

-- ---------------------------------------------------------------- debts
-- Une ligne par objectif payant, créée à zéro à son lancement (modèle "engagement +
-- dette accumulée", PRODUCT_SPEC-2.md §5.3 — aucun encaissement au démarrage).
-- Écriture réservée aux triggers SECURITY DEFINER, jamais au client.
create table public.debts (
    id uuid primary key default gen_random_uuid(),
    goal_id uuid not null unique references public.goals (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    amount_cents bigint not null default 0 check (amount_cents >= 0),
    -- TODO-13 : seul l'état de donnée existe, aucune politique de recouvrement.
    status text not null default 'PENDING' check (status in ('PENDING', 'SETTLED', 'UNPAID_DEBT')),
    settled_at timestamptz,
    created_at timestamptz not null default now()
);

create index debts_user_id_idx on public.debts (user_id);

-- ---------------------------------------------------------------- ledger_entries
-- Append-only (ARCHITECTURE-2.md §6). DEBT_ACCRUED : une entrée par journée FAILED.
-- SETTLEMENT : une entrée au règlement, porte la répartition association/Nofon
-- effectivement appliquée (§5.4), jamais recalculée ailleurs.
create table public.ledger_entries (
    id uuid primary key default gen_random_uuid(),
    goal_id uuid not null references public.goals (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    debt_id uuid references public.debts (id) on delete cascade,
    -- null pour une entrée SETTLEMENT : pas rattachée à une journée précise.
    local_date date,
    entry_type text not null check (entry_type in ('DEBT_ACCRUED', 'SETTLEMENT')),
    amount_cents bigint not null check (amount_cents >= 0),
    -- Rempli uniquement sur une entrée SETTLEMENT.
    charity_cents bigint,
    nofon_cents bigint,
    -- ARCHITECTURE-2.md §5.3 : "toute écriture est marquée simulated = true" en MVP.
    simulated boolean not null default true,
    -- ARCHITECTURE-2.md §8 : "idempotence obligatoire sur toute opération financière".
    idempotency_key text not null unique,
    created_at timestamptz not null default now()
);

create index ledger_entries_goal_id_idx on public.ledger_entries (goal_id);

-- ---------------------------------------------------------------- debt_settlement_requests
-- Seule table financière où le client a le droit d'INSERT (comme measurement_batches
-- déclenche evaluate_measurement_batch). Une ligne = une demande de règlement, jamais
-- rejouable deux fois pour la même dette (unique (debt_id)).
create table public.debt_settlement_requests (
    id uuid primary key default gen_random_uuid(),
    debt_id uuid not null references public.debts (id) on delete cascade,
    goal_id uuid not null references public.goals (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    requested_at timestamptz not null default now(),
    unique (debt_id)
);

-- ---------------------------------------------------------------- trigger : config_snapshot
create function public.set_goal_config_snapshot()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    new.config_snapshot := jsonb_build_object(
        'charity_share_bp', (select (value #>> '{}')::bigint from public.app_config where key = 'charity_share_bp'),
        'nofon_share_bp', (select (value #>> '{}')::bigint from public.app_config where key = 'nofon_share_bp')
    );
    return new;
end;
$$;

create trigger on_goal_insert_set_config_snapshot
    before insert on public.goals
    for each row execute function public.set_goal_config_snapshot();

-- ---------------------------------------------------------------- trigger : création de la dette
create function public.create_goal_debt()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    if new.kind = 'STAKED' then
        insert into public.debts (goal_id, user_id, amount_cents, status)
        values (new.id, new.user_id, 0, 'PENDING');
    end if;
    return new;
end;
$$;

create trigger on_goal_insert_create_debt
    after insert on public.goals
    for each row execute function public.create_goal_debt();

-- ---------------------------------------------------------------- trigger : évaluation (étendu)
-- Remplace la fonction posée par 0002_goals.sql — le trigger on_measurement_batch_insert
-- existant continue de fonctionner sans être recréé. Ajouts : accumulation de dette sur
-- une journée FAILED, et détection de fin d'objectif (dernière journée couverte par
-- daily_stake_shares, quel que soit son statut terminal — jamais déclenché par une
-- horloge, ARCHITECTURE-2.md §4).
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
begin
    select * into g from public.goals where id = new.goal_id;
    last_day := g.start_date + (g.duration_days - 1);

    if new.data_quality = 'UNAVAILABLE' then
        insert into public.daily_results (goal_id, local_date, status, measured_time_seconds, measured_opens, evaluated_at)
        values (new.goal_id, new.local_date, 'NO_DATA', new.measured_time_seconds, new.measured_opens, now())
        on conflict (goal_id, local_date) do update set
            status = 'NO_DATA', evaluated_at = now();
        -- Aucune conséquence sur la série ni la dette : TODO-06 reste ouvert.
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
        where id = new.goal_id;

        if success then
            update public.users
            set best_streak_days = greatest(best_streak_days, (select current_streak_days from public.goals where id = new.goal_id))
            where id = g.user_id;
        end if;
    end if;

    -- Fin d'objectif (PRODUCT_SPEC-2.md §13) : dernière journée évaluée, quel que soit
    -- son statut terminal — jamais déclenché par une horloge.
    if new.local_date = last_day and g.status = 'ACTIVE' then
        update public.goals set status = 'COMPLETED' where id = new.goal_id;

        -- Rien à prélever : réglée immédiatement, sans round-trip client.
        if g.kind = 'STAKED' then
            update public.debts
            set status = 'SETTLED', settled_at = now()
            where goal_id = new.goal_id and status = 'PENDING' and amount_cents = 0;
        end if;
    end if;

    return new;
end;
$$;

-- ---------------------------------------------------------------- trigger : règlement simulé
create function public.settle_debt()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
declare
    d record;
    g record;
    charity_bp bigint;
    nofon_bp bigint;
    charity bigint;
    nofon bigint;
begin
    select * into d from public.debts where id = new.debt_id;
    select * into g from public.goals where id = d.goal_id;

    if g.status <> 'COMPLETED' then
        raise exception 'Règlement demandé avant la fin de l''objectif (goal_id=%).', d.goal_id;
    end if;

    if d.status = 'SETTLED' then
        return new;
    end if;

    if d.amount_cents > 0 then
        charity_bp := coalesce(
            (g.config_snapshot ->> 'charity_share_bp')::bigint,
            (select (value #>> '{}')::bigint from public.app_config where key = 'charity_share_bp')
        );
        nofon_bp := coalesce(
            (g.config_snapshot ->> 'nofon_share_bp')::bigint,
            (select (value #>> '{}')::bigint from public.app_config where key = 'nofon_share_bp')
        );
        charity := d.amount_cents * charity_bp / 10000;
        -- nofon = reste, jamais recalculé séparément : garantit charity + nofon =
        -- amount_cents à la centime près (BIGINT, jamais de float).
        nofon := d.amount_cents - charity;

        insert into public.ledger_entries (
            goal_id, user_id, debt_id, entry_type, amount_cents, charity_cents, nofon_cents, simulated, idempotency_key
        )
        values (
            d.goal_id, d.user_id, d.id, 'SETTLEMENT', d.amount_cents, charity, nofon, true,
            d.goal_id::text || ':SETTLEMENT'
        )
        on conflict (idempotency_key) do nothing;
    end if;

    -- MVP : SimulatedPaymentProvider réussit toujours — jamais d'UNPAID_DEBT produit
    -- ici. L'état existe dans le modèle pour l'implémentation réelle future (TODO-13).
    update public.debts set status = 'SETTLED', settled_at = now() where id = d.id;

    return new;
end;
$$;

create trigger on_debt_settlement_request_insert
    after insert on public.debt_settlement_requests
    for each row execute function public.settle_debt();

-- ---------------------------------------------------------------- RLS
alter table public.debts enable row level security;
create policy debts_select_self on public.debts for select to authenticated
    using (user_id = auth.uid());
-- Écriture réservée aux triggers SECURITY DEFINER — aucune policy d'écriture cliente.

alter table public.ledger_entries enable row level security;
create policy ledger_entries_select_self on public.ledger_entries for select to authenticated
    using (user_id = auth.uid());
-- Append-only : aucune policy d'écriture côté client.

alter table public.debt_settlement_requests enable row level security;
create policy debt_settlement_requests_insert_self on public.debt_settlement_requests for insert to authenticated
    with check (
        user_id = auth.uid()
        and exists (
            select 1 from public.debts d
            where d.id = debt_id and d.user_id = auth.uid() and d.goal_id = goal_id
        )
    );
create policy debt_settlement_requests_select_self on public.debt_settlement_requests for select to authenticated
    using (user_id = auth.uid());
-- Pas d'UPDATE ni de DELETE : une demande de règlement, une fois posée, est figée.
