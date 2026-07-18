# ticket-1022 — @TempDir cleanup under wire-based CLI tests

**Priority:** P3  
**Status:** backlog  
**Depends on:** ticket-1021 (done)  
**Branch:** (none)

## Problem
`:cli:test` spawns a real engine. Even after `EngineClient.forceStop`, some project trees under
`@TempDir` still fail JUnit's default deletion strategy (CAS hardlinks / delayed unmap). The suite
uses `junit.jupiter.tempdir.cleanup.mode.default=never` so failures don't flake the build.

## Goal
Make default `@TempDir` cleanup safe without `cleanup.mode=never`, e.g.:
- Route action-cache / CAS for tests outside `@TempDir` (suite cache only)
- Or delay TempDir deletion until after engine stop with a custom TempDirFactory
- Or use soft-delete / ignore ENOTEMPTY for known CAS layouts

## Acceptance
- [ ] No `tempdir.cleanup.mode=never` on `:cli:test`
- [ ] `./gradlew :cli:test` green with default TempDir cleanup
- [ ] No leftover engine processes after the suite

## Non-goals
- Restoring InProcessEngine
