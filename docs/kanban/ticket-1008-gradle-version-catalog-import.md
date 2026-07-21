# ticket-1008 — Gradle version catalog import (fidelity)

**Priority:** P1 (migration)  
**Status:** done  
**Branch:** `ticket-1008-version-catalog-import`  
**Refs:** [libraries-boms-starters PRD](../features/libraries-boms-starters.md) (§9–10 import +
catalog layers), [guide.md](../guide.md) import, `GradleVersionCatalog.java`, `GradleImporter.java`

## Problem

`GradleVersionCatalog` + importer **already** read catalogs for type-safe accessors during
string-level `build.gradle.kts` import. Gaps remained for:

- Catalog-only / thin-script projects
- Bundle expansion fidelity
- Clear report lines when an alias / version ref cannot resolve
- Docs under-promising relative to code

## Shipped

1. Version-less catalog libraries resolve to bare `group:artifact` (platform-managed roots), not silent drops
2. Unresolved `version.ref` → import report warning + version-less GA
3. Bundle missing members → per-member warning; empty bundle → error
4. Unique reverse-map GA → jk library-catalog short name when importing coords
5. Unit tests: `GradleVersionCatalogTest`, `GradleImporterCatalogTest` under `:toolchain`
6. [guide.md](../guide.md) import section documents catalog fidelity honestly

## Acceptance

- [x] Fixture catalog → produced deps contain expected library coords/versions
- [x] Unresolved catalog ref appears in import report (not silent)
- [x] Version-less catalog libs become platform-managed roots
- [x] Prefer reverse-map of GAV → jk catalog short name when unique
- [x] Unit tests under `:toolchain`
- [x] guide.md import section mentions version catalogs; no versions written into library catalog layers

## Out of scope

- Evaluating `build.gradle.kts` / plugins DSL
- Version catalogs in arbitrary included builds beyond walk-up locate
- Putting versions into project/local/global library catalogs
