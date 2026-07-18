# ticket-1004 — Permanent CAS / action-cache invariant tests

**Priority:** P0 (correctness)  
**Status:** open  
**Refs:** [architecture.md](../architecture.md) (CAS aliasing sweep),
`server/engine/.../task/ActionCache`, `Cas`, variant switch tests

## Problem

Compilers rewrite outputs in place. Shared inodes between CAS blobs and build trees poisoned
immutable store (dozens of blobs). Fixed by copy-not-link, but the class of bug needs permanent
guards so it cannot regress silently.

## Invariants to test (property or focused integration)

1. `Cas.put*` never leaves a hardlink from CAS blob path to `target/` / workspace outputs
2. Action cache restore copies class trees (no shared inode with live compile out)
3. Zero-output success for non-empty source set is never action-cached
4. Variant switch + shrink source set does not leave stale classes from prior variant
5. Plugin worker jar hash is part of action key (upgrade invalidates)

## Acceptance

- [ ] Tests live under engine/resolver as appropriate; fail if `Files.isSameFile(casBlob, buildOut)`
- [ ] Named in CI path that runs on every PR (`:engine:test` or equivalent)
- [ ] One-line pointer from `docs/recent-features.md` or engine task package-info

## Non-goals

- Remote cache protocol (ticket-1012)
