# ticket-1022 — @TempDir cleanup under wire-based CLI tests

**Priority:** P3  
**Status:** backlog (refined)  
**Kind:** go-do  
**Depends on:** ticket-1020, ticket-1021 (**done**)  
**Branch:** `ticket-1022-tempdir-cleanup`  
**Estimate:** S–M (1 day)  
**Refs:** `clients/cli/build.gradle.kts` (`junit.jupiter.tempdir.cleanup.mode.default=never`),
`EngineTestSupport`, `EngineTestExtension`, suite shared cache dir

## Problem

`:cli:test` spawns a real engine. After `forceStop`, JUnit’s default `@TempDir` deletion still races
with CAS hardlinks / late unmap under the project tree. The suite sets:

```text
junit.jupiter.tempdir.cleanup.mode.default=never
```

so the build stays green but **leaks temp dirs** and hides real cleanup bugs.

Partial mitigations already landed:

- Short `JK_STATE_DIR` under `/tmp/jk-cli-*` (ticket-1021)  
- `EngineTestSupport.stopEngineAndRelease` + `UncheckedIOException` swallow on walk  
- Shared `jk.test.cache.dir` outside `@TempDir` for some paths  

Still not enough to drop `cleanup.mode=never`.

## Goal

Remove `tempdir.cleanup.mode.default=never` from `:cli:test` while keeping the suite green and
engine-free after the run.

## Approaches (pick one primary; document choice in PR)

| Approach | Idea |
|---|---|
| **A. Cache outside TempDir** | Guarantee all CAS/action-cache for tests use `jk.test.cache.dir` / `--cache-dir`, never under `@TempDir` |
| **B. Custom TempDirFactory** | AfterEach: stop engine → GC/sleep brief → delete with retry; only then release dir |
| **C. Soft ignore** | Keep never-delete but add suite `@AfterAll` sweeper for `/tmp/jk-cli-*` and junit dirs older than N hours — **weaker**, last resort |

**Prefer A + B together:** no CAS under TempDir; factory retries deletion after engine stop.

## Acceptance

- [ ] No `tempdir.cleanup.mode.default=never` on `:cli:test`  
- [ ] `./gradlew :cli:test` green with default TempDir cleanup  
- [ ] No leftover `jk-engine` / `EngineMain` processes after the suite (spot-check)  
- [ ] Comment in `build.gradle.kts` removed or replaced with the real strategy  

## Non-goals

- Restoring InProcessEngine on production classpath  
- Fixing macOS UDS path limits (already handled via shortTempDir / `/tmp`)  

## Ready criteria

- Pull when flaky disk full / TempDir pollution becomes painful; otherwise after P2 showcase  
