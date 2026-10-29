-- Nofon — courbe de temps d'écran + déverrouillages sur le profil (feature ad-hoc, hors
-- des 9 phases de NOFON_AUDIT_INITIAL.md, toutes déjà closes).
--
-- TODO-18 amendé explicitement avec l'utilisateur avant cette migration
-- (PRODUCT_TODOS-2.md) : le temps d'écran quotidien exact devient public sur le profil
-- de n'importe qui (2026-09-03), puis le nombre de déverrouillages quotidien avec lui
-- (2026-09-04, extension non re-demandée explicitement — voir PRODUCT_TODOS-2.md pour le
-- raisonnement). Le reste de la liste « jamais publiques » (détail par app, tout ce qui
-- est financier, le statut quotidien par objectif — TODO-32) n'est pas concerné.
--
-- TODO-31 (PRODUCT_TODOS-2.md) : measurement_batches est scopé par objectif, pas par
-- utilisateur — un utilisateur peut avoir plusieurs objectifs actifs en parallèle
-- (TODO-24), chacun avec ses propres applications exception, donc plusieurs lignes pour
-- le même (user_id, local_date). Décision provisoire : priorité à l'objectif payant
-- actif s'il existe ce jour-là, sinon l'objectif gratuit soumis le plus récemment.
--
-- Migration jamais encore appliquée en prod à l'écriture de cette révision (2026-09-04)
-- : renommée user_daily_screen_time -> user_daily_measurements (porte aussi les
-- déverrouillages désormais) et measured_opens ajouté directement ici, plutôt qu'un
-- nouveau fichier — même précédent que le hot-patch de 0006_groups.sql.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor).

-- ---------------------------------------------------------------- user_daily_measurements
-- Pas de security_invoker, même idiome que public_profile_stats (0005_social.sql) : la
-- vue doit être lisible par n'importe qui, y compris pour un utilisateur qui n'a pas
-- accès en lecture directe à measurement_batches d'un tiers (RLS measurement_batches_
-- select_self, 0002_goals.sql, restreint à user_id = auth.uid()). data_quality exposé
-- pour que le client affiche un trou plutôt qu'un zéro trompeur sur UNAVAILABLE (même
-- principe que NO_DATA, TODO-06 — jamais un zéro silencieux).
create view public.user_daily_measurements as
select distinct on (mb.user_id, mb.local_date)
    mb.user_id,
    mb.local_date,
    mb.measured_time_seconds,
    mb.measured_opens,
    mb.data_quality
from public.measurement_batches mb
join public.goals g on g.id = mb.goal_id
join public.users u on u.id = mb.user_id and u.deleted_at is null
order by mb.user_id, mb.local_date, (g.kind = 'STAKED') desc, mb.received_at desc;
