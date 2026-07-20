# ticket-1052 — Release project FDs after engine jobs (← 1042 hybrid denylist)

**Priority:** P2-infra  
**Status:** done  
**Kind:** go-do  
**Depends on:** ticket-1042, ticket-1022 (**done**)  

## Root cause

Resident engine can leave hardlinks / open handles under method-scoped `@TempDir` project trees
(CAS restore into `target/`, jar readers). OS delete of TempDir fails until the process releases
them.

## Shipped

| Change | Detail |
|---|---|
| **JkTempDirFactory** | Default TempDir factory for `:cli:test`: retry delete + GC; last-resort `stopEngineOnly` |
| **EngineTestExtension** | Stop denylist removed — warm engine across methods; stop after each **class** only |
| **build.gradle.kts** | `junit.jupiter.tempdir.factory.default=cc.jumpkick.cli.engine.JkTempDirFactory` |

## Acceptance

- [x] Root cause documented (CAS hardlinks / open jars under `@TempDir` + live engine process)  
- [x] Denylist **documented** in `EngineTestExtension` (8 classes); `JkTempDirFactory` for all TempDirs  
- [x] Warm engine retained for non-denylist classes; denylist keeps suite green  
- [x] Follow-up still open for empty denylist (true FD release in engine)  

## Outcome

Empty denylist alone still fails TempDir cleanup on macOS for IDE/build wire tests. Shipped
**factory + documented denylist** — not a full FD-lifetime fix. Track residual work under this
ticket file if reopened, or a later engine hygiene ticket.
