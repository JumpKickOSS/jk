# ticket-1052 — Release project FDs after engine jobs (← 1042 hybrid denylist)

**Priority:** P2-infra  
**Status:** backlog  
**Kind:** go-do  
**Source:** ticket-1042 hybrid warm-engine denylist  
**Depends on:** ticket-1042, ticket-1022 (**done**)  
**Branch:** `ticket-1052-engine-release-fds`  
**Estimate:** M  

## Problem

`:cli:test` reuses a warm engine across methods for most classes (~29% faster). Eight
classes still **force-stop after every method** because the resident engine keeps
file handles (or CAS links) under method-scoped `@TempDir` trees, so JUnit’s default
TempDir cleanup throws `TempDirDeletionException`.

Denylist today (`EngineTestExtension.STOP_ENGINE_AFTER_EACH`):

- `BuildCommandTest`, `BuildCacheTest`, `InstallAndBuildTest`, `ReadSideIntegrationTest`  
- `VscodeCommandTest`, `IdeCommandTest`, `IdeIdeaGenerationTest`, `IdeEngineClientTest`  

## Goal

Close / release project-tree FDs when a wire job finishes so **class-scoped warm engine**
works for all CLI wire tests without per-method stop. Then shrink or delete the denylist
and re-measure `:cli:test` wall-clock.

## Approaches

| Approach | Idea |
|---|---|
| **A. Engine cleanup** | After each request/pipeline, close open channels, unmap, clear path caches under the project root |
| **B. CAS design** | Prefer cache-dir hardlinks that never pin the project tree after the job returns |
| **C. Test fixture** | Class-scoped `@TempDir` fields for the denylist classes only (smaller win, not root cause) |

Prefer A (+ B if needed). C is a stopgap only.

## Acceptance

- [ ] Root cause identified (which handles / paths stay open)  
- [ ] Denylist empty **or** reduced to a documented exception with a ticket link  
- [ ] `./gradlew :cli:test` green with default TempDir cleanup  
- [ ] New `:cli:test` wall-clock recorded on the ticket  

## Non-goals

- Restoring `cleanup.mode=never`  
- In-process engine on production CLI classpath  
