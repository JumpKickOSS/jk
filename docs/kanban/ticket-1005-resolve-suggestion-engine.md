# ticket-1005 — Resolve conflict suggestion engine

**Priority:** P1 (diagnostics product)  
**Status:** ready  
**Branch:** `ticket-1005-resolve-suggestions`  
**Refs:** [guide.md](../guide.md) (resolve / why), `server/resolver/.../pubgrub/Diagnostics.java`,
`UnsatisfiableException`, `PackageSource.versions`, `PackageId` (ticket-1002)

## Problem

Conflict output already has derivation prose and `available:` version samples (R6a). It does
**not** yet end with ranked, actionable next steps (`jk add …`, pin, relax root). That is the
product gap vs Cargo/uv “try this” messaging.

## Scope (MVP)

1. On unsatisfiable solve (`NoVersions` / root incompatibility), inspect the derivation and
   `PackageSource.versions` for packages that appear in the conflict.
2. Rank **1–3** suggestions (best-effort, offline once metadata is already cached for the solve):
   - pin a concrete version that satisfies *other* terms in the partial solution
   - relax / remove a root constraint (report which root library)
   - optional: “feature X activates optional dep Y” only when the derivation names a feature edge
3. Render under a clear `Suggestions:` block after the prose (reuse Diagnostics palette).
4. Package names in suggestions use `PackageId.display()` (not raw `g:a:jar:` noise).

## Out of scope

- `jk lock --fix` auto-apply (follow-up)
- Network calls beyond what the failed solve already did
- Full constraint solver “minimum change” (heuristic is fine)

## Acceptance

- [ ] Integration or unit fixture: failure message contains at least one concrete suggestion line
      (e.g. pin `g:a:v` or named root library)
- [ ] Unknown / empty package → no fabricated suggestion (existing no-versions path stays honest)
- [ ] Offline after metadata cache; no new HTTP in the suggestion path
- [ ] `:resolver` tests green; no change to successful solve results

## Depends on

ticket-1002 (done) — suggestions should print full identity when classifiers matter.
