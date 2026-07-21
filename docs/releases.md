# Releases

**Status:** stub (JK-1071). Full release pipeline content is owned by **JK-1066**
(publish workflow, SHA256SUMS, ReleaseVerifier).

Until that lands:

- Local dogfood: `./gradlew dist` → `./install.sh build/dist/jk`
- Installer archive formats (referenced by `install.sh`): `.xz` preferred, `.zip` fallback
- Version layout: `~/.jk/versions/<version>/` via `jk self materialize` (CAS-backed)

Do not treat this file as the system of record for release engineering until JK-1066 ships.
