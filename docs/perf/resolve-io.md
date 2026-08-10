# Resolve / lock I/O (JK-1088)

## Measuring cold lock without wiping `~/.cache/jk`

Use an isolated CAS (works with the resident engine):

```bash
COLD=$(mktemp -d /tmp/jk-cold-XXXX)
time jk lock --cache-dir "$COLD"    # or: JK_CACHE_DIR="$COLD" jk lock
```

See [guide.md](../guide.md) (`JK_HOME` / `JK_CACHE_DIR` / `--cache-dir`).

### Dogfood timings (macOS laptop, 2026-07-21, post lazy-universe + parallel materialize)

| Scenario | Fixture | Wall |
|----------|---------|------|
| Cold CAS (`--cache-dir` empty temp), pre POM-prefetch | `spring-boot-web` (90 deps) | **~52 s** |
| Cold CAS + POM prefetch for pinned children | same | **~29 s** |
| Cold CAS + concurrent POM/BOM expand (JK-1090) | same | **~19 s** |
| Warm local CAS (`~/.cache/jk`) | same | **~1 s** |
| Metadata cache wiped, POMs/jars warm | same | **~0.5–1 s** |

Pre-1088 dogfood (same laptop, first-ish cache): multi-minute Spring Boot locks with sparse progress.

### Quarkus platform (post JK-1202)

`jk new --quarkus` / `[quarkus] version` pulls **`io.quarkus.platform:quarkus-bom`** plus REST
starters — often **200+** packages on first lock. Expect multi-minute cold materialize on a
laptop if the CAS is empty; warm re-lock is seconds. Tips:

- Prefer a warm `~/.cache/jk` (or CI cache of `repos/central/`) for dogfood/CI.
- Engine heap defaults were raised for large BOMs; if lock thrashs, check engine memory flags
  in the guide / architecture notes.
- Residual: further cold-materialize wall-clock work is tracked as product polish (not a
  packaging blocker). Packaging itself is pure bootstrap + fast-jar (JK-1160/1202).

## What costs time on first lock

Cold `jk lock` for large graphs (Spring Boot ~90 packages) is dominated by:

1. **maven-metadata.xml** per GA (disk TTL 24h; local-first — **no HTTP** while warm; past TTL
   conditional GET) — **skipped entirely on happy path when the constraint is exact (platform pins
   under enforced) or a lock soft-prefer seeds a singleton**. `jk lock` does **not** force-revalidate
   every run (avoids Central 429); `jk update` / `-F` revalidate past TTL.
2. **POM + parent chain** fetches per package (online path local-first after first fetch)
3. **Three scope solves** (main / test / processor) with shared in-memory caches
4. **Jar download + CAS + upstream checksum** per package (`toArtifact`, parallel with host rate limit)

Platform BOM pins are **exact** on edges (unmapped fills mediate by default —
`[resolve] unmapped = "strict"` makes them exact too); pins seed a lazy version universe so
metadata is not required unless the pin fails. They do not by themselves skip POM/jar download
work for the chosen GAV.

## Optimizations landed

| Change | Effect |
|--------|--------|
| Local-first online fetch from `repos/<name>/` | Warm re-lock skips re-HTTP for POMs/jars |
| Shared `EffectivePomBuilder` + `MavenPackageSource` across scopes | Avoid re-walking overlapping Spring GAs thrice |
| Graph-phase progress (`onGraphPackage` / `onPhase`) | Bar advances during solve, not only jar fetch |
| Dual-phase tick budget (~2× packages) | Graph + materialize each contribute to the bar |
| **Lazy exact / soft-prefer universes** | Exact constraints (incl. enforced-platform pins) and lock soft-prefers seed `{v}` without `availableVersions`; expand to full metadata only on conflict / unavailable / empty projection |
| **Prefetch skip** for pinned/exact children | Do not eagerly fetch metadata the solver will not need |
| **Parallel lock-time materialize** | `toArtifact` jar fetches on `JkThreads.io()` + `HostRateLimiter` (same pattern as CacheSync) |
| **POM prefetch for pinned children** | After expanding a package, async full `EffectivePomBuilder.build` for preferred/exact children (parents + imports) |
| **Concurrent POM builder (JK-1090)** | Dropped global `synchronized` on `build`; multi BOM-import expand in parallel; sibling prefetches walk chains together |
| **Process-wide POM / GMM / fetch caches** | Warm re-locks and multi-scope solves reuse effective POMs, Gradle `.module` parses, and local POM hit paths |
| **BOM pin warm + frontier prefetch** | Fire-and-forget parallel `rawEdges` for managed pins before PubGrub; exact-child prefetch during expand |
| **GMM `available-at` fast path** | Skip MiniJson when `.module` has no redirect |
| **KMP group filter** | Skip marker scan for groups that never publish Gradle metadata |
| **Local-mirror hot path** | `tryLocalMirror` returns sidecar+path without reclaim/materialize on every resolve hit |
| **GA-keyed KMP / raw-deps caches** | `jar:` vs `aar:` share one POM/`.module` memo (BOM warm seeds default jar keys) |

