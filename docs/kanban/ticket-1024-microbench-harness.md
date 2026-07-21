# ticket-1024 — Microbench harness (clean / incremental / no-op)

**Priority:** P0  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §1, §5b  
**Branch:** `ticket-1024-microbench-harness`  
**Refs:** shrink/AOT kill switches `JK_WORKER_AOT`, engine status; dogfood workspace

## Problem

No standard matrix → cannot decide warm pools or claim Mill-comparable latency.

## Goal

One command (script under `scripts/` or `docs/perf/`) that times fixed scenarios on a fixed
fixture and prints a markdown table. Optional peak RSS where OS allows.

## Scenarios (required)

| ID | Setup | Measure |
|---|---|---|
| clean-all | wipe module outputs (and optionally action cache for that project) | wall `jk build --skip-tests` |
| clean-one | single module clean | wall compile/build one module |
| incr-body | edit one method body only | wall rebuild |
| noop | second build unchanged | wall rebuild |
| aot-off | `JK_WORKER_AOT=off` + noop or clean-one | control arm |

## Implementation sketch

- Fixture: small multi-module under `docs/perf/fixture/` **or** reuse temp `jk init` tree  
- Script: bash or Java main; warm engine once; 3 runs median  
- Output: stdout table + optional JSON  
- Document hardware/OS note in header comment  

## Acceptance

- [ ] `./scripts/microbench.sh` (or equivalent) runs the matrix without network after first dep fetch  
- [ ] All scenarios above present or skipped with explicit reason  
- [ ] CONTRIBUTING or `docs/perf/README.md` explains how to interpret  
- [ ] Results comparable when machine noted  

## Non-goals

- Publishing Mill comparison numbers as marketing  
- Implementing warm pools (1030)  

## Enables

ticket-1030
