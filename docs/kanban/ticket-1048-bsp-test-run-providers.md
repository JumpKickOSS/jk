# ticket-1048 — BSP test / run providers (depth of 1028 / 1041)

**Priority:** P3  
**Status:** backlog  
**Kind:** go-do  
**Source:** mill-comparison §7; 1028/1041 non-goals  
**Depends on:** 1041 (done); soft — 1017 marketplace  
**Branch:** `ticket-1048-bsp-test-run`  

## Problem

BSP MVP supports compile + sources + dependency modules. Mill / Metals-class IDEs also expect
**test** and **run** capabilities on build targets for one-click workflows.

## Goal

1. Advertise `canTest` / `canRun` where accurate.  
2. Implement `buildTarget/test` and/or `buildTarget/run` (or document BSP-adjacent tasks via IDE
   plugin only — prefer real BSP if cheap).  
3. Wire to existing `jk test` / `jk run` engine paths.  

## Acceptance

- [ ] At least one of test or run works end-to-end against the stdio BSP server  
- [ ] Framing/protocol test or smoke  
- [ ] Docs: capabilities matrix (what BSP can/can’t do)  

## Non-goals

- Debug adapter  
- Scala/Metals-specific extensions  
- Replacing marketplace plugin UX (1017)  

## Refs

- `BspServer`, `IdeEngineClient`, `TestCommand`, `RunCommand`  
