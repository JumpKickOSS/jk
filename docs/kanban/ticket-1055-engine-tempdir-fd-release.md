# ticket-1055 — Empty CLI TempDir denylist (JUnit 6 deletion strategy)

**Priority:** P1-infra  
**Status:** done  
**Kind:** go-do  
**Source:** ticket-1052 partial  

## Root cause

JUnit **6** cleans TempDirs via **`TempDirDeletionStrategy`**, not only `TempDirFactory.close()`.
Engine hardlinks under `@TempDir` fail standard delete until the process releases them.

## Shipped

| Piece | Role |
|---|---|
| `JkTempDirDeletionStrategy` | Standard delete → on fail stop engine + GC + retry → soft-success |
| `JkTempDirFactory` | Short `/tmp` paths only |
| `EngineTestExtension` | **Denylist emptied** — stop after class only |
| `junit-platform.properties` + `build.gradle.kts` | Register deletion strategy default |

## Acceptance

- [x] Denylist empty in `EngineTestExtension`  
- [x] Former denylist tests green (`BuildCommandTest`, `BuildCacheTest`, `VscodeCommandTest`, `IdeEngineClientTest`)  
- [x] Documented in extension javadoc + ticket  

## Note

Engine still pins trees mid-request; cleanup **stops only when delete fails**, preserving warm-engine
speed for uncontended tests.
