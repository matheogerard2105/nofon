# Nofon

Application Android pour reprendre le contrôle de son temps d'écran : on se fixe un objectif (temps d'écran, nombre de déverrouillages), on mise une somme dessus, seul ou en groupe, et l'appli mesure si on l'a tenu.

> Projet personnel en cours. L'argent est **simulé** (aucun paiement réel).

## Fonctionnalités
- **Mesure du temps d'écran** à partir de `UsageStatsManager` : reconstruction des sessions d'utilisation à partir des événements bruts (gestion du verrouillage, du passage de minuit, des particularités Samsung…), couverte par des tests unitaires.
- **Objectifs et mises** : solo ou en groupe, avec un registre financier tenu côté serveur.
- **Exploits** : badges débloqués par des déclencheurs SQL, avec une rareté calculée sur l'ensemble des joueurs.
- **Social** : amis, fil d'activité, profils et avatars ; **groupes** avec objectifs communs.
- **Comptes** : connexion Google (Credential Manager) via Supabase Auth, vérification d'âge.

## Architecture
- **Client** : Kotlin, Jetpack Compose, MVVM + flux de données unidirectionnel, Hilt, Coroutines/Flow, Room, DataStore, WorkManager.
- **Modules Gradle** : `core/` (`tracking`, `network`, `database`, `designsystem`) et `feature/` (`auth`, `home`, `exploits`, `social`, `group`, `profile`).
- **Backend** : Supabase (Postgres). Toute la logique sensible (argent, exploits, groupes) est côté serveur, en SQL avec Row Level Security. Le schéma complet est dans `supabase/migrations/` (17 migrations).

## Lancer le projet
1. Copier `local.properties.example` en `local.properties` et renseigner l'URL et la clé `anon` de son projet Supabase, ainsi que le client ID OAuth Google.
2. Appliquer les migrations de `supabase/migrations/` dans l'ordre.
3. Ouvrir le dossier dans Android Studio et lancer la configuration `app` (Android 10 / API 29 minimum).

Tests : `./gradlew test`

---
Développé avec l'assistance de Claude Code (Anthropic) ; conception produit, architecture et choix techniques par Mathéo Gérard.
