# ticket-1023 — Build timeline / chrome-tracing profile every run

**Priority:** P0  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §1, §5  
**Branch:** `ticket-1023-build-timeline`  
**Refs (code today):** `BuildMetrics` (aggregates, not per-run spans), `Perf` (`JK_PERF=1` stderr),
wire step events via `EngineBuildListenerAdapter` / pipeline listeners

## Problem

Mill writes a per-run chrome profile. JumpKick has forecast (`jk explain`) and historical
aggregates (`BuildMetrics`) but no **per-run** span file for parallel vs sequential long poles.

## Goal

Every hosted pipeline (`build`, `test`, and preferably `compile`) writes a Chrome Trace Event
JSON (Perfetto / `chrome://tracing`) with one complete event per step (and per module in
workspace runs).

## Design

| Item | Choice |
|---|---|
| Default path | `<project>/out/jk-chrome-profile.json` (create `out/`); override `JK_CHROME_PROFILE` or `--timeline <path>` |
| Format | Chrome Trace Event array: `ph:X` complete events, `ts`/`dur` µs, `name`=step, `cat`=module, `args`={status, cacheHit?} |
| When | Always on for build/test (cheap append); optional `--no-timeline` if noise in CI artifacts |
| Parallel | Distinct `tid` or `pid` per concurrent module/worker so overlaps show |

## Implementation sketch

1. Engine: collect `(module, step, startNanos, endNanos, status, cacheHint)` during pipeline run  
2. Write JSON at pipeline-finish (best-effort; never fail the build on write error)  
3. CLI: one stderr line when verbose or always short “timeline: path”  
4. Test: fixture multi-step run → file exists, valid JSON array, ≥1 event with `dur`

## Acceptance

- [ ] `jk build` on a sample project produces loadable Chrome Trace JSON  
- [ ] Cache hit vs miss (or status) visible in event args when known  
- [ ] Parallel module builds show overlapping intervals  
- [ ] Automated test asserts non-empty valid events  
- [ ] Guide or architecture: how to open the file  

## Non-goals

- OpenTelemetry export  
- Replacing `jk explain` or `BuildMetrics` history  

## Enables

ticket-1030 (warm-pool measure), monorepo long-pole hunting
