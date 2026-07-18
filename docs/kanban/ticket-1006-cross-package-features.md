# ticket-1006 — Cross-package feature selection

**Priority:** P1 (library author UX)  
**Status:** ready  
**Branch:** `ticket-1006-cross-package-features`  
**Refs:** [guide.md](../guide.md) features section (today: **local** features only),
`shared/jk-api/.../Features.java`, `Feature.java`, `Dependency`, `LockOrchestrator` optional-dep
activation, lock schema under `shared/core/.../lock/`

## Problem

Features today only activate **optional deps declared in the consuming project**. A library
cannot publish Cargo-style feature sets that consumers enable with:

```toml
[dependencies]
widget = { group = "com.example", name = "widget", version = "0.3.1",
           features = ["mysql"], default-features = false }
```

## Design decision (record in this ticket when implemented)

Pick **one** metadata carrier for published features (do not invent two):

| Option | Pros | Cons |
|---|---|---|
| A. Sidecar `META-INF/jk-features.toml` in the jar | Clear, jk-native | Needs publish + resolve fetch |
| B. POM `<properties>` / custom XML | Travels with Maven | Ugly, fragile |
| C. Lock-only for path/git deps first | Ships faster | No Central story |

**MVP recommendation:** path + git deps + local workspace modules (feature table in their
`jk.toml`), then Central via sidecar if time. Consumer syntax lands either way.

## Implementation slices

1. **Consumer syntax** in `jk.toml` parser: `features = […]`, `default-features = bool` on a dep
2. **Library declaration** in library `jk.toml` (`[features]` already exists for local — extend
   so published/workspace modules expose the same shape to dependents)
3. **Resolve**: when expanding a package, activate its optional deps per selected feature set;
   record activated set on the lock row (or adjacent field) for reproducibility
4. **Tests**: library fixture with optional `mysql` dep; consumer enables feature → lock gains
   the optional coord; `default-features = false` withholds defaults

## Acceptance

- [ ] Short design choice recorded at top of this ticket (A/B/C or hybrid)
- [ ] Consumer fixture: enable feature → optional dep appears in `jk.lock`
- [ ] `default-features = false` honored
- [ ] No silent ignore of unknown feature names (error with library package id)
- [ ] Local-only features (consumer project) still work unchanged

## Out of scope

- Features that change source sets / variants (use variants/profiles)
- Feature unification across diamonds beyond “union of requested features” (document if simpler)
