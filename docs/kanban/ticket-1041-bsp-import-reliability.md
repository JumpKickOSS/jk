# ticket-1041 — BSP import reliability (depth of 1028)

**Priority:** P1-depth  
**Status:** done
 
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §7  
**Depends on:** 1028 (done)  
**Branch:** `ticket-mill-steal-p0-p1` (depth pass)

## Problem

MVP BSP lists targets and can compile “the project,” but import reliability is thin:

- dependency modules attached only to `#root`  
- compile ignores target URI (always whole project)  
- no reload; limited source roots (no test)  
- weak failure surface for IDEs  

## Goal

1. **Per-target** `buildTarget/dependencyModules` (and sources) for workspace modules.  
2. **Compile** resolves target URI → module dir; builds that module (or workspace root).  
3. **`workspace/reload`** re-reads project model.  
4. Source roots include `src/test/java|kotlin` (kind=2 test where easy).  
5. Compile result includes message on failure when available.  
6. Framing tests extended for multi-module targets + compile target id.

## Acceptance

- [x] Per-target dependencyModules + sources (incl. test roots)  
- [x] Compile resolves `#name` target URI → `IdeEngineClient.buildModule`  
- [x] `workspace/reload` invalidates cached model  
- [x] Framing URI extract unit test  

## Shipped

- `BspServer` depth + `IdeEngineClient.buildModule`

## Non-goals

- Marketplace plugins (1017)  
- Full test/run BSP providers  
- Metals/Scala  
