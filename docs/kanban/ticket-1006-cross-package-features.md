# ticket-1006 — Cross-package feature selection

**Priority:** P1 (library author UX)  
**Status:** done  
**Branch:** `ticket-1005-1006-features-suggestions`  
**Refs:** `Dependency.requestedFeatures` / `defaultFeatures`, `JkBuildParser`,
`CrossPackageFeatures`, `LockOrchestrator.withProjectDir`

## Design decision

**C (path first) + consumer syntax:** cross-package features work for **`path=`** dependencies
whose target has `jk.toml`. Maven sidecar / git/workspace expansion left for follow-up.
Local project `[features]` unchanged.

## What landed

- TOML: `features = […]`, `default-features = true|false` on dependency tables
- Resolve: activate library features → optional deps become consumer roots
- Lock: `pinned-by = "features:…"` on the library row when selection applied
- Errors: unknown feature names; non-path feature selection rejected with a clear message

## Acceptance

- [x] Design choice recorded (C + path)
- [x] Tests: feature pulls optional; `default-features = false` withholds; unknown feature errors
- [x] Parser tests for consumer keys
- [x] Local-only features path unchanged (no selection ⇒ no expand)
