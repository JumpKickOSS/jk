# Decision: mirror checksum verification on store hits (JK-1451)

**Status:** decided 2026-08-03 · **Scope:** `store/repos/<name>/` mirror hits (the shared,
long-lived per-repo store fed by `RepoArtifactStore.materialize`)

## Context

Since the cache/store split, a mirror hit returns whatever bytes were first stored for a
coordinate, forever. Two consequences drove this research (JK-1451):

1. **Mutable re-publish** — an upstream coordinate re-published with different bytes (snapshots,
   misbehaving private repos, `jk install` over an existing coordinate) keeps serving stale bytes,
   and locks record stale checksums.
2. **Test-suite pollution** (JK-1450) — fixtures reusing a coordinate with different bytes poisoned
   each other; worked around with fixture hygiene + store isolation rather than product
   verification.

What already exists and is load-bearing:

- The lockfile pins `sha256:` per artifact. `RepoArtifactStore.verify(relPath, expectedSha256)`
  compares the stored sidecar against the lock pin, and `locate(relPath, sha)` treats a
  `MISMATCH` as *absent*, so callers (e.g. `ClasspathResolver`) already fall back to the CAS blob
  (whose path **is** its content hash) or re-fetch. A drifted mirror entry cannot silently reach a
  compile classpath **when the caller goes through the hash-verified locate**.
- The sidecar is written last (fully-stored signal); `repos/` is exclusively jk-owned.

## The four questions, answered

### 1. Should a mirror hit verify against upstream (sidecars / maven-metadata / conditional GET)?

**No — not per hit.** A network round-trip per store hit defeats the point of the store, breaks
`--offline` builds, and re-introduces the latency the CAS design removed. Upstream consultation
belongs to the operations that already talk to the network:

- **`jk lock` (re-lock)** is the moment upstream truth matters. A future enhancement may do
  conditional GET (ETag / checksum-sidecar HEAD) during locking for *changing* coordinates, but
  release coordinates don't need it (see Q4).
- **Plain builds** trust the lock + local verification chain only.

### 2. Cost model

**Verify-on-mismatch (already in place), not per-hit hashing.** The chain is:

- lock pins `sha256` → hash-verified `locate(relPath, sha)` on classpath materialization →
  mismatch behaves as a miss → CAS-or-refetch. Per-hit cost is two stats + one tiny sidecar read.
- Full content re-hash per hit (~100 MB classpaths) would cost seconds of CPU per warm build for a
  corruption class (jk-owned tree, hard-linked CAS) that the sidecar invariant already bounds.

**Gap to close (follow-up):** callers that use the *unverified* `locate(relPath)` on paths that
feed a worker classpath (e.g. `PluginJar.locate`, `JkPluginSync`) should migrate to the
hash-verified overload where a pinned hash exists (JK-1461).

### 3. Offline semantics

`--offline` must keep working from the mirror alone; verification never adds a network
dependency. On a lock/store mismatch while offline, the build fails with a message that names the
coordinate and suggests `jk repo refresh <coord>` once online — it must **not** silently serve
mismatched bytes (JK-1462 covers the message).

### 4. Immutability policy

**First-write-wins is the documented contract for release coordinates** — identical to Maven
Central semantics (a released coordinate is immutable; republishing different bytes is an upstream
bug). jk documents this and provides an escape hatch:

- `jk repo refresh <coordinate>` — evict the mirror entry (artifact + sidecar) and re-fetch on
  next resolve, for the cases where upstream really did change (JK-1460).
- Changing coordinates (`-SNAPSHOT`-style) are out of scope for the mirror-immutability contract;
  if/when jk grows first-class snapshot support, snapshot entries get a TTL/conditional-GET
  refresh policy at lock time — not per hit.

## Follow-up tickets

- **JK-1460** — `jk repo refresh <coordinate>` escape hatch (evict + refetch; docs in
  maven-repo.md).
- **JK-1461** — audit unverified `RepoArtifactStore.locate(relPath)` callers on classpath-feeding
  paths; use the hash-verified overload where a lock pin exists.
- **JK-1462** — offline mismatch: fail with coordinate + `jk repo refresh` guidance instead of a
  generic miss.

## Non-goals

- Per-hit upstream verification (rejected above).
- Signature/PGP verification of upstream artifacts (separate topic; `ReleaseVerifier` covers jk's
  own release channel only).
