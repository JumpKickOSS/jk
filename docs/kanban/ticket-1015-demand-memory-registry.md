# ticket-1015 — Concurrent worker memory demand registry

**Priority:** P3 (instrument first)  
**Status:** ready  
**Branch:** `ticket-1015-memory-instrument`  
**Refs:** [architecture.md](../architecture.md) engine heap cap, `HeapPlan`, `JvmOptions`,
`MemoryProbe`, engine `activeConnections` / worker concurrency

## Problem

Coarse shared memory plan for concurrent workers may over- or under-admit work. A live
**demand registry** is only justified if measurement shows real pain (idle queueing or
single-build regression). This ticket is **instrumentation + decision**, not a rewrite by default.

## Scope (phase 1 — ship this)

1. **Metrics:** log or status fields for per-build peak worker concurrency, estimated heap budget,
   and optional wait-for-slot counts (engine status / build journal — reuse existing hooks)
2. **Harness doc** in this ticket: how to run 2/4/8 concurrent `jk build`s and what to record
3. **Decision gate:** after measurements (or after one dogfood week), either:
   - **close as “no registry”** with numbers pasted here, or
   - open a follow-up ticket for a demand registry design with the data attached

## Scope (phase 2 — only if gate fires)

Live registry of declared demand per connection; admit workers when sum fits under cap.

## Acceptance (phase 1 only)

- [ ] Observable concurrency / memory-related counters reachable without a debugger
      (status, journal, or documented log line)
- [ ] Measurement recipe written in this ticket
- [ ] Explicit phase-1 completion note: “no registry” **or** link to follow-up ticket

## Out of scope for phase 1

- Implementing the full registry
- Changing default 256 MiB engine cap without data
