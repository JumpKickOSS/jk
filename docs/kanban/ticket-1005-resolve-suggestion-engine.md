# ticket-1005 — Resolve conflict suggestion engine

**Priority:** P1 (diagnostics product)  
**Status:** open  
**Refs:** [guide.md](../guide.md) §8.2, [architecture.md](../architecture.md) P2.1,
R6a already lists `available:` samples

## Problem

PRD showcase diagnostics end with concrete next steps (`jk add …`, upgrade Boot, override).
Today we render derivation prose + available versions, but not ranked fix suggestions.

## Work

1. On `NoVersions` / root failure, compute candidates from `PackageSource.versions` that satisfy
   *other* constraints in the partial derivation (best-effort)
2. Emit 1–3 suggestions: pin exact, relax root, remove conflicting feature
3. Optional later: `jk lock --fix` applying the first safe suggestion (out of MVP)

## Acceptance

- [ ] At least one integration test where message contains a suggested `jk add g:a:v` or pin line
- [ ] No false “suggestion” when graph is truly empty / unknown package (already handled)
- [ ] Stays offline given cached metadata (no extra network beyond solve)

## Depends on

R6a available samples (done). Classifier identity (1002) improves suggestion coords later.
