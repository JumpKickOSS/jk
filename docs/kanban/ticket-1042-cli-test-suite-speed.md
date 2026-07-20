# ticket-1042 — CLI / full suite test optimization

**Priority:** P3 (infra / DX)  
**Status:** backlog  
**Kind:** go-do  
**Source:** Mill-steal dogfood validation; suite wall-clock investigation (2026-07)  
**Depends on:** soft — ticket-1022 (TempDir cleanup) related  
**Branch:** `ticket-1042-test-suite-speed`  
**Estimate:** M–L  

## Problem

`./gradlew test` (especially `:cli:test`) is **much slower than a 5-minute budget**:

| Observation | Detail |
|---|---|
| `:cli:test` alone | ~8 minutes when healthy (~736 `@Test` methods) |
| Appearance | Gradle prints only `> Task :cli:test` then silence — looks “stalled” |
| Cost driver | Wire-based tests **force-stop + respawn the engine after every method** (ticket-1021) so `@TempDir` can release CAS links |
| Secondary | Integration builds often 4–5s each; full suite = cli + engine + plugins |

This slows dogfood, CI, and agent loops. It is **not** the same bug as a wedged engine (see ticket-1043), but long silent runs make real hangs hard to spot.

## Goals (pick a subset; document tradeoffs)

1. **Wall-clock target** (propose): full `./gradlew test` under **~5 minutes** on a warm laptop CI-class machine, or document a realistic budget if 5m is unreachable without thinning coverage.  
2. Reduce per-test engine churn without reintroducing TempDir / CAS flakes.  
3. Improve progress visibility (`--console=rich` default for humans; or periodic “N tests done” for plain).

## Approaches (evaluate in ticket / PR)

| Approach | Idea | Tradeoff |
|---|---|---|
| **A. Warm engine across tests** | Stop only between classes / suites, not every method | Faster; harder TempDir cleanup (1022) |
| **B. Shared suite engine + cache outside TempDir** | CAS always under `jk.test.cache.dir`; project trees never hold engine links | Unblocks safer TempDir delete |
| **C. Thin integration set** | Mark slowest wire tests `@Tag("slow")`; default suite is unit + smoke | Coverage split |
| **D. Parallel module tests carefully** | Allow more Gradle workers where state dirs don’t collide | Risk of flake / socket races |
| **E. Progress** | Ensure plain console still shows lifecycle or use build scan | DX only |

**Likely combo:** B + A (or class-level stop) + optional C for CI default.

## Acceptance

- [ ] Written measurement: baseline wall-clock for `:cli:test` and full `test` on a known machine  
- [ ] At least one optimization landed that cuts `:cli:test` wall time by a **material** amount (e.g. ≥30%) **or** CI default suite &lt; target with slow tests opt-in  
- [ ] No new flake spike on TempDir / engine leak (document strategy vs 1022)  
- [ ] CONTRIBUTING or Agents note: expected suite time + how to run full vs fast  

## Non-goals

- Deleting wire coverage of the client↔engine split (ticket-1020)  
- In-process engine on the production CLI classpath  

## Refs

- `EngineTestExtension` / `EngineTestSupport` (afterEach force-stop)  
- `clients/cli/build.gradle.kts` (`maxParallelForks=1`, `JK_STATE_DIR`, `JK_STREAM_IDLE_MS`)  
- ticket-1021, ticket-1022  
