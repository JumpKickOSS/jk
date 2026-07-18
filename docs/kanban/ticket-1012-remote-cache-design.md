# ticket-1012 — Remote cache design (read-only first)

**Priority:** P2 (design now; implement post-GA)  
**Status:** ready  
**Branch:** `ticket-1012-remote-cache-design`  
**Refs:** [architecture.md](../architecture.md) CAS/action cache, `ActionKey`, `Cas`,
ticket-1004 invariants (copy-not-link, empty-output rules)

## Problem

Local action keys and CAS layout must not paint us into a corner for a later remote cache
(Bazel REAPI or simple HTTP CAS). Shipping remote is **not** required for 1.0; a short design
is.

## Deliverable (design-only MVP)

A short section in [architecture.md](../architecture.md) (or a single linked subsection — **no**
new top-level product doc file unless content exceeds ~1 page) covering:

1. **Action key ingredients** (task type, input content hashes, toolchain/jdk, jk version,
   plugin/worker jar hashes, OS/arch only when outputs are platform-specific)
2. **CAS addressing** (sha256 layout stays content-addressed; remote stores same hex)
3. **Read-only remote client** sketch: lookup by action key → download blobs → restore locally
4. **Non-goals for 1.0:** write-back remote, ACLs, REAPI full execution, cross-org trust
5. **Compatibility:** keys may gain fields but must not silently reinterpret old local keys
   (version prefix or explicit schema byte)

## Acceptance

- [ ] Architecture (or kanban-linked) design text merged
- [ ] Explicit list of current `ActionKey` contributors vs gaps for remote
- [ ] No production remote client code required for this ticket

## Out of scope

- Implementing HTTP/REAPI client
- Changing local cache GC policy
