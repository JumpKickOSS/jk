# Warm compiler pool vs AOT forks — measure & decision

**Date:** 2026-07-21 (re-run)  
**Status:** **DEFER** warm pool (no implementation on `main`)  
**Baseline:** JumpKick short-lived fork + JEP 514 `.aot` (`PluginAot`)  
**Harness:** `scripts/aot-vs-fork-bench.sh`, `scripts/microbench.sh`, chrome timeline (`target/jk-chrome-profile.json`)

## Arms

| Arm | How | On `main`? |
|-----|-----|------------|
| **A. Fork + AOT** (production) | Default; maps `~/.jk/state/aot/javac-*.aot` via `-J-XX:AOTCache=` when present | yes |
| **B. Fork, AOT off** | `JK_WORKER_AOT=off` | yes |
| **C. Warm / resident compiler pool** | Mill-style reused javac/kotlinc JVMs | **no** — not prototyped |

A pool (C) must beat **A**, not only **B**.

## How to re-run

```bash
# Prefer --rebuild-style work (action-cache hits hide compile):
RUNS=5 ./scripts/aot-vs-fork-bench.sh /path/to/project

# Forced recompile median (CLI "took Nms"):
#   for i in 1..7: jk build --skip-tests --rebuild   # AOT on vs JK_WORKER_AOT=off
```

AOT caches train in the background on first miss; ensure `~/.jk/state/aot/javac-*.aot` exists before measuring A.

## 2026-07-21 dogfood (macOS, GraalVM JDK 25, `jk 0.10.0-SNAPSHOT`)

### Forced recompile: `spring-boot-hello` (`jk build --skip-tests --rebuild`, n=7)

| Arm | Median wall (CLI “took”) | Notes |
|-----|--------------------------|--------|
| **AOT-on** | **612 ms** | AOT files present under `~/.jk/state/aot/` |
| **AOT-off** | **621 ms** | `JK_WORKER_AOT=off` |

**Delta: ~1% (noise).** Chrome timeline on a rebuild: ~**426 ms** in `compile-java`, ~136 ms `package-jar` — compile is real work, but AOT mapping is not buying a clear win on this fixture.

### Cache-friendly builds (median of 5, wall including engine handshake)

Small projects often sit on a **~130–160 ms pipeline floor** (noop ≈ clean when action cache / tiny sources). AOT-on vs AOT-off differences there are also **within noise**.

| Project | AOT-on clean | AOT-off clean | AOT-on noop | AOT-off noop |
|---------|-------------:|-------------:|------------:|-------------:|
| spring-boot-hello | 159 ms | 162 ms | 153 ms | 155 ms |
| hello-java | 137 ms | 127 ms | 138 ms | 109 ms |
| workspace-basic | 158 ms | 151 ms | 161 ms | 140 ms |

Engine process RSS samples after runs were ~**450–580 MiB** (full process, not just heap; coarse `ps` after each run — not a peak-worker capture).

## Decision: **DEFER warm pool** (reaffirmed)

| Finding | Implication |
|---------|-------------|
| AOT-on ≈ AOT-off on wall for dogfood rebuilds | Room for a warm pool **might** exist vs fork cold-start, but AOT is not the differentiator on these sizes |
| Warm pool still unprototyped | Cannot claim GO without arm **C** wall + peak RSS (engine + pool × worker heap) |
| Product memory story | Pinned resident compilers fight capped concurrent engines until proven |
| Steady-state noop is already ~150 ms | Warm pool must improve **real compile** (rebuild / multi-module dirty), not handshake |

**Do not implement a warm pool on `main` yet.** Keep `PluginAot` + short-lived workers.

### When to reopen (GO criteria)

Prototype arm **C** in a **worktree** and measure against **A**:

1. Median wall: clean rebuild, single-file incr, multi-module dirty  
2. Peak RSS: engine + N forked workers vs engine + pool  
3. Benefit still holds with AOT **on** (C must beat A, not only B)  
4. Parallelism under default `max-heap-mb` not forced down  

If C wins wall by a clear margin **and** RSS fits concurrent budget → **GO** (JK-1049).  
If C only beats B → **NO-GO**.

## Related

- Historical: [ticket-1030](../kanban/ticket-1030-warm-compiler-pool-benchmark.md), [ticket-1049](../kanban/ticket-1049-warm-pool-implementation.md)  
- [mill-comparison.md](../mill-comparison.md) §5b  
- Harness: `scripts/aot-vs-fork-bench.sh`, `scripts/microbench.sh`
