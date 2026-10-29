-- Nofon — Phase 9 : durcissement.
--
-- Trois sujets, ajoutés incrémentalement dans ce même fichier au fil de la Phase 9
-- (comme 0006_groups.sql pour la Phase 8) :
--
-- 1. TODO-28 (PRODUCT_TODOS-2.md) note deux trous non couverts par la décision prise en
--    Phase 7 (0005_social.sql) : la recherche (lecture seule, pas de point d'ancrage
--    BEFORE INSERT) et les demandes de groupe (Phase 8 n'existait pas encore). Cette
--    section comble le second — le premier reste documenté ci-dessous comme
--    volontairement hors de portée d'un trigger générique.
--
-- 2. TODO-29 : suppression de compte. Décision tranchée avec l'utilisateur avant cette
--    migration (voir PRODUCT_TODOS-2.md) : un créateur de groupe actif ne peut pas
--    supprimer son compte tant que le groupe n'est pas quitté/dissous (évite qu'un
--    `on delete cascade` sur `groups.creator_id` n'efface silencieusement les données
--    d'autres membres) ; suppression douce immédiate (`users.deleted_at`) puis
--    effacement dur différé de 30 jours via une tâche `pg_cron`.
--
-- 3. TODO-30 : Play Integrity, amorce. Décision provisoire (à confirmer ou corriger,
--    même idiome que TODO-28) : capture uniquement. Le client attache un jeton
--    d'attestation (API Classic, `IntegrityAttestationRepository`) à chaque lot de
--    mesure soumis ; le serveur le stocke tel quel, sans le décoder ni bloquer la
--    soumission dessus. Le décoder réellement exige un appel à l'API Google
--    (`playintegrity.googleapis.com`, compte de service) depuis une Edge Function —
--    infrastructure hors de Postgres, non construite ici, à réévaluer une fois l'argent
--    réel activé (même logique de report que TODO-13/14).
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL
-- Editor), après 0001 à 0006. La section 2 active l'extension `pg_cron` — si la
-- création échoue faute de droits, l'activer d'abord via Database > Extensions dans le
-- dashboard Supabase, puis rejouer cette migration.

-- ---------------------------------------------------------------- app_config (ajout)
insert into public.app_config (key, value) values
    ('rate_limit_group_join_per_hour', '20');

-- ---------------------------------------------------------------- rate limiting des demandes de groupe
-- Même idiome que on_follow_insert_rate_limit/on_comment_insert_rate_limit/
-- on_report_insert_rate_limit (0005_social.sql) : enforce_rate_limit() générique,
-- déclenché en tête d'un BEFORE INSERT. Restreint aux lignes PENDING via WHEN, pour ne
-- PAS s'appliquer à l'insertion ACTIVE du créateur faite par insert_creator_membership()
-- (0006_groups.sql, SECURITY DEFINER, hors du chemin client) — seule une vraie demande
-- d'adhésion (insérée par le client via group_members_insert_self) doit être comptée.
create trigger on_group_join_insert_rate_limit
    before insert on public.group_members
    for each row
    when (new.status = 'PENDING')
    execute function public.enforce_rate_limit('group_join', 'rate_limit_group_join_per_hour');

