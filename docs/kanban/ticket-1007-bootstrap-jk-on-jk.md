# ticket-1007 — Bootstrap / CI builds jk with jk

**Priority:** P1 (dogfood)  
**Status:** done  
**Branch:** `ticket-1007-bootstrap-jk-on-jk`  
**Refs:** [CONTRIBUTING.md](../../CONTRIBUTING.md) (self-host section), [architecture.md](../architecture.md)
status, root `jk.toml`, `.github/workflows/ci.yml` `self-host` job

## Shipped (phase 1)

1. **Workspace restored** — 12 modules with parseable `jk.toml` (plugin-sdk → … → cli-engine)
2. **Dogfood path** — after Gradle `dist` + `installLocal` + `install.sh`:
   `jk lock` && `jk build --skip-tests`
3. **CI** — `self-host` job on ubuntu (GraalVM 25) runs that path; Gradle `test` job kept
4. **SelfHostingTomlTest** — asserts module list; no longer skips
5. **Supporting fixes** — `jk-api` depends on `plugin-sdk`; test resource copy for
   `src/test/resources`; baked built-in plugin manifests under `core` resources for self-host

## Acceptance

- [x] Root workspace + every listed module has parseable `jk.toml`
- [x] Contributor doc (CONTRIBUTING) with sequence + still-Gradle table
- [x] CI Linux job for `jk lock` + `jk build --skip-tests`
- [x] `./gradlew test` remains the primary suite
- [x] SelfHostingTomlTest un-skips

## Phase 2 (follow-up)

- Engine jar + CLI packaging primarily via jk
- `plugins/*` on the workspace
- Full `jk build` (with tests) for engine/cli-engine without Gradle worker install
