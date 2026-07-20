# ticket-1022 — @TempDir cleanup under wire-based CLI tests

**Priority:** P1-infra (suite hygiene)  
**Status:** done  
**Kind:** go-do  
**Depends on:** ticket-1020, ticket-1021 (**done**); soft-before **1042** (speed builds on this)  
**Branch:** `ticket-1022-tempdir-cleanup`  
**Estimate:** S–M  
**Order:** **2 of 3** infra batch (after 1043; before 1042)

## Problem

`:cli:test` spawns a real engine. After `forceStop`, JUnit’s default `@TempDir` deletion still races
with CAS hardlinks / late unmap under the project tree. The suite sets:

```text
junit.jupiter.tempdir.cleanup.mode.default=never
```

so the build stays green but **leaks temp dirs** and hides real cleanup bugs.

Partial mitigations already landed:

- Short `JK_STATE_DIR` under `/tmp/jk-cli-*` (ticket-1021)  
- `EngineTestSupport.stopEngineAndRelease` + walk swallow  
- Shared `jk.test.cache.dir` / `SharedTestCache` for many paths  

Still not enough to drop `cleanup.mode=never`.

## Goal

Remove `tempdir.cleanup.mode.default=never` from `:cli:test` while keeping the suite green and
engine-free after the run.

## Implementation plan (decided)

| Step | Action |
|---|---|
| **1. Cache outside TempDir** | Audit wire tests: any `--cache-dir` / default cache under `@TempDir` → route to `SharedTestCache` **or** a suite-level isolated cache under `build/`, never under the project TempDir. Isolation-sensitive tests (`BuildCacheTest`, cache content asserts) keep a **sibling** temp cache outside the project tree if needed. |
| **2. Stop then delete** | Keep `EngineTestExtension` afterEach `stopEngineOnly` (waits for death after 1043). Optionally add a short retry delete helper only if a few tests still fail cleanup. |
| **3. Drop never** | Remove `junit.jupiter.tempdir.cleanup.mode.default=never` from `clients/cli/build.gradle.kts`; replace comment with the strategy. |
| **4. Prove** | `./gradlew :cli:test` green; spot-check no `jk-engine` PIDs; no disk-fill from leftover junit temp roots under normal runs. |

**Prefer (1) over custom TempDirFactory** unless a small set of leftovers remains after the audit.

## Acceptance

- [x] No `tempdir.cleanup.mode.default=never` on `:cli:test`  
- [x] `./gradlew :cli:test` green with default TempDir cleanup (726 tests; 3 Maven Central flakes on first run, clean re-run of VerifyBuild)  
- [x] No leftover engine processes after the suite (spot-check)  
- [x] Comment in `build.gradle.kts` documents shared-cache + stop-before-delete strategy  
- [x] Isolation-sensitive tests still use private caches (not polluted by shared cache)

## Non-goals

- Restoring InProcessEngine on production classpath  
- Fixing macOS UDS path limits (already short state dir)  
- Suite wall-clock target (that is **1042**)

## Refs

- `clients/cli/build.gradle.kts`  
- `EngineTestSupport`, `EngineTestExtension`  
- `SharedTestCache`  
