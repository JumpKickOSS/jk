# ticket-1008 — Gradle version catalog import (fidelity)

**Priority:** P1 (migration)  
**Status:** ready  
**Branch:** `ticket-1008-version-catalog-import`  
**Refs:** [guide.md](../guide.md) import, `server/toolchain/.../gradle/GradleVersionCatalog.java`,
`GradleImporter.java` (already locates/parses `gradle/libs.versions.toml` and resolves aliases)

## Problem

`GradleVersionCatalog` + importer **already** read catalogs for type-safe accessors during
string-level `build.gradle.kts` import. Gaps remain for:

- Catalog-only projects (deps only in TOML, script is thin)
- Bundle expansion fidelity
- Clear report lines when an alias / version ref cannot resolve
- Docs still under-promise or over-promise relative to code

## Scope

1. Audit `GradleImporter` + `GradleVersionCatalog` against a real-ish fixture
   (`libs.versions.toml` with `versions`, `libraries`, `bundles`, version.ref)
2. Close holes: unresolved refs → **warning/error in import report**, never silent drop
3. Map libraries → `[dependencies]` / test scopes when the script only references catalog aliases
4. Bundles expand to multiple deps or a single documented limitation
5. Guide one-liner: catalog TOML is read; **no** Groovy/Kotlin evaluation

## Acceptance

- [ ] Fixture catalog → produced `jk.toml` contains expected library coords/versions
- [ ] Unresolved catalog ref appears in import report (not silent)
- [ ] Unit tests under `:toolchain` (or importer test module) for parse + map
- [ ] [guide.md](../guide.md) import section mentions version catalogs honestly

## Out of scope

- Evaluating `build.gradle.kts` / plugins DSL
- Version catalogs in arbitrary included builds beyond walk-up locate
