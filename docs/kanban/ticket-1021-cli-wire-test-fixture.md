# ticket-1021 — CLI wire-test fixture hardening (post-1020)

**Priority:** P2  
**Status:** done  
**Depends on:** ticket-1020 (done)  
**Branch:** `ticket-1021-cli-wire-test-fixture`

## Problem
After eliminating `:cli-engine` and the in-process dual path, `:cli:test` spawns a real
engine from `:engine:shadowJar`. That works but has residual test-harness debt:

1. **UDS path length** — long worktree `JK_HOME/.../state/engine/*.sock` paths overflow
   Linux `sun_path` (~108 bytes). Mitigated today with a fixed `JK_STATE_DIR=/tmp/jk-cli-test-state`.
2. **@TempDir cleanup** — the resident engine can hold CAS files under a test's
   `--cache-dir` inside `@TempDir`; suite uses `junit.jupiter.tempdir.cleanup.mode.default=never`.
3. **Suite-scoped engine lifecycle** — every first spawn pays AOT train; no suite-level
   drain/stop between classes; shared `/tmp` state can leak across concurrent checkouts.
4. **Plugin jar property forwarding** — `EngineClient` forwards `jk.*.jar` system properties
   into the engine JVM; still no automatic `installLocal` fallback for self-host dogfood without Gradle.

## Goal
- Short, unique-per-suite state dir (or TCP loopback in tests) without a hard-coded `/tmp` path.
- Clean `@TempDir` cleanup (engine closes handles or tests use a suite cache outside temp).
- Optional suite-scoped `EngineServer` / process fixture with deterministic shutdown.
- Document / automate worker jar materialization for pure-jk test runs.

## Non-goals
- Restoring `InProcessEngine` dual path.
- Native engine image.

## Acceptance
- [x] No hard-coded `/tmp/jk-cli-test-state` (unique `/tmp/jk-cli-<run-id>` per test task)
- [x] Engine force-stopped after each test + suite end (no leftover processes)
- [~] `@TempDir` default cleanup — **not** fully restored: CAS hardlinks still race deletion even after forceStop; keep `cleanup.mode=never` (failure-proof). Track pure fix as residual if needed.
- [x] `./gradlew :cli:test` green

## Done
- `EngineTestExtension` BeforeAll materialize + AfterEach/AfterAll `forceStop` / hardKill
- Unique short `JK_STATE_DIR` under `/tmp/jk-cli-*` per Gradle test task; `maxParallelForks=1`
- `tempdir.cleanup.mode=never` retained intentionally (see acceptance)
- Worker jars still wired via `-Djk.*.plugin.jar` from Gradle (self-host without Gradle remains installLocal — see CONTRIBUTING)

## References
- `clients/cli/build.gradle.kts` test task env/system properties
- `clients/cli/src/test/java/cc/jumpkick/cli/engine/EngineTestSupport.java`
- `EngineClient` spawn plugin-jar property forwarding
