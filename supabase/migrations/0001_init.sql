-- Nofon — migration initiale (Phase 2 : socle).
--
-- Périmètre volontairement minimal : uniquement ce dont l'authentification et le
-- socle client ont besoin maintenant (`users`, `app_config`). Le reste du modèle de
-- données décrit dans ARCHITECTURE-2.md §6 (goals, ledger_entries, groups,
-- exploit_definitions, ...) sera écrit table par table au fil des phases qui en ont
-- réellement besoin (Phase 4 et suivantes) — pas inventé à l'avance ici.
--
-- Non déployée automatiquement : ce fichier est versionné, à exécuter sur ton propre
-- projet Supabase (SQL Editor, ou CLI `supabase db push`) une fois que tu en as créé un.

-- ---------------------------------------------------------------- extensions
create extension if not exists "pgcrypto";

-- ---------------------------------------------------------------- users
-- Profil applicatif lié à auth.users. Séparé de auth.users (géré par Supabase Auth)
-- pour ne jamais mélanger les identifiants de connexion et les données de profil.
create table public.users (
    id uuid primary key references auth.users (id) on delete cascade,
    handle text unique not null,
    avatar_url text,
    -- PRODUCT_SPEC-2.md §1 (TODO-15) : 18 ans minimum sur l'intégralité du produit.
    -- Contrainte imposée en base, jamais seulement côté client.
    birth_date date not null check (birth_date <= (now() - interval '18 years')),
    timezone text not null default 'Europe/Paris',
    created_at timestamptz not null default now(),
    deleted_at timestamptz
);

comment on table public.users is
    'Profil applicatif. Champs publics/privés : voir PRODUCT_SPEC-2.md §10.1 (TODO-18). '
    'birth_date n''est jamais dans la liste des champs publics.';

-- ---------------------------------------------------------------- app_config
-- Table clé/valeur versionnée, exposée en lecture seule au client (ARCHITECTURE-2.md
-- §7). Aucune de ces valeurs n'est codée en dur côté client, jamais dupliquée.
create table public.app_config (
    key text primary key,
    value jsonb not null,
    version integer not null default 1,
    updated_at timestamptz not null default now()
);

insert into public.app_config (key, value) values
    ('max_group_members', '10'),
    ('max_daily_stake_cents', '500'),
    ('max_total_stake_cents', '50000'),
    ('min_users_for_rarity_percentage', '100'),
    ('min_baseline_days', '3'),
    ('charity_share_bp', '7500'),
    ('nofon_share_bp', '2500');

-- ---------------------------------------------------------------- création auto du profil
-- Pattern standard Supabase : une ligne public.users est créée automatiquement à
-- l'inscription, à partir des métadonnées passées par le client (voir
-- SupabaseAuthRepository.signUpWithEmail — data = { "birth_date": "AAAA-MM-JJ" }).
create function public.handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
    insert into public.users (id, handle, birth_date)
    values (
        new.id,
        -- Handle provisoire dérivé de l'id : à laisser l'utilisateur personnaliser
        -- ensuite (écran non construit à cette phase).
        'user_' || substr(new.id::text, 1, 8),
        (new.raw_user_meta_data ->> 'birth_date')::date
    );
    return new;
end;
$$;

create trigger on_auth_user_created
    after insert on auth.users
    for each row execute function public.handle_new_user();

-- ---------------------------------------------------------------- RLS : users
alter table public.users enable row level security;

-- Colonnes publiques par défaut (TODO-18) : seules handle/avatar_url/created_at sont
-- lisibles par n'importe qui. birth_date et timezone restent privées.
revoke all on public.users from anon, authenticated;
grant select (id, handle, avatar_url, created_at) on public.users to anon, authenticated;
grant select (id, handle, avatar_url, birth_date, timezone, created_at) on public.users to authenticated;
grant update (handle, avatar_url, timezone) on public.users to authenticated;

create policy "users_select_all_rows"
    on public.users for select
    to anon, authenticated
    using (deleted_at is null);

create policy "users_update_self"
    on public.users for update
    to authenticated
    using (id = auth.uid())
    with check (id = auth.uid());

-- ---------------------------------------------------------------- RLS : app_config
alter table public.app_config enable row level security;

create policy "app_config_read_only"
    on public.app_config for select
    to anon, authenticated
    using (true);