-- ---------------------------------------------------------------- recherche : non couverte, volontairement
-- La recherche de profils (feature/social, ProfileRepository/SocialGraphRepository) est
-- un SELECT côté client sans écriture correspondante — aucun trigger BEFORE INSERT/
-- UPDATE ne peut s'y accrocher, à la différence de follow/comment/report/group_join qui
-- sont chacun le corollaire d'une ligne insérée. Couvrir la recherche demanderait un
-- mécanisme différent (ex. RPC dédiée appelant check_rate_limit() explicitement au lieu
-- d'un SELECT direct sur la table) — décision d'infrastructure non demandée par TODO-28,
-- qui se limite à consommer le mécanisme existant. Laissé ouvert, comme TODO-28 le
-- prévoit déjà ("réévaluer si un abus est constaté, avant ouverture publique").

-- ================================================================== TODO-29 : suppression de compte

-- ---------------------------------------------------------------- app_config (ajout)
insert into public.app_config (key, value) values
    ('account_deletion_grace_period_days', '30');

-- ---------------------------------------------------------------- request_account_deletion
-- Point d'entrée client unique (RPC), plutôt qu'un UPDATE direct sur users.deleted_at :
-- la garde anti-perte-de-données ci-dessous doit être imposée en base, jamais seulement
-- côté client (même principe que create_group()/award_exploit — une règle serveur ne se
-- contourne pas avec un client mal écrit ou une requête manuelle).
-- TODO-29 : un créateur de groupe encore actif (end_date pas encore passée) et comptant
-- au moins un AUTRE membre PENDING/ACTIVE/LEAVING ne peut pas supprimer son compte —
-- `groups.creator_id` est en cascade et effacerait sinon le groupe entier, donc les
-- données d'autrui, sans qu'ils en soient jamais informés. Un groupe sans autre membre,
-- ou déjà arrivé à échéance, ne bloque rien : personne d'autre n'a de donnée en jeu.
create function public.request_account_deletion()
returns void
language plpgsql
security definer
set search_path = public
as $$
begin
    if exists (
        select 1 from public.groups g
        where g.creator_id = auth.uid()
          and g.end_date >= current_date
          and exists (
              select 1 from public.group_members gm
              where gm.group_id = g.id
                and gm.user_id != auth.uid()
                and gm.status in ('PENDING', 'ACTIVE', 'LEAVING')
          )
    ) then
        raise exception 'account_deletion_blocked_active_group_creator';
    end if;

    -- Idempotent : une deuxième demande pendant le délai de grâce ne fait rien (pas de
    -- ré-armement du délai, pas d'erreur non plus — inutile de distinguer ce cas côté
    -- client, aucune fonctionnalité d'annulation n'est construite dans cette itération).
    update public.users set deleted_at = now() where id = auth.uid() and deleted_at is null;
end;
$$;

-- ---------------------------------------------------------------- erase_expired_deleted_accounts
-- Effacement dur, planifié — jamais déclenché directement par le client. Supprimer la
-- ligne auth.users suffit : c'est la racine de la chaîne on delete cascade construite
-- depuis 0001_init.sql (public.users -> goals/stakes/exploits/groups/follows/... en
-- cascade). Suppression SQL directe plutôt que l'API Admin GoTrue (plus canonique mais
-- demanderait pg_net + une clé service_role stockée en base, une surface de risque
-- distincte non ouverte ici) — voir la vérification externe suggérée dans TODO-29
-- (PRODUCT_TODOS-2.md) si des métadonnées d'authentification s'avèrent orphelines.
create extension if not exists pg_cron;

create function public.erase_expired_deleted_accounts()
returns void
language plpgsql
security definer
set search_path = public
as $$
declare
    v_grace_days integer;
begin
    select (value #>> '{}')::integer into v_grace_days
    from public.app_config where key = 'account_deletion_grace_period_days';

    delete from auth.users
    where id in (
        select id from public.users
        where deleted_at is not null
          and deleted_at <= now() - (v_grace_days || ' days')::interval
    );
end;
$$;

select cron.schedule(
    'nofon-erase-expired-deleted-accounts',
    '0 3 * * *',
    $$select public.erase_expired_deleted_accounts();$$
);

-- ================================================================== TODO-30 : Play Integrity (amorce)

-- ---------------------------------------------------------------- measurement_batches (ajout)
-- Nullable, aucune contrainte : un échec côté client (émulateur, Play Services absent,
-- build non distribué par Play) ne doit jamais empêcher la soumission de la mesure —
-- voir TrackingSyncWorker.requestIntegrityToken(). Aucune policy RLS supplémentaire
-- nécessaire : measurement_batches_insert_self (0002_goals.sql) s'applique déjà à toute
-- la ligne, pas de restriction par colonne comme sur users/notifications.
alter table public.measurement_batches
    add column integrity_token text;

comment on column public.measurement_batches.integrity_token is
    'Jeton d''attestation Play Integrity (API Classic), stocké tel quel. Non décodé, '
    'non vérifié, non exploité pour l''instant — TODO-30 (PRODUCT_TODOS-2.md).';

-- ================================================================== Correctif TODO-29 : compteurs abonnés/abonnements
-- Constaté en testant TODO-29 en conditions réelles (2026-09-03, même session) : un
-- compte supprimé (deleted_at posé) disparaît déjà de public_profile_stats lui-même
-- (where u.deleted_at is null) et des listes abonnés/abonnements (filtrées par la
-- policy RLS deleted_at is null sur users, 0001_init.sql) — mais followers_count/
-- following_count ci-dessous comptaient encore les lignes follows brutes SANS vérifier
-- si l'autre partie de la relation était elle-même supprimée. Résultat : le total
-- affiché ne correspond plus au nombre de personnes réellement listées, dès qu'un
-- compte suivi/suiveur passe par TODO-29. Même patron que le hot-fix RLS de
-- 0006_groups.sql : corrigé en place ici plutôt que dans un nouveau fichier, la Phase 9
-- n'ayant pas encore été déclarée close au moment de la découverte.
create or replace view public.public_profile_stats as
select
    u.id as user_id,
    u.handle,
    u.avatar_url,
    u.best_streak_days,
    (select max(g.current_streak_days) from public.goals g where g.user_id = u.id and g.status = 'ACTIVE')
        as current_streak_days,
    (
        select count(*) from public.follows f
        join public.users follower on follower.id = f.follower_id
        where f.followee_id = u.id and follower.deleted_at is null
    ) as followers_count,
    (
        select count(*) from public.follows f
        join public.users followee on followee.id = f.followee_id
        where f.follower_id = u.id and followee.deleted_at is null
    ) as following_count,
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
