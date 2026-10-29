-- Nofon — deux fuites d'un compte supprimé (2026-09-04), constatées en conditions
-- réelles juste après avoir testé la suppression de compte (TODO-29).
--
-- TODO-35 (PRODUCT_TODOS-2.md) :
-- 1. Les exploits (et par extension commentaires/réactions) d'un compte supprimé
--    restaient visibles dans le fil des abonnés — can_view_feed_event() (0005_social.sql)
--    ne vérifiait jamais deleted_at.
-- 2. Rien n'empêchait de se reconnecter à un compte déjà marqué supprimé pendant les
--    30 jours qui précèdent l'effacement dur (auth.users reste valide jusque-là,
--    TODO-29) — géré côté client (voir feature/auth), pas ici, mais s'appuie sur
--    user_is_active() (0012_fix_user_exploits_rls.sql).
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor),
-- après 0012_fix_user_exploits_rls.sql (dépend de user_is_active()).

create or replace function public.can_view_feed_event(p_feed_event_id uuid)
returns boolean
language sql
security definer
set search_path = public
stable
as $$
    select exists (
        select 1 from public.feed_events fe
        where fe.id = p_feed_event_id
          and public.user_is_active(fe.user_id)
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
