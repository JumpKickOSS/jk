# ticket-1040 — Selective prepare / resolve / run (depth of 1027)

**Priority:** P1-depth  
**Status:** done
 
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §4  
**Depends on:** 1027, 1013 (done); 1031 selectors (same depth pass)  
**Branch:** `ticket-mill-steal-p0-p1` (depth pass)

## Problem

`--affected-since` selects modules for build/test, but CI still lacks Mill’s **prepare → run**
handoff: snapshot the plan once, reuse on agents, dry-run resolve without building.

## Goal

```bash
jk selective resolve --since=origin/main   # print modules (human + --json)
jk selective prepare --since=origin/main   # write .jk/selective-plan.json
jk selective run build                     # build using plan (or --since=)
jk selective run test
```

Plan file fields: `since`, `gitHead` (optional), `modules` (relative paths), `createdAt`.

`selective run` with neither plan nor `--since` errors clearly.  
`--modules` (1031) intersects with plan/since when both present.

## Acceptance

- [x] resolve / prepare / run subcommands  
- [x] prepare writes `.jk/selective-plan.json`  
- [x] run build|test honors plan / --modules / --since  
- [x] docs CI snippet in guide  
- [x] `SelectiveCommandTest`  

## Shipped

- `jk selective resolve|prepare|run` via `SelectiveCommand`  


## Non-goals

- Content-hash prepare (input digests) — git-ref selection only in v1  
- Cross-machine artifact handoff beyond the plan JSON  
