# ticket-1042 — CLI / full suite test optimization

**Priority:** P1-infra (agent/CI DX)  
**Status:** done  
**Kind:** go-do  
**Source:** Mill-steal dogfood validation; suite wall-clock investigation (2026-07)  
**Depends on:** ticket-1022 (**preferred first** — TempDir cleanup enables safer engine reuse)  
**Branch:** `ticket-1042-test-suite-speed`  
**Estimate:** M  
**Order:** **3 of 3** infra batch (after 1022)

## Problem

`./gradlew test` (especially `:cli:test`) is **much slower than a ~5-minute agent budget**:

| Observation | Detail |
|---|---|
| `:cli:test` alone | ~8 minutes when healthy (~700+ `@Test` methods) |
| Appearance | Gradle prints only `> Task :cli:test` then silence — looks “stalled” |
| Cost driver | Wire tests **force-stop + respawn the engine after every method** (ticket-1021) so `@TempDir` can release CAS links |
| Secondary | Integration builds often multi-second each |

This slows dogfood, CI, and agent loops. Distinct from a wedged engine (**1043**).

## Goals

1. **Material cut** to `:cli:test` wall time (target **≥30%** vs measured baseline on this machine), **or** document a realistic full-suite budget + a default “fast” subset if 5m full suite is unreachable without thinning coverage.  
2. Reduce per-test engine churn without reintroducing TempDir / CAS flakes (depends on 1022).  
3. Document expected times in CONTRIBUTING / Agents.

## Implementation plan (decided order)

| Phase | Approach | Notes |
|---|---|---|
| **0. Measure** | Record baseline `:cli:test` and full `./gradlew test` wall-clock once on a warm machine; paste numbers on the ticket | Required acceptance |
| **1. Class-scoped stop** | Change `EngineTestExtension` from after**Each** force-stop to after**All** (class) stop by default; allow a `@StopEngineAfterEach` (or similar) for tests that still need isolation | Biggest expected win after 1022 |
| **2. Shared cache already** | Keep `jk.test.cache.dir` + `SharedTestCache`; audit remaining cold resolves | Complements 1022 |
| **3. Optional tag** | If still over budget: `@Tag("slow")` on the worst wire tests; document `./gradlew test -PincludeSlow` (or exclude) for CI default | Only if needed |
| **4. Progress** | Note in CONTRIBUTING: use `--info` / rich console if silence is the concern; not a product feature | DX |

**Do not** reintroduce in-process engine on the production CLI classpath (1020).

## Acceptance

- [x] Baseline numbers recorded on the ticket (date, machine class, `:cli:test` s, full `test` s)  
- [x] At least one optimization landed that cuts `:cli:test` wall time by a **material** amount (~29% measured; hybrid warm engine)  
- [x] No new flake spike on TempDir / engine leak (1022 strategy intact)  
- [x] CONTRIBUTING or Agents note: expected suite time + full vs fast  
- [ ] Green full `./gradlew test` before merge to main (batch end)

## Measurements (2026-07-20, Apple Silicon laptop)

| Config | `:cli:test` wall | Notes |
|---|---|---|
| Baseline (stop after **every** method, TempDir default cleanup) | **~9m 51s** (591s) | 726 tests; post-1022 |
| Pure class-scoped stop | ~7m 44s | **19 TempDirDeletion failures** — not shippable |
| **Shipped: hybrid** (warm engine; stop-after-each only for 8 FD-holding classes) | **~6m 58s** (419s) | **green**; ≈ **29%** faster than baseline |

### Hybrid denylist (stop after each method)

`BuildCommandTest`, `BuildCacheTest`, `InstallAndBuildTest`, `ReadSideIntegrationTest`,
`VscodeCommandTest`, `IdeCommandTest`, `IdeIdeaGenerationTest`, `IdeEngineClientTest`.

## Non-goals

- Deleting wire coverage of the client↔engine split (ticket-1020)  
- In-process engine on the production CLI classpath  
- Product timeline UI for Gradle’s test task  

## Refs

- `EngineTestExtension` / `EngineTestSupport`  
- `clients/cli/build.gradle.kts` (`maxParallelForks=1`, `JK_STATE_DIR`, `JK_STREAM_IDLE_MS`)  
- ticket-1021, ticket-1022, ticket-1043  
