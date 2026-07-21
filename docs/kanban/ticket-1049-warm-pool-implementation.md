# ticket-1049 — Warm compiler pool implementation (gated)

**Priority:** P3 (blocked on go criteria)  
**Status:** backlog  
**Kind:** go-do **only if** measure says go  
**Source:** mill-comparison §5b; ticket-1030 (done — DEFER)  
**Depends on:** 1030 measurement; re-run harness (1023/1024) before implementing  
**Branch:** `ticket-1049-warm-pool-impl`  

## Problem

1030 measured warm pool vs AOT forks and **deferred** implementation. Mill’s compile speed story
still benefits from long-lived compiler workers. If a future re-measure shows AOT-on baseline is
beaten on wall **and** RSS, we need a ready implementation ticket.

## Gate (from 1030 / warm-pool-bench.md)

Do **not** start until:

- Steady-state incremental/no-op latency win vs **AOT-on** forks is material  
- Peak RSS fits the capped engine + concurrency story  
- Kill switch and pool size 0/1/N documented  

## Goal (when unblocked)

1. Optional warm javac/kotlinc worker pool in the engine memory plan  
2. Default off or auto only when budget allows  
3. `JK_WORKER_POOL=…` / config kill switch  
4. Microbench + timeline proof  

## Acceptance

- [ ] Go criteria re-validated and linked in PR  
- [ ] Pool implementation with size 0 ≡ current AOT-fork behavior  
- [ ] Tests for enable/disable  
- [ ] Docs: when to use pool vs AOT-only  

## Non-goals

- Implementing without a fresh green measure  
- Dropping AOT path  

## Refs

- [docs/perf/warm-pool-bench.md](../perf/warm-pool-bench.md), `PluginAot`, ticket-1030  
