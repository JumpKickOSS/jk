# ticket-1015 — Concurrent worker memory demand registry

**Priority:** P3 (instrument first)  
**Status:** done (phase 1)  
**Branch:** `ticket-ready-batch`

## Phase 1 shipped

Observable concurrency high-water marks without a debugger:

| Field | Where |
|---|---|
| `activeRequests` / `activePipelines` | `jk engine status` / status-ack / `GET /api/status` |
| `peakActiveRequests` / `peakActivePipelines` | same (process lifetime peaks) |
| heap used/committed/max, RSS | existing status fields |

## Measurement recipe

```bash
# Terminal A: long-lived engine
jk engine start
jk engine status   # note peaks start at 0/current

# Terminals B–I: 2, 4, or 8 concurrent builds against the same JK_HOME
for i in $(seq 1 8); do
  (cd /path/to/project && jk build) &
done
wait
jk engine status   # read peakActiveRequests / peakActivePipelines / heap*
```

Record: peak concurrent requests, peak pipelines, heap max vs used, whether builds queued
while CPU/RAM were idle, and single-build wall-clock vs a solo baseline.

## Phase-1 decision

**No demand registry for now.** Instrumentation is in place; revisit when dogfood shows
idle queueing or single-build regression under concurrent load (see triggers below). Default
256 MiB engine cap unchanged without data.

## Revisit when

- `peakActiveRequests` distribution measured on real multi-client use
- Single-build wall-clock regression vs pre-engine sizing
- Synthetic 2/4/8 concurrent builds queue while CPU/RAM idle

## Acceptance (phase 1)

- [x] Observable concurrency counters without a debugger
- [x] Measurement recipe in this ticket
- [x] Explicit phase-1 completion: **no registry** until data fires a follow-up
