-- Nofon — stockage des photos de profil (feature ad-hoc, refonte du profil du
-- 2026-09-04). Premier usage de Supabase Storage dans ce projet — tout le reste des
-- migrations ne touche que Postgres/Auth.
--
-- Convention de chemin : "{user_id}/avatar.jpg" — un seul fichier par utilisateur,
-- toujours réécrit en upsert côté client, jamais une liste qui grossirait à chaque
-- changement de photo. avatar_url (déjà public, PRODUCT_SPEC-2.md §10.1) pointe vers
-- l'URL publique de ce fichier.
--
-- Non déployée automatiquement : à exécuter sur ton propre projet Supabase (SQL Editor).

insert into storage.buckets (id, name, public)
values ('avatars', 'avatars', true)
on conflict (id) do nothing;

-- Lecture publique : avatar_url est déjà un champ public du profil, cohérent de rendre
-- le fichier lui-même public plutôt que de passer par des URLs signées à durée limitée.
create policy avatars_public_read
    on storage.objects for select
    using (bucket_id = 'avatars');

-- Écriture restreinte au dossier "{auth.uid()}/..." — jamais au nom de fichier choisi
-- par le client, pour qu'un utilisateur ne puisse ni lire (déjà public, sans objet) ni
-- surtout écraser l'avatar d'un autre.
create policy avatars_insert_own
    on storage.objects for insert
    to authenticated
    with check (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);

create policy avatars_update_own
    on storage.objects for update
    to authenticated
    using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);

create policy avatars_delete_own
    on storage.objects for delete
    to authenticated
    using (bucket_id = 'avatars' and (storage.foldername(name))[1] = auth.uid()::text);
