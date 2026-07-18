# ticket-1018 — Self-host phase 2 (plugins + full dist on jk)

**Priority:** P1 (dogfood)  
**Status:** backlog  
**Depends on:** ticket-1007 phase 1 (**done**)  
**Branch:** `ticket-1018-self-host-phase2`

## Learned from 1007

Phase 1 restored a 12-module workspace and CI dogfood via `jk lock` + `jk build --skip-tests`
after a Gradle `dist`/`installLocal` bootstrap. Remaining friction:

| Gap | Detail |
|---|---|
| Plugins not on workspace | `plugins/*` still Gradle-only; no module `jk.toml` |
| Engine/cli-engine tests | Need worker jars in isolated caches (`test-plugin-jars` + installLocal) |
| installDist path | JVM dist script fails “could not resolve the running jk binary's path” |
| Native dist required | CI self-host job needs GraalVM for bootstrap binary |
| Full dist packaging | Engine fat jar + native client still Gradle-assembled |

## Goal

1. Add workspace modules (or documented subset) for first-party plugins needed for self-test
2. Make `jk build` (with tests) green for `server/engine` and `clients/cli-engine` without manual
   worker overrides where possible
3. Document or fix JVM installDist so CI can dogfood without Graal when desired
4. Staged plan toward packaging dist primarily with jk (native-image may stay special-cased)

## Acceptance (draft)

- [ ] At least one first-party plugin built as a workspace module via `jk build`
- [ ] Documented path for engine tests under self-host (or green `jk test` on engine)
- [ ] CONTRIBUTING updated; phase-1 CI job still green
