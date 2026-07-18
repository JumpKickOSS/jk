# ticket-1004 — Permanent CAS / action-cache invariant tests

**Priority:** P0 (correctness)  
**Status:** ready  
**Branch:** `ticket-1004-cas-invariants`  
**Refs:** [architecture.md](../architecture.md) (build execution / CAS),
`shared/client-io/.../cache/Cas.java`, `Linking.java`,
`server/engine/.../task/ActionCache.java`, existing `ActionCacheTest`, `CasPrewriterTest`

## Problem

Compilers rewrite outputs in place. Shared inodes between CAS blobs and build trees once
poisoned the immutable store. Production code moved to **copy-not-link** for CAS puts and
several restore paths, but the class of bug needs **permanent automated guards** so it
cannot regress silently.

This ticket is **tests + minimal hardening**, not a new cache design.

## Invariants (must have automated coverage)

| # | Invariant | Suggested home |
|---|---|---|
| 1 | `Cas.put*` never leaves a hardlink from a CAS blob path to `target/` / workspace outputs | `:client-io` or `:engine` Cas tests — assert `!Files.isSameFile(casBlob, buildOut)` after put-from-output |
| 2 | Action-cache restore of class trees does not share inode with live compile out | `ActionCacheTest` restore path |
| 3 | Zero-output “success” for a non-empty source set is never recorded as an action-cache hit for later builds | Compile/action-cache tests |
| 4 | Variant switch + smaller source set does not leave stale classes from the prior variant | Focused engine/compile test or existing variant suite |
| 5 | Plugin worker jar content (or equivalent) is part of the action key — upgrading the worker invalidates | `ActionKeyTest` / plugin key builder |

## Acceptance

- [ ] Each invariant above has a named test that **fails** if the invariant is violated
      (prefer `Files.isSameFile` / content checks over timing)
- [ ] Tests run on the default CI path (`./gradlew test` modules already in `.github/workflows`)
- [ ] No new public product doc; optional one-line class Javadoc on `Cas` / `ActionCache` pointing at the invariant

## Out of scope

- Remote / shared cache protocol (ticket-1012)
- Changing GC / sweep policy beyond what tests need
- Full property-based filesystem fuzzer (nice-to-have later)

## Depends on

None. Small surface; good parallel worktree next to 1001/1002.
