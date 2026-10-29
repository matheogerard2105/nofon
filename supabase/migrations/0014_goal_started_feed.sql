-- Nofon — le fil affiche aussi le lancement d'un nouvel objectif, en plus de sa
-- réussite (déjà alimentée par evaluate_measurement_batch en tant que GOAL_COMPLETED,
-- 0005_social.sql §feed_events). Demande explicite de l'utilisateur (2026-09-05).
--
-- Mêmes champs rendus publics que GOAL_COMPLETED, par symétrie : kind/constraint_type/
-- duration_days. JAMAIS max_committed_cents (montant engagé) ni time_limit_seconds/
-- opens_limit — c'est de l'information financière/de contrainte détaillée, qui reste
-- privée (PRODUCT_SPEC-2.md §10.1 : "montant de caution" jamais public).
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor),
-- comme toutes les migrations précédentes de ce dossier.

alter table public.feed_events drop constraint feed_events_event_type_check;
alter table public.feed_events add constraint feed_events_event_type_check
    check (event_type in ('GOAL_COMPLETED', 'EXPLOIT_UNLOCKED', 'GOAL_STARTED'));

-- SECURITY DEFINER + `on conflict do nothing` : même idiome que le reste de
-- feed_events (award_exploit, 0005_social.sql) — idempotent, jamais de doublon pour un
-- même objectif même si le trigger était rejoué.
create function public.award_goal_started_feed_event()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.feed_events (user_id, event_type, source_id, content)
    values (
        new.user_id, 'GOAL_STARTED', new.id,
        jsonb_build_object('kind', new.kind, 'constraint_type', new.constraint_type, 'duration_days', new.duration_days)
    )
    on conflict (event_type, source_id) do nothing;
    return new;
end;
$$;

-- Coexiste avec on_goal_insert_generate_shares (0002_goals.sql) — deux triggers AFTER
-- INSERT indépendants sur goals, chacun sa responsabilité propre.
create trigger on_goal_insert_feed_event
    after insert on public.goals
    for each row execute function public.award_goal_started_feed_event();
