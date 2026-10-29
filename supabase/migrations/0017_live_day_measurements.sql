-- Nofon — données du jour visibles par les autres même si l'utilisateur n'ouvre pas l'app.
--
-- Constat : measurement_batches ne reçoit qu'une journée CLOSE (TrackingSyncWorker,
-- catchUpGoal s'arrête à hier) — c'est voulu, le lot est immuable et déclenche toute
-- l'évaluation financière (evaluate_measurement_batch). Conséquence : la journée en
-- cours n'existait jamais côté serveur, ni sur le graphique d'évolution du groupe
-- (group_member_daily_progress), ni sur la courbe du profil (user_daily_measurements).
--
-- Solution : une table séparée, mutable, purement d'AFFICHAGE, que le worker de fond
-- met à jour à chaque passage (toutes les 15 min au mieux, selon Doze/le fabricant) pour
-- chaque objectif actif. Aucun trigger, aucune conséquence financière : le lot définitif
-- de measurement_batches reste la seule source de vérité, et masque la ligne live dès
-- qu'il existe pour le même jour.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor).

-- ---------------------------------------------------------------- live_day_measurements
create table public.live_day_measurements (
    goal_id uuid not null references public.goals (id) on delete cascade,
    user_id uuid not null references public.users (id) on delete cascade,
    local_date date not null,
    measured_time_seconds integer not null check (measured_time_seconds >= 0),
    measured_opens integer not null check (measured_opens >= 0),
    data_quality text not null check (data_quality in ('MEASURED', 'RECONSTRUCTED', 'ESTIMATED', 'UNAVAILABLE')),
    updated_at timestamptz not null default now(),
    primary key (goal_id, local_date)
);

-- Écriture client limitée à ses propres objectifs actifs, et au jour courant (ou la
-- veille, pour une dernière mise à jour juste après minuit) dans le fuseau FIGÉ de
-- l'objectif — jamais une réécriture arbitraire de l'historique.
create function public.can_write_live_day(p_goal_id uuid, p_user_id uuid, p_local_date date)
returns boolean
language sql
stable
security definer
set search_path = public
as $$
    select p_user_id = auth.uid() and exists (
        select 1 from public.goals g
        where g.id = p_goal_id
          and g.user_id = auth.uid()
          and g.status = 'ACTIVE'
          and p_local_date between (now() at time zone g.timezone)::date - 1
                               and (now() at time zone g.timezone)::date
    );
$$;

alter table public.live_day_measurements enable row level security;
create policy live_day_measurements_select_self on public.live_day_measurements for select to authenticated
    using (user_id = auth.uid());
create policy live_day_measurements_insert_self on public.live_day_measurements for insert to authenticated
    with check (public.can_write_live_day(goal_id, user_id, local_date));
create policy live_day_measurements_update_self on public.live_day_measurements for update to authenticated
    using (user_id = auth.uid())
    with check (public.can_write_live_day(goal_id, user_id, local_date));

-- ---------------------------------------------------------------- user_daily_measurements
-- Même contrat que 0008_profile_screen_time.sql (mêmes colonnes, même priorité TODO-31 :
-- objectif payant d'abord), avec la ligne live en repli pour un jour sans lot définitif.
create or replace view public.user_daily_measurements as
select distinct on (s.user_id, s.local_date)
    s.user_id,
    s.local_date,
    s.measured_time_seconds,
    s.measured_opens,
    s.data_quality
from (
    select mb.user_id, mb.local_date, mb.measured_time_seconds, mb.measured_opens, mb.data_quality,
           g.kind, 0 as source_rank, mb.received_at as at
    from public.measurement_batches mb
    join public.goals g on g.id = mb.goal_id
    union all
    select l.user_id, l.local_date, l.measured_time_seconds, l.measured_opens, l.data_quality,
           g.kind, 1 as source_rank, l.updated_at as at
    from public.live_day_measurements l
    join public.goals g on g.id = l.goal_id
    where not exists (
        select 1 from public.measurement_batches mb
        where mb.goal_id = l.goal_id and mb.local_date = l.local_date
    )
) s
join public.users u on u.id = s.user_id and u.deleted_at is null
order by s.user_id, s.local_date, (s.kind = 'STAKED') desc, s.source_rank, s.at desc;

-- ---------------------------------------------------------------- group_member_daily_progress
-- Même contrat que 0016_group_redesign.sql, plus une ligne status = 'LIVE' par membre
-- pour un jour sans daily_results (typiquement aujourd'hui). Le client la traite comme
-- un point normal (filtre existant : seuls PENDING et NO_DATA sont écartés/troués).
create or replace view public.group_member_daily_progress as
select
    gm.group_id,
    gm.user_id,
    dr.local_date,
    dr.status,
    dr.measured_time_seconds,
    dr.measured_opens,
    g.constraint_type,
    g.time_limit_seconds,
    g.opens_limit
from public.group_members gm
join public.goals g on g.id = gm.goal_id
left join public.daily_results dr on dr.goal_id = gm.goal_id
where gm.status in ('ACTIVE', 'LEAVING')
  and public.is_active_group_member(auth.uid(), gm.group_id)
union all
select
    gm.group_id,
    gm.user_id,
    l.local_date,
    'LIVE'::text,
    l.measured_time_seconds,
    l.measured_opens,
    g.constraint_type,
    g.time_limit_seconds,
    g.opens_limit
from public.group_members gm
join public.goals g on g.id = gm.goal_id
join public.live_day_measurements l on l.goal_id = gm.goal_id
where gm.status in ('ACTIVE', 'LEAVING')
  and public.is_active_group_member(auth.uid(), gm.group_id)
  and l.data_quality != 'UNAVAILABLE'
  and not exists (
      select 1 from public.daily_results dr
      where dr.goal_id = l.goal_id and dr.local_date = l.local_date
  );