### NIA (Now in Android) warm-disk lock (~286 packages, multi-repo)

| Scenario | Wall (resolve harness) |
|----------|------------------------|
| First-in-process (process caches cold, CAS warm) | **~8 s** |
| Hot re-lock (same process) | **~3 s** |

Target: both under **10 s** without network. Enable `-Djk.resolve.profile=true` / `JK_RESOLVE_PROFILE=1`
for `pomBuild` / `deps` / `kmp` / `versions` / `solve` counters, plus plan phases
`phasePrep` / `phaseResolve` / `phasePost` on the engine (printed to stderr when profile is on).

### Force vs process caches

`--force` / `-F` / `jk update` (force-revalidate) **clears** process-wide resolve memos (effective
POM, GMM parse, KMP selection, local fetch hits, version lists) before resolve so stale answers
cannot win. Non-force warm re-locks keep the memos.

### Default Google exclusive groups

When the Google Android Maven remote is present, `androidx.*` / `com.android.*` /
`com.google.android.*` (and related) are exclusively bound to it — Central is not probed for those
GAs. Override by declaring `[repositories.google] groups = [...]` explicitly (including empty only
if you intentionally want dual-probe — not recommended).

### Engine first-lock

AOT cache is keyed by engine jar identity + JDK. After the engine endpoint is live, a background
task classloads PubGrub/resolve types so the first plan pays less classload. Residual first-lock
cost after `jk engine start` is mostly cold process resolve memos + any remaining JIT.

## Lazy version universes (metadata skip)

| Positive constraint | First contact | Expand when |
|---------------------|---------------|-------------|
| Exact `=1.2.3` | Seed universe `{1.2.3}` — no metadata | POM missing / not advertised → load full list for diagnostics / next candidates |
| Open range + BOM/lock `preferredVersion` | Seed `{pin}` if pin ∈ constraint | Pin POM 404, or pin outside remaining candidates |
| Open range, no prefer | Full `maven-metadata.xml` as before | — |

Without a platform BOM, bare POM versions remain **highest-wins** (`atLeast`) and still need
metadata unless a prefer pin seeds them. With a platform BOM, bare fills are exact singletons.

Exact pins **do not** need metadata on the happy path. Existence is proven by the POM (or jar) fetch.

## Progress bar model

- Initial ticks ≈ `max(10, declaredRoots × 12) × 2`
- During graph: +1 tick per unique package **as PubGrub decides it** (JK-1091 live hooks; catch-up after each scope for any missed)
- After graph: set total to graphTicks + packages for materialize
- During materialize: +1 tick per package **when its jar fetch completes** (parallel workers; UI drained single-threaded; lock rows still ordered)

### JK-1091 (live ticks)

| Gap before | Fix |
|------------|-----|
| Graph bar jumped only after whole main/test/processor scope | `PubGrubSolver` decision callback → `onGraphPackage` mid-solve |
| Download ticks lagged until join order caught up | Completion queue: tick on finish, assemble lock in stable order |

## Cache inventory

| Layer | What | Lifetime |
|-------|------|----------|
| `MavenMetadataCache` | `maven-metadata.xml` + ETag | 24h TTL under store `metadata/`; force-revalidate only on `jk update` / `-F` |
| `repos/<name>/` | POM + jar (+ sha256) | Permanent; local-first online |
| CAS | Content-addressed bytes | Permanent |
| `MavenPackageSource` version/deps caches | Per lock, shared across scopes | One lock |
| `EffectivePomBuilder` | Effective POM per GAV | One lock (thread-safe for materialize) |
| Lazy `VersionUniverse` | Singleton seed | Per scope solve; expand once |

## Warm re-lock cost (NIA-scale, ~288 packages)

Phase split on a warm disk (no HTTP writes), measured 2026-08:

| Phase | Before opts | After |
|-------|-------------|--------|
| Graph (PubGrub + POM + KMP) | ~18–20 s | ~5–6 s first process pass; **~3–4 s** re-lock |
| Materialize (local CAS) | ~1 s | ~0.6–1 s |
| **Total `jk lock`** | ~30 s | **~4 s** re-lock (engine up); ~20–25 s first after engine start |

Dominant graph costs were not PubGrub bitsets (`relationTo` ~0.6 s / 170k calls) but:

1. **KMP `.module` parse** for every AndroidX multiplatform root (~300 lookups)
2. **Version list union** across Central+Google (now first non-empty wins)
3. **Effective POM** parent/BOM walks (process-wide memo across locks in the resident engine)

Enable counters: `-Djk.resolve.profile=true` → `ResolveProfile.report()`.

## Related

- JK-1089: platform BOM in tree is pin source, not `(missing)`
- Warm metadata: `MavenMetadataCache` (24h)
