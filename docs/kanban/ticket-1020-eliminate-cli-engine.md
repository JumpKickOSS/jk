# ticket-1020 — Eliminate `:cli-engine`; strict client ↔ server wire separation

**Priority:** P1 (architecture / anti-goal)  
**Status:** done  
**Depends on:** none hard; benefits from 1014 (`IdeEngineClient` already wire-first)  
**Branch:** `ticket-1020-eliminate-cli-engine`  
**Anti-goal:** A module (or fat jar) that links **client CLI code and the build engine** in one process for production.

## Problem

`:cli-engine` (`clients/cli-engine`) is a hybrid that should not exist:

| What it is today | Why that’s wrong |
|---|---|
| Gradle module under `clients/` | Hosts **engine** entrypoint (`EngineMain`) — not a client |
| `implementation(project(":cli"))` **and** `implementation(project(":engine"))` | Engine jar **embeds the CLI**; layering is inverted |
| Produces `jk-engine-*.jar` via shadowJar | Production engine artifact is assembled from a *client* module |
| `InProcessEngine` / `InProcessEngineImpl` ServiceLoader | Parallel API surface that bypasses the wire for tests + JVM dist FALLBACK |
| Hosts ~17k LOC of **CLI** tests | “Engine module” is really “CLI test classpath with engine on the side” |

Stage 5 correctly slimmed the **native** client (`:cli` → wire + client-safe modules only). The residual hybrid undoes that for the **engine artifact** and for the JVM installDist path: one classpath still glues both halves.

User intent: **strict wire-only separation**. Clients (CLI, IDE, web) speak JSONL over UDS/TCP. The server is `server/engine` (+ server deps). Combining them is an **anti-goal**, not a convenience.

## Research snapshot (current tree)

### Modules and edges

```
:cli  (native Graal client)
  → jk-api, core, client-io, toolchain-jdk, wire, plugin-sdk
  → NO :engine, :io, :resolver, :toolchain   ✓ Stage 5

:cli-engine  (hybrid — delete)
  → :cli + :engine + full server stack
  → application main = cc.jumpkick.cli.Jk   (client!)
  → image / shadow main = cc.jumpkick.cli.EngineMain
  → META-INF/services → InProcessEngineImpl

:engine  (server/engine)
  → wire, core, io, resolver, toolchain, plugin-sdk, web (runtime)
  → NO :cli today   ✓
  → No `main` / application plugin yet
```

### Production paths that depend on the hybrid

1. **`./gradlew dist`** — `build.gradle.kts` copies `:cli-engine:shadowJar` → `lib/jk-engine-*.jar`. Spawn line is  
   `java -cp …/jk-engine.jar cc.jumpkick.cli.EngineMain`  
   (`EngineDelegate` hardcodes that class name for `--job` children.)

2. **`./gradlew :cli-engine:installDist`** — monolythic JVM “jk”: client verbs + `--engine-server` via `InProcessEngine` on the **same** classpath (FALLBACK when no versioned engine jar). Self-host CI job `self-host-jvm` uses this.

3. **`EngineMain`** lives in `clients/cli-engine/.../cli/EngineMain.java` (~180 LOC): detaches session (`PosixDetach`), ignores SIGINT/HUP (JLine), constructs `EngineServer`, job/AOT-training modes. Only needs engine + a version string + detach helper — **not** the CLI command tree.

4. **`InProcessEngine`** (`clients/cli/.../InProcessEngine.java`, ~450 LOC interface) + **`InProcessEngineImpl`** (~1.3k LOC) mirror nearly every wire verb as in-process methods. Used by:
   - ~36 CLI sources when `jk.test.noEngine` / installDist FALLBACK / `jk --engine-server` on monolyth classpath
   - `IdeEngineClient` in-process branch
   - Essentially the entire `:cli-engine` test suite (command tests need the kernel without spawning)

### What is *not* wrong

- Slim `:cli` depending only on client-safe + `wire` modules.
- `EngineClient` / `IdeEngineClient` over the frozen wire protocol.
- Engine depending on `server/*` and serving HTTP dashboard assets from `:web` **runtime**.

The bug is the **third module that links both**.

## Goal (end state)

1. **No `:cli-engine` module** (directory, Gradle project, workspace member, CI job names retired).
2. **`main` for the engine JVM lives in `server/engine`** (e.g. `cc.jumpkick.engine.EngineMain` or keep simple name under `engine` package — **not** under `cc.jumpkick.cli`).
3. **`jk-engine-*.jar` is built from `:engine`** (shadow/application), with classpath = server stack only — **zero** `clients/cli` classes.
4. **Clients never compile or runtime-link `:engine`** (native image and JVM client classpaths stay Stage 5).
5. **Production code path is wire-only**: no ServiceLoader engine seam in shipped client or shipped engine jar.
6. **Docs / CONTRIBUTING / architecture / dist / self-host** describe two artifacts only: native (or thin JVM) **client** + **engine** jar; installDist is engine-or-client, not a franken-binary.

## Non-goals (this ticket)

- Rewriting the wire protocol (1001 freeze stands).
- Marketplace IDE plugins (1017) — they consume the wire facade; cleaner split helps them.
- Moving remaining fat plugins onto the workspace (post-1018 residual).
- Making engine a native image (explicitly never).

## Design decisions (recommended defaults)

