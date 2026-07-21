# ticket-1030 — Warm compiler pool vs AOT forks (measure only)

**Priority:** P1  
**Status:** done  
**Kind:** research / measure — **no warm-pool ship**  
**Source:** [mill-comparison.md](../mill-comparison.md) §5b  
**Depends on:** 1023, 1024 (soft: can start harness pieces in parallel)  
**Branch:** `ticket-1030-warm-pool-bench`  
**Refs:** `PluginAot` (`.aot` for javac/kotlinc), `JK_WORKER_AOT`

## Goal

Write a go/no-go with numbers: **fork + AOT** vs **AOT off** vs **experimental warm pool**
(prototype branch OK, not merged).

## Metrics

Wall (clean / incr / noop), peak RSS (engine + workers), parallel build under default heap plan.

## Go criteria

- Steady-state win **with AOT baseline on**, and  
- RSS fits concurrent-build product story, and  
- Does not force lower useful parallelism  

## Acceptance

- [ ] Results table in ticket or `docs/perf/`  
- [ ] Explicit go / no-go / defer  
- [ ] If go: open implementation ticket; if no-go: update mill-comparison §5b  

## Non-goals

- Landing warm pools on main  

## Shipped

- Decision **DEFER** in `docs/perf/warm-pool-bench.md`
- Microbench + AOT-off control remain the measurement path; no pool on main
