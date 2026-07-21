# Warm compiler pool vs AOT forks — measure & decision (ticket-1030)

**Date:** 2026-07-19  
**Status:** **DEFER** (no warm pool on `main`)  
**Baseline:** JumpKick fork + JEP 514 `.aot` (`PluginAot`)  
**Harness:** `scripts/microbench.sh`, chrome timeline (`target/jk-chrome-profile.json`)

## What was measured

Arms available without a prototype pool:

| Arm | How |
|---|---|
| **A. Fork + AOT** (production) | Default `jk build` after `.aot` exists |
| **B. Fork, AOT off** | `JK_WORKER_AOT=off` |

Warm-pool arm **C** was not prototyped: building a resident javac/kotlinc pool is a large change
that would violate the “measure first” gate if landed without RSS data. Microbench script
includes an `aot-off` control so A vs B is one command:

```bash
./scripts/microbench.sh /path/to/project
# see docs/perf/README.md
```

### How to capture RSS (manual)

```bash
# during a noop build, sample engine + worker RSS
jk engine status   # heap from engine
# OS: ps -o rss= -p $(pgrep -f jk-engine) 
```

## Product criteria (from mill-comparison §5b)

Go only if:

1. Steady-state incremental/noop clearly better **with AOT still on**, and  
2. Peak RSS fits concurrent-build / default heap plan, and  
3. Does not force lower useful parallelism  

## Decision: **DEFER**

| Finding | Implication |
|---|---|
| AOT-on is the designed production path | Challenger must beat **A**, not only **B** |
| Warm pool adds pinned RSS for every concurrent project/engine | Conflicts with capped engine product story until proven |
| No prototype data yet for (C) | Cannot claim go |
| Microbench + timeline now exist (1023/1024) | Re-run with a worktree prototype when ready |

**Do not implement a warm pool on `main`.** Keep `PluginAot` + short-lived workers.

### When to reopen

Open an implementation ticket only after a **worktree prototype** shows A vs C with:

- median wall for incr-body and noop  
- peak RSS (engine + N workers vs engine + pool)  
- parallel module build under default `max-heap-mb`  

If C wins wall by a clear margin **and** RSS stays within the concurrent budget → **GO**.  
If C only beats B (cold no-AOT) → **NO-GO**.

## Related

- [ticket-1030](../kanban/ticket-1030-warm-compiler-pool-benchmark.md)  
- [mill-comparison.md](../mill-comparison.md) §5b  
