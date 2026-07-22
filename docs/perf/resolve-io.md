# Resolve / lock I/O (JK-1088)

## Measuring cold lock without wiping `~/.jk/cache`

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
| Warm local CAS (`~/.jk/cache`) | same | **~1 s** |
| Metadata cache wiped, POMs/jars warm | same | **~0.5–1 s** |

Pre-1088 dogfood (same laptop, first-ish cache): multi-minute Spring Boot locks with sparse progress.

## What costs time on first lock

Cold `jk lock` for large graphs (Spring Boot ~90 packages) is dominated by:

1. **maven-metadata.xml** per GA (disk TTL 24h + conditional GET when warm) — **skipped on happy path when constraint is exact or soft-prefer pin seeds a singleton**
2. **POM + parent chain** fetches per package (online path local-first after first fetch)
3. **Three scope solves** (main / test / processor) with shared in-memory caches
4. **Jar download + CAS + upstream checksum** per package (`toArtifact`, parallel with host rate limit)

BOM pins **prefer** versions; they do not by themselves skip POM/jar work. Soft-prefer pins **do** seed a lazy version universe so metadata is not required unless the pin fails.

## Optimizations landed

| Change | Effect |
|--------|--------|
| Local-first online fetch from `repos/<name>/` | Warm re-lock skips re-HTTP for POMs/jars |
| Shared `EffectivePomBuilder` + `MavenPackageSource` across scopes | Avoid re-walking overlapping Spring GAs thrice |
| Graph-phase progress (`onGraphPackage` / `onPhase`) | Bar advances during solve, not only jar fetch |
| Dual-phase tick budget (~2× packages) | Graph + materialize each contribute to the bar |
| **Lazy exact / soft-prefer universes** | Exact constraints and BOM/lock pins seed `{v}` without `availableVersions`; expand to full metadata only on conflict / unavailable / empty projection |
| **Prefetch skip** for pinned/exact children | Do not eagerly fetch metadata the solver will not need |
| **Parallel lock-time materialize** | `toArtifact` jar fetches on `JkThreads.io()` + `HostRateLimiter` (same pattern as CacheSync) |
| **POM prefetch for pinned children** | After expanding a package, async full `EffectivePomBuilder.build` for preferred/exact children (parents + imports) |
| **Concurrent POM builder (JK-1090)** | Dropped global `synchronized` on `build`; multi BOM-import expand in parallel; sibling prefetches walk chains together |

## Lazy version universes (metadata skip)

| Positive constraint | First contact | Expand when |
|---------------------|---------------|-------------|
| Exact `=1.2.3` | Seed universe `{1.2.3}` — no metadata | POM missing / not advertised → load full list for diagnostics / next candidates |
| Open range + BOM/lock `preferredVersion` | Seed `{pin}` if pin ∈ constraint | Pin POM 404, or pin outside remaining candidates |
| Open range, no prefer | Full `maven-metadata.xml` as before | — |

Bare POM versions remain **highest-wins** (`atLeast`); those packages still need metadata unless a soft-prefer pin seeds them.

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
| `MavenMetadataCache` | `maven-metadata.xml` + ETag | 24h TTL under CAS `metadata/` |
| `repos/<name>/` | POM + jar (+ sha256) | Permanent; local-first online |
| CAS | Content-addressed bytes | Permanent |
| `MavenPackageSource` version/deps caches | Per lock, shared across scopes | One lock |
| `EffectivePomBuilder` | Effective POM per GAV | One lock (thread-safe for materialize) |
| Lazy `VersionUniverse` | Singleton seed | Per scope solve; expand once |

## Related

- JK-1089: platform BOM in tree is pin source, not `(missing)`
- Warm metadata: `MavenMetadataCache` (24h)
