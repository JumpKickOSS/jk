# Warm javac worker pool (JK-1416)

**Status:** **DECISION — do not ship**  
**Date:** 2026-08-03  
**Ticket:** JK-1416

## Question

Should JumpKick keep a **resident / pooled `javac` (ToolProvider) worker** across dirty
builds to close the last dirty-wall gap vs Mill’s warm Zinc JVM?

## Context (already measured)

JK-1392 re-bench (2026-08-03, BocaBox, Netty monorepo, `-j 1`):

| Scenario | JumpKick | Mill |
|----------|---------:|-----:|
| Cold full recompile | ~16.5 s | ~24.8 s |
| Warm no-op | **209 ms** | 454 ms |
| Dirty one `common` source | **201 ms** | **122 ms** |

jk is ahead on cold and warm no-op. Dirty is the only column where Mill leads (~**80 ms**).
That gap is process/JIT residency: Mill keeps Zinc warm; jk re-forks bare `javac` per dirty
build (`JavaIncrementalCompile` — AOT-free by design; bare-javac AOT was noise).

## Decision

**Do not implement a warm javac worker pool.**

The ~80 ms dirty improvement is not worth the **extra RSS** of a resident compiler JVM (or
pool) held for dirty rebuilds. Prefer a lean engine memory footprint over chasing Mill’s
dirty-one-file wall on this path.

## What stays

| Choice | Rationale |
|--------|-----------|
| Fork bare `javac` per dirty Java compile | Simple, bounded RSS, current path |
| Bare `javac` AOT off | Measured noise; see [warm-pool-bench.md](warm-pool-bench.md) |
| Zinc embed deferred | [incremental-zinc-decision.md](incremental-zinc-decision.md) (JK-1046) |
| PluginAot for `java … PluginMain` workers | Still on for source-gen AP / kotlin workers |

## Reopen only if

1. Dirty one-file (or multi-module dirty) becomes a **product-level** complaint with
   measured evidence that residency is the dominant remaining cost, **and**
2. A spike shows a warm pool can deliver most of the gap **within** the engine’s existing
   worker heap plan (no material RSS regression on typical monorepos).

Until then: accept the Mill dirty delta; do not open implementation tickets for javac
residency.

## Refs

- [netty-benchmark.md](netty-benchmark.md) (dirty column + this decision pointer)
- [warm-pool-bench.md](warm-pool-bench.md) (AOT / warm-pool go-no-go history)
- `JavaIncrementalCompile` (forked bare javac path)
