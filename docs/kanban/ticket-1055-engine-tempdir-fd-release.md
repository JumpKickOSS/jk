# ticket-1055 — True engine FD release (empty CLI TempDir denylist)

**Priority:** P3-infra  
**Status:** backlog  
**Kind:** go-do  
**Source:** ticket-1052 partial (denylist remains for 8 classes)  
**Depends on:** ticket-1052  

## Problem

Even with `JkTempDirFactory` retry, eight wire-test classes must stop the engine after every
method or `@TempDir` cleanup fails (CAS hardlinks / open jars under the project tree).

## Goal

Engine releases all project-tree handles when a request finishes so `STOP_ENGINE_AFTER_EACH` can
be emptied while TempDir cleanup stays green.

## Acceptance

- [ ] Denylist empty in `EngineTestExtension`  
- [ ] `./gradlew :cli:test` green  
- [ ] Document what was held open  
