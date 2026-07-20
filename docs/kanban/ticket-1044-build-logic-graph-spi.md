# ticket-1044 — Build-logic graph SPI (depth of 1037 / 1039)

**Priority:** P3 (product depth)  
**Status:** backlog  
**Kind:** go-do  
**Source:** [project-build-logic.md](../features/project-build-logic.md) Future; mill-comparison §6; 1037/1039 non-goals  
**Depends on:** 1037, 1039 (done)  
**Branch:** `ticket-1044-build-logic-spi`  

## Problem

MVP `.jk-build/` runs discovered `*Build` mains as independently action-cached tasks. Mill’s bar is
**graph-native** programmability: register named tasks, splice at pipeline anchors, share traits
across modules, surface in explain/selective.

## Goal (phased)

### Phase A — SPI + anchors

- Public API (shared/plugin-sdk or small `jk-build-api` jar on logic compile classpath) e.g.
  `JkBuildLogic.register(BuildLogicGraph)`  
- Anchors: at least `afterResources` (today’s behavior) + one more (`beforePackage` or `afterCompile`)  
- Tasks appear as named steps in pipeline labels / explain when free  

### Phase B — Workspace reuse

- Shared logic dir or workspace-level pointer (data-only in root `jk.toml`)  
- No monorepo trait language required in B if a shared jar/path is enough  

### Phase C — Kotlin sources in `.jk-build/`

- Compile `.kt` with existing kotlin-compiler worker path  

## Acceptance (Phase A minimum for “done”)

- [ ] SPI documented; sample migrates from pure-main to SPI **or** both supported  
- [ ] ≥2 anchors with tests  
- [ ] `jk.toml` still data-only (path / feature flag only)  
- [ ] Guide + project-build-logic.md Future section updated  

## Non-goals

- Scripts inside `jk.toml`  
- Full Mill OO `extends JavaModule` trait system in one ticket  
- Marketplace of hatch libraries  

## Refs

- `BuildLogicSupport`, ticket-1026 design, ticket-1039 multi-task  
