# ticket-1047 — Task inspect / show (Mill resolve-lite)

**Priority:** P3  
**Status:** backlog  
**Kind:** go-do  
**Source:** mill-comparison §3; ticket-1031 non-goal (full Mill path UX)  
**Depends on:** soft — 1031 selectors, 1035 DAG (optional), hatch SPI 1044  
**Branch:** `ticket-1047-task-inspect`  

## Problem

Mill’s CLI is a **queryable task graph** (`resolve`, `inspect`, `show`). jk has:

- `jk explain` (forecast)  
- `--modules` selectors  
- Pipeline step names in progress UI  

Missing: inspect a **named step/task** (inputs, outputs, cache key / last result) without reading
chrome profiles or code.

## Goal (thin Mill steal)

```bash
jk tasks                     # list first-party steps (+ hatch tasks if registered)
jk inspect compile-main      # or: jk explain --inspect compile-main
jk show package-jar          # print primary output path(s) for the current module
```

Prefer extending `explain` / a small `tasks` command over inventing a second graph language.

## Acceptance

- [ ] List steps for current module (and selected modules)  
- [ ] Inspect: name, phase, last cache hit/miss if known, output paths when known  
- [ ] Show: at least jar / classes dir for package/compile  
- [ ] Tests + guide one-liner  
- [ ] Works with `--modules`  

## Non-goals

- Full Mill `{a,b}.__.compile` path algebra  
- Replacing `jk explain` forecast  
- SVG/DOT (that’s 1035)  

## Refs

- `ExplainCommand`, `BuildPlan`, `StepNames`, build-logic task ids  
