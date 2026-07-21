# ticket-1027 — Selective test / multi-verb plan

**Priority:** P1  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §4  
**Depends on:** ticket-1013 (done)  
**Branch:** `ticket-1027-selective-test-plan`  
**Refs:** `AffectedModules`, `BuildCommand` `--affected-since` git shell-out

## Problem

`--affected-since` exists for **build** only. CI still wants selective **test** and dry-run
without Mill’s full prepare/run v1.

## Goal

```bash
jk test --affected-since=origin/main
jk explain --affected-since=origin/main   # print selected modules / steps
jk build --affected-since=…              # already shipped; keep behavior
```

## Design

1. Extract git-diff + `AffectedModules` resolution used by `BuildCommand` into a shared helper
   (e.g. `AffectedSelection`) used by build, test, explain  
2. TestCommand: filter workspace modules to selected set (or single-module project: all-or-nothing)  
3. ExplainCommand: when flag set, list modules that would run (no engine work beyond parse if possible)  
4. Docs: CI snippet + determinism caveats (path identity, don’t stamp git version into always-dirty inputs)

## Acceptance

- [ ] `jk test --affected-since=<ref>` only runs tests for affected modules (+ reverse deps)  
- [ ] Dry-run via explain (or `jk test --affected-since=… --dry-run`) shows the set  
- [ ] Invalid ref / non-git → same quality errors as build  
- [ ] Unit tests for selection reuse / TestCommand wiring  
- [ ] guide section  

## Non-goals (v1)

- prepare/run artifact handoff between CI machines  
- selectiveInputs overrides  
