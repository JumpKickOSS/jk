# Incremental compile: Zinc vs ABI contracts (JK-1046)

**Date:** 2026-07-20  
**Status:** **DECISION — stay on ABI contracts; defer Zinc embed**  
**Depends on:** JK-1029 (Java ABI contracts + tests) — done

## Question

Should JumpKick replace or supplement the post-1029 **Java ABI incremental** path with
**Zinc** (or a Zinc-compatible analysis layer), and what should we do for **Kotlin**?

## Decision

| Layer | Choice |
|---|---|
| **Java** | Keep **ABI contracts** (`ClassAbi` / incremental analysis already tested under 1029). Do **not** embed Zinc in the engine or native CLI. |
| **Kotlin** | Stay on the **Kotlin Build Tools API** worker path already in tree. No separate Zinc-for-Kotlin productization. |
| **Zinc** | **Deferred.** Revisit only if (a) multi-module Java rebuild false-positives become a measured product problem, and (b) a spike shows Zinc analysis can run **out-of-process** without poisoning Graal native-image or engine RSS budgets. |

## Rationale

1. **1029 already closed the Mill-comparable gap that mattered for Java** — public API vs body
   change contracts with automated tests. Shipping Zinc would re-solve a problem we have
   regression coverage for, at high integration cost.
2. **Graal / engine constraints** — Zinc is a large Scala/Java analysis stack historically aimed
   at long-lived sbt/Mill workers. JumpKick’s engine is **memory-capped** and prefers **fork +
   AOT** workers (see [warm-pool-bench.md](warm-pool-bench.md)). Embedding Zinc in-process risks
   RSS and native-image complexity; forking it as yet another worker is a full product line.
3. **Kotlin** — kotlinc BTA is the supported incremental surface. Dual analysis (Zinc + BTA)
   would not simplify “why recompiled” UX.
4. **UX polish** — richer dirty-reason text in `jk explain --verbose` can land as a small
   follow-up without Zinc (track under backlog if needed).

## Explicit non-goals (this decision)

- Full Scala Zinc productization  
- Replacing the action cache  
- Warm compiler pools (still gated on JK-1049 / measure)

## When to reopen

Reopen Zinc **only** with:

- A failing multi-module Java incremental fixture that ABI contracts cannot fix without false
  rebuilds or false skips, **and**
- A spike document with wall-time + peak RSS vs status-quo on a real monorepo, out-of-process
  worker only.

## Follow-ups (optional backlog)

- Kotlin ABI-style contract tests if BTA gaps appear (not required by this decision).  
- Explain “why this file recompiled” strings when dirty reasons are already known in-engine.

## Refs

- JK-1029, `ClassAbi` / `ClassAbiContractTest`, kotlin-compiler worker  
- [warm-pool-bench.md](warm-pool-bench.md), [mill-comparison.md](../mill-comparison.md) §2  
