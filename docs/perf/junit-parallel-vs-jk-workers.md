# JUnit Platform parallel vs jk test workers (JK-1092)

**Status:** research complete · **Product stance: layers are independent; do not stack by default**  
**Date:** 2026-07-22

Companion: [test-parallelization.md](test-parallelization.md), [within-module-test-parallel-spike.md](within-module-test-parallel-spike.md).

## Layer diagram

```
┌─────────────────────────────────────────────────────────────┐
│  jk monorepo (workspace)                                      │
│  -j / jobs  → concurrent *modules* (compile + schedule)       │
│  cross-module tests: default ON (C2); --serial-tests off      │
│  TEST_GATE serializes run-tests unless Session.parallelTests  │
└───────────────────────────┬─────────────────────────────────┘
                            │ per module run-tests
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  Within-module (jk -w / TestWorkers)                          │
│  -w0 auto = min(jobs, classCount) + HeapPlan clamp            │
│  W JVMs fork → pull queue of *classes* (JUnitLauncher)        │
│  W>1 → per-worker java.io.tmpdir                              │
│  [test] workers=1 → force W=1 for hermetic modules            │
└───────────────────────────┬─────────────────────────────────┘
                            │ each worker JVM
                            ▼
┌─────────────────────────────────────────────────────────────┐
│  JUnit Platform *inside* the worker (optional, user config) │
│  junit.jupiter.execution.parallel.enabled=true              │
│  concurrent methods/classes in *one* JVM                      │
│  @Execution, @ResourceLock, same-thread vs concurrent       │
│  jk does NOT set these; classpath junit-platform.properties │
│  / system properties from the project still apply           │
└─────────────────────────────────────────────────────────────┘
```

## What jk does today (code map)

| Layer | Behavior | Code |
|-------|----------|------|
| Cross-module test overlap | Default on; `--serial-tests` opts out | `Session.parallelTests`, `BuildBuildPlans` TEST_GATE, CLI `ParallelTestsOpts` |
| Within-module class shards | Default `-w0` auto; forks + pull queue | `TestWorkers`, `JUnitLauncher` |
| Heap / neighborliness | Shrinks W / module peak JVMs | `HeapPlan`, `PluginSlots` |
| In-process Jupiter parallel | **Not configured by jk** | Project may still enable via JUnit config on the test classpath |

## What JUnit Platform parallel does

Jupiter can run **methods or classes concurrently inside one JVM** when:

- `junit.jupiter.execution.parallel.enabled=true`
- mode / strategy properties (or annotations) choose concurrency
- isolation is the author’s problem (`@ResourceLock`, not sharing statics, etc.)

That is **orthogonal** to process isolation: no separate temp dirs, no separate heaps, shared statics.

## Interaction table

| Combo | Assessment | Notes |
|-------|------------|--------|
| `-w1`, Jupiter parallel **off** (default) | **Safe baseline** | One JVM, sequential classes/methods |
| `-w0` / `-w N` (N>1), Jupiter **off** | **Preferred Mill-like path** | Isolation via forks + per-worker tmp |
| `-w1`, Jupiter parallel **on** | **OK if suite is hermetic** | One process; races are suite bugs, not jk bugs |
| `-w N` (N>1) **and** Jupiter parallel **on** | **Discouraged** | Double parallelism: CPU thrash, heap pressure, flaky races harder to debug |
| Cross-module parallel + either within-module mode | **OK with pins** | Hermetic modules use `[test] workers=1` |

## Recommended product stance

**jk owns process/class sharding (`-j`, `-w`, module pins). JUnit in-process parallel is an advanced suite-level opt-in that jk neither enables nor disables.**

- Prefer **jk `-w`** for speed and isolation (Mill-shaped).
- Use **Jupiter parallel only with `-w1`** (or a single-worker pin) when you need method-level concurrency and trust the suite’s locks.
- **Do not stack** multi-worker forks with Jupiter concurrent execution unless you have measured both wall and flake rate.
- jk will **not** auto-set `junit.jupiter.execution.parallel.*` defaults.

No code change required for this stance; documentation is the deliverable.

## Guide / author recipe

```toml
# Hermetic module (nested engines, fixed ports): force one test JVM
[test]
workers = 1
```

```bash
# Mill-like (default): process sharding only
jk test -j0 -w0

# Method-level Jupiter parallel: keep a single worker JVM
jk test -w1
# + junit-platform.properties in the project if desired
```

## Stacking warn (shipped)

When resolved workers are **&gt; 1** and Jupiter parallel is enabled on the test classpath
(`junit-platform.properties` or system property `junit.jupiter.execution.parallel.enabled=true`),
`JUnitLauncher` emits a pipeline **warn** (`code=jupiter-parallel`) describing the double-parallelism
risk. Detection: `JupiterParallelDetect`. Does not fail the run.

## Refs

- `TestWorkers`, `JUnitLauncher`, `BuildBuildPlans` TEST_GATE  
- [JUnit 5 User Guide — Parallel Execution](https://junit.org/junit5/docs/current/user-guide/#writing-tests-parallel-execution)  
- JK-1086 / JK-1087  