| Decision | Recommendation | Rationale |
|---|---|---|
| Fate of `InProcessEngine` | **Delete** from production; do not ship in either artifact | Dual API is the glue that forces `:cli`↔`:engine` |
| CLI unit tests that only need parser/TUI | Move/keep under `:cli` with **no** engine on classpath | Fast, honest Stage 5 tests |
| CLI integration tests that need a real build | Spawn **real engine process** (UDS/TCP) against a test `jk-engine` jar or `EngineServer` test harness in `:engine` | Same path as production |
| Engine-only tests | Stay in `:engine` (already many); own `application` + worker jar props | No client packages |
| JVM “installDist” dogfood | **Engine application** under `:engine` + native/thin client separately; drop monolyth that runs `Jk` + engine on one CP | Aligns with anti-goal; self-host uses client→spawn engine jar |
| `jk --engine-server` on client binary | Only meaningful if client has engine on CP — **remove** from slim client; engine is started only as engine main / managed spawn | FALLBACK that re-execs client-as-engine goes away |
| Version string in engine main | Use `JkVersion` / shared constant already on engine side — not `Jk.VERSION` from CLI | Drop CLI dependency |
| Package rename | `cc.jumpkick.cli.EngineMain` → `cc.jumpkick.engine.EngineMain` (or `…engine.Main`); update spawners, docs, AOT trainer argv | Correct layering |

Open only if implementer discovers a hard constraint: **temporary** test-only module (e.g. `:cli-integration-tests`) that depends on both — allowed **only** as a non-shipped test project, never as the engine fat jar source. Prefer process-level integration tests instead.

## Implementation sketch (PR-sized slices)

Suggested order (each mergeable if tests stay green):

1. **Move `EngineMain` + `PosixDetach` into `server/engine`**; point `EngineDelegate` / spawn lines at new FQCN; keep old class as deprecated one-liner redirect **or** dual-main briefly if needed for rolling dist.
2. **Shadow/application plugin on `:engine`**; root `dist` task consumes `:engine:shadowJar` (or equivalent); drop `:cli-engine` from dist.
3. **Strip `:cli` from any engine packaging path**; assert (ArchUnit or Gradle check) that `:engine` runtime classpath does not contain `cc.jumpkick.cli.**` command packages.
4. **Retire `InProcessEngine` production use**: client always uses `EngineClient`; IDE facade always wire (1014 already prefers that); tests migrate off `jk.test.noEngine` where they exercise engine behavior.
5. **Relocate CLI tests**: pure client → `:cli/src/test`; engine kernel → `:engine`; cross-process integration → dedicated test source set that spawns engine.
6. **Delete `clients/cli-engine`**, workspace entry, settings include, CI `installDist` job rewritten to engine app + client, CONTRIBUTING/architecture tables.
7. **Self-host dogfood**: `jk` client (native or thin) + materialized `jk-engine.jar` only — no monolyth classpath.

## Acceptance

- [x] No Gradle project `:cli-engine` / no `clients/cli-engine` tree
- [x] Engine JVM entrypoint lives under `server/engine` and is the `Main-Class` of `jk-engine-*.jar`
- [x] Production engine fat jar contains **no** `cc.jumpkick.cli.command` / CLI TUI packages (server-only packaging; no `:cli` dep)
- [x] `:cli` still has **no** compile/runtime dependency on `:engine` / `:io` / `:resolver` / `:toolchain` (testImplementation of `:engine` only for EngineClientTest)
- [x] Production client path does not use `InProcessEngine` ServiceLoader (interface deleted)
- [x] `./gradlew dist` consumes `:engine:shadowJar`; spawn FQCN `cc.jumpkick.engine.EngineMain`
- [x] `./gradlew test` green; self-host CI uses thin client + engine jar (phase A/B)
- [x] architecture.md + CONTRIBUTING + AGENTS describe wire-only separation; `cli-engine` wording gone

## Phases C–E notes (shipped)

- Stripped all `engineDisabledForTests` / `InProcessEngine` dual path from `clients/cli/src/main`
- Migrated CLI tests to `:cli` with `EngineTestSupport` materializing `:engine:shadowJar` into test `JK_HOME`
- Short `JK_STATE_DIR` for UDS; forward `jk.*.jar` props into engine spawn; no `jk.test.noEngine`
- Residual fixture debt → [ticket-1021](ticket-1021-cli-wire-test-fixture.md)

## Risks

| Risk | Mitigation |
|---|---|
| Huge CLI test suite assumes in-process engine | Migrate in batches; process spawn harness; keep `:cli` unit tests engine-free |
| CI time (real engine spawns) | Shared test engine fixture / reuse EngineServer in-JVM **from engine tests only**, not from CLI packaging |
| installDist DX for contributors without Graal | Document engine `installDist`/`run` + `JK_ENGINE_EXE` / version materialize; Temurin-only path still viable |
| Native image size regression | Must not re-link engine into `:cli` — ArchUnit/dep check in CI |

## References

- Stage 5 comments: `clients/cli/build.gradle.kts`, `clients/cli-engine/build.gradle.kts`
- Entry: `clients/cli-engine/.../EngineMain.java` → target `server/engine`
- Seam: `clients/cli/.../InProcessEngine.java` + `.../InProcessEngineImpl.java`
- Dist: root `build.gradle.kts` (`dist` Sync from `:cli-engine:shadowJar`)
- Spawn FQCN: `server/engine/.../EngineDelegate.java` (`cc.jumpkick.cli.EngineMain`)
- Related done: ticket-1001 (wire freeze), ticket-1014 (IDE facade over wire)
