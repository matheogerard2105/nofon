-- Nofon — enrichit le contenu de GOAL_STARTED (0014_goal_started_feed.sql) avec les
-- règles de l'objectif (time_limit_seconds/opens_limit), demande explicite (2026-09-05) :
-- "tous les détails de l'objectif" dans le post du fil.
--
-- CREATE OR REPLACE plutôt qu'un nouveau trigger : 0014 a déjà été exécutée en prod,
-- on ne réécrit jamais un fichier déjà déployé (même précédent que le hot-patch de
-- 0006_groups.sql / le renommage de 0008_profile_screen_time.sql). Les GOAL_STARTED déjà
-- créés avec l'ancien contenu (kind/constraint_type/duration_days uniquement) restent
-- tels quels — instantané figé au moment de l'événement, jamais recalculé.
--
-- max_committed_cents (montant engagé) reste volontairement absent de content :
-- information financière, jamais publique (PRODUCT_SPEC-2.md §10.1), même ici.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor).

create or replace function public.award_goal_started_feed_event()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.feed_events (user_id, event_type, source_id, content)
    values (
        new.user_id, 'GOAL_STARTED', new.id,
        jsonb_build_object(
            'kind', new.kind,
            'constraint_type', new.constraint_type,
            'duration_days', new.duration_days,
            'time_limit_seconds', new.time_limit_seconds,
            'opens_limit', new.opens_limit
        )
    )
    on conflict (event_type, source_id) do nothing;
    return new;
end;
$$;
