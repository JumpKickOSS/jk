# ticket-1004 — Permanent CAS / action-cache invariant tests

**Priority:** P0 (correctness)  
**Status:** done  
**Branch:** `ticket-1004-cas-invariants`  
**Refs:** `Cas.putFile`, `ActionCache.store`/`restore`, `ActionKey.forKotlinc`,
`JavaIncrementalCompile.store`

## Problem

Shared inodes between CAS blobs and build trees once poisoned the immutable store. Production
code copies instead of hard-linking; this ticket adds permanent guards so that class of bug
cannot regress silently.

## Invariants covered

| # | Invariant | Test |
|---|---|---|
| 1 | `Cas.putFile` never hard-links workspace → CAS | `CasTest.putFile_never_shares_inode_with_source` |
| 2 | Action-cache restore does not share inode with CAS | `ActionCacheTest.restore_never_shares_inode_with_cas_blob` |
| 3 | Zero-output success with sources is not cached | `ActionCacheTest.store_skips_empty_outputs_when_sources_were_present` + guard in `ActionCache.store` / `JavaIncrementalCompile.store` |
| 4 | Smaller output set does not leave stale classes | `ActionCacheTest.restore_after_smaller_source_set_does_not_leave_stale_classes` |
| 5 | Plugin/worker jar content in action key | `ActionKeyTest.kotlin_plugin_jar_content_is_part_of_action_key` + `artifact_input_tokens_include_worker_identity` |

## Acceptance

- [x] Named tests fail if invariants break (`Files.isSameFile` / content / key inequality)
- [x] `:client-io:test` + `:engine:test` (default CI path)
- [x] Short invariant notes on `Cas` / `ActionCache` class Javadoc
