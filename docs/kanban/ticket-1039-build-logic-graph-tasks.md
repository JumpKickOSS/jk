# ticket-1039 — Build-logic graph-native tasks (depth of 1037)

**Priority:** P1-depth  
**Status:** done
 
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §6; [project-build-logic.md](../features/project-build-logic.md) “Future”  
**Depends on:** 1037 (done)  
**Branch:** `ticket-mill-steal-p0-p1` (depth pass)

## Problem

MVP `.jk-build/` runs **one** main class as a single action-cached blob. Mill’s hatch value is
**named graph nodes** (inspectable, independently cached). Depth without full OO traits:

- multiple independent build-logic tasks per module  
- each task its own action-cache key  
- distinct pipeline labels (`build-logic:LineCount`, …)

## Goal

1. Discover every public class under the logic dir named `*Build` / `*BuildMain` (or
   `[build].logic-main` forces a single class — existing behavior).  
2. Compile once; **run each** main as a separate action-cached task  
   (`build-logic@…` + class simple name in key).  
3. Labels: `build-logic:<SimpleName>: cache hit | compile + run`.  
4. Docs + tests (two tasks, independent invalidation).

## Acceptance

- [x] Two `*Build` mains → two cache keys; independent labels  
- [x] `logic-main` still pins a single class  
- [x] project-build-logic.md updated  
- [x] unit tests (`BuildLogicSupportTest.two_build_mains_are_independently_cached`)  

## Shipped

- Compile once; run every `*Build`/`*BuildMain` as `build-logic-<SimpleName>` action-cached task  


## Non-goals

- Full `register(BuildGraph)` SPI / monorepo traits  
- Kotlin sources  
- `jk tasks` Mill-style resolver (1031 is module selectors only)  
