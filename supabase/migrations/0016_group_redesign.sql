-- Nofon — refonte de l'onglet Groupe (demande explicite, 2026-09-08) : nom + logo de
-- groupe, dissolution par le créateur (même mécanique à délai de 24h que le départ
-- volontaire/l'exclusion, 0006_groups.sql), et exposition contrôlée de l'objectif
-- (règle + progression quotidienne) des membres aux autres membres du même groupe —
-- nécessaire pour afficher l'objectif abrégé de chacun et la courbe d'évolution, alors
-- que `goals`/`daily_results` restent strictement self-only en RLS (0002_goals.sql).
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor).

-- ---------------------------------------------------------------- groups (colonnes)
alter table public.groups add column if not exists name text not null default 'Groupe';
alter table public.groups add column if not exists avatar_url text;
-- Même mécanique que group_members.left_effective_at : demande horodatée + instant
-- réel d'effet (+24h, calculé côté client au moment de la demande — même idiome que
-- initiateLeave/LeaveMemberUpdate, jamais recalculé côté serveur à la lecture).
alter table public.groups add column if not exists dissolve_requested_at timestamptz;
alter table public.groups add column if not exists dissolve_effective_at timestamptz;

comment on column public.groups.name is 'Modifiable par le créateur uniquement. Défaut "Groupe" à la création (renommage immédiat possible).';
comment on column public.groups.dissolve_effective_at is
    'Une fois passé : finalize_dissolved_group() fait sortir tous les membres actifs '
    '(status -> LEFT), sans effet sur leurs objectifs individuels (jamais financier).';

revoke update on public.groups from authenticated;
grant update (name, avatar_url, dissolve_requested_at, dissolve_effective_at) on public.groups to authenticated;

create policy groups_update_creator
    on public.groups for update
    to authenticated
    using (creator_id = auth.uid())
    with check (creator_id = auth.uid());

-- ---------------------------------------------------------------- avatar de groupe (storage)
-- Même bucket "avatars" que les photos de profil (0009_avatars.sql), sous un préfixe
-- dédié group/{groupId}/avatar.jpg — écriture réservée au créateur du groupe concerné.
create policy avatars_insert_group_creator
    on storage.objects for insert
    to authenticated
    with check (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = 'groups'
        and exists (
            select 1 from public.groups g
            where g.id::text = (storage.foldername(name))[2] and g.creator_id = auth.uid()
        )
    );

create policy avatars_update_group_creator
    on storage.objects for update
    to authenticated
    using (
        bucket_id = 'avatars'
        and (storage.foldername(name))[1] = 'groups'
        and exists (
            select 1 from public.groups g
            where g.id::text = (storage.foldername(name))[2] and g.creator_id = auth.uid()
        )
    );

-- ---------------------------------------------------------------- finalize_dissolved_group
-- Même paresse que finalize_departed_members : jamais déclenchée par une horloge,
-- rappelée depuis resolve_group_day (donc à chaque journée personnelle qui se clôture
-- pour n'importe quel membre encore actif) et par le filet de sécurité TODO-22.
-- leave_initiated_by distinct ('CREATOR' déjà pris pour l'exclusion individuelle) :
-- 'NATURAL_END' réutilisé ici (fin non volontaire, sans délai perçu par le membre
-- au-delà de celui déjà écoulé) plutôt qu'une nouvelle valeur d'enum non demandée.
create function public.finalize_dissolved_group(p_group_id uuid)
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    update public.group_members
    set status = 'LEFT',
        leave_initiated_by = coalesce(leave_initiated_by, 'NATURAL_END'),
        left_effective_at = coalesce(
            left_effective_at,
            (select dissolve_effective_at from public.groups where id = p_group_id)
        )
    where group_id = p_group_id
      and status in ('ACTIVE', 'LEAVING')
      and exists (
          select 1 from public.groups g
          where g.id = p_group_id
            and g.dissolve_effective_at is not null
            and g.dissolve_effective_at <= now()
      );
end;
$$;

-- resolve_group_day (0006_groups.sql) inchangée à l'identique, sauf l'appel ajouté
-- juste après finalize_departed_members — CREATE OR REPLACE car 0006 est déjà déployée.
create or replace function public.resolve_group_day(p_group_id uuid, p_local_date date)
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
    perform pg_advisory_xact_lock(hashtext(p_group_id::text || ':' || p_local_date::text));

    if exists (select 1 from public.group_daily_results where group_id = p_group_id and local_date = p_local_date) then
        return;
    end if;

    perform public.finalize_departed_members(p_group_id);
    perform public.finalize_dissolved_group(p_group_id);

    select * into g from public.groups where id = p_group_id;
    select (value #>> '{}')::integer into deadline_hours
    from public.app_config where key = 'group_finalisation_deadline_hours';

    if now() < ((p_local_date + 1)::timestamp at time zone g.timezone) + (deadline_hours || ' hours')::interval then
        for member in select * from public.active_group_members_for(p_group_id, p_local_date) loop
            select * into dr from public.daily_results
            where goal_id = member.goal_id and local_date = p_local_date;
            if dr.local_date is null or dr.status = 'PENDING' then
                all_ready := false;
                exit;
            end if;
        end loop;
        if not all_ready then
            return;
        end if;
    end if;

    for member in select * from public.active_group_members_for(p_group_id, p_local_date) loop
        select * into dr from public.daily_results
        where goal_id = member.goal_id and local_date = p_local_date;

        if dr.local_date is null or dr.status = 'PENDING' then
            continue;
        end if;
        if dr.status = 'NO_DATA' then
            continue;
        end if;

        considered_count := considered_count + 1;
        if not dr.personal_success then
            group_success := false;
        end if;
    end loop;

    if considered_count = 0 then
        return;
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

-- ---------------------------------------------------------------- group_member_goal_summary
-- Règle de l'objectif de chaque membre (jamais sa progression), visible par les autres
-- membres ACTIFS du même groupe uniquement — jamais le montant engagé (toujours privé,
-- PRODUCT_SPEC-2.md §10.1). PAS de security_invoker (même idiome que
-- public_profile_stats, 0005_social.sql) : la vue doit pouvoir lire goals d'un tiers
-- alors que goals_select_self (0002_goals.sql) l'interdit en direct — is_active_group_member
-- (0006_groups.sql, SECURITY DEFINER) fait toute la vérification d'appartenance.
create view public.group_member_goal_summary as
select
    gm.group_id,
    gm.user_id,
    g.constraint_type,
    g.time_limit_seconds,
    g.opens_limit
from public.group_members gm
join public.goals g on g.id = gm.goal_id
where gm.status in ('ACTIVE', 'LEAVING')
  and public.is_active_group_member(auth.uid(), gm.group_id);

-- ---------------------------------------------------------------- group_member_daily_progress
-- Une ligne par membre et par jour DEPUIS LA CRÉATION DU GROUPE (jointure sur
-- group_members.created_at côté client, pas filtrée ici) — measured_time_seconds/
-- measured_opens permettent au client de calculer "% de l'objectif avant dépassement"
-- (demande explicite). LEFT JOIN daily_results : un jour sans mesure encore soumise
-- reste un trou (measured_* null), jamais un zéro trompeur.
create view public.group_member_daily_progress as
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
  and public.is_active_group_member(auth.uid(), gm.group_id);
