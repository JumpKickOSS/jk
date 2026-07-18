# ticket-1007 — Bootstrap / CI builds jk with jk

**Priority:** P1 (dogfood)  
**Status:** ready  
**Branch:** `ticket-1007-bootstrap-jk-on-jk`  
**Refs:** [guide.md](../guide.md) (self-hosting / engine), [architecture.md](../architecture.md)
(modules + status note), root Gradle multi-module layout, `.github/workflows/ci.yml`,
`AGENTS.md` reinstall path (`./gradlew dist installLocal`)

## Problem

jk claims self-hosting, but the **shippable** and **CI** path is still `./gradlew test` /
`./gradlew dist`. This open-source checkout currently has **no root workspace `jk.toml`**
(`SelfHostingTomlTest` skips when absent). Dual toolchains (Gradle for jk itself + jk for
user projects) and Gradle’s one-build-per-checkout lock are ongoing friction.

## Current state (facts)

| Path | Tool | Notes |
|---|---|---|
| Unit/integration CI | `./gradlew test` | Only job in `ci.yml` today |
| Shippable dist | `./gradlew dist` | Native CLI + engine fat jar |
| Local reinstall | `dist` + `installLocal` + `install.sh` | Documented in AGENTS.md |
| Self-host manifests | Missing in this repo | Historical private tree had full workspace modules |

## Goal (phased — ship phase 1 first)

### Phase 1 — MVP (this ticket)

1. **Restore a workspace `jk.toml` graph** for modules that already map cleanly onto jk
   (at least: `shared/jk-api`, `shared/core`, `shared/plugin-sdk`, `shared/wire`, and as many
   server/client modules as parse without inventing new features). Exact module list is
   negotiated against `settings.gradle.kts` — document drops with reasons.
2. **Documented command sequence** (guide or CONTRIBUTING, no new product doc file) that a
   contributor can run to:
   - `jk lock` / `jk build` the workspace (or listed modules) for **Java compile + test** of
     those modules **without** invoking Gradle for that compile step
   - still use Gradle **only** where unavoidable (call out: Graal `nativeCompile`, fat-jar
     shadow assembly, multi-module packaging parity)
3. **CI job** on Linux that runs the documented jk path for the restored modules (at least
   lock + build or test of the workspace subset) **in addition to** (not instead of)
   `./gradlew test` until phase 2.

### Phase 2 — follow-up (out of MVP; open new ticket when ready)

- Engine jar + slim CLI package produced primarily by jk
- Gradle reduced to native-image / last-mile packaging only
- Optional: drop or gate full Gradle test once dogfood is trusted

## Scope / non-goals (phase 1)

**In**

- Workspace manifests + lockfiles committed or generated in CI with a clear refresh rule
- Docs: honest “what jk builds vs what still needs Gradle”
- Green CI step on Linux for the jk subset

**Out**

- Full `dist` parity on day one
- Windows bootstrap CI (see ticket-1011)
- Replacing plugin packaging / `installLocal` without a separate design
- Perfect module-for-module Gradle replacement

## Dependencies

- None blocking (1001 wire freeze done). Private plugins (1010) are independent.
- If a module cannot declare itself in `jk.toml` yet (missing feature), leave it on Gradle and
  list it under “still Gradle” in the doc — do not block the whole ticket.

## Acceptance (phase 1)

- [ ] Root (or documented entry) `jk.toml` workspace lists a real module set; every listed
      module has a parseable `jk.toml`
- [ ] Contributor doc: sequence to build/test that set with `jk` (and what still needs Gradle)
- [ ] CI workflow step on `ubuntu-latest` runs that sequence successfully on main
- [ ] `./gradlew test` remains green (do not regress the full suite)
- [ ] `SelfHostingTomlTest` un-skips (or is replaced) once workspace root exists

## Suggested implementation order

1. Draft module list vs `settings.gradle.kts`; add minimal `jk.toml`s
2. `jk lock` + fix resolve issues (BOMs, processors, multi-module edges)
3. Get `jk build` / `jk test` green for the subset locally
4. Wire CI job; keep Gradle test job
5. Document + un-skip self-hosting tests
