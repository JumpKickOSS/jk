# Contributing to jk

## Source headers

Every source file starts with an SPDX line:

```
// SPDX-License-Identifier: Apache-2.0
```

Use the comment syntax appropriate to the file type (`//`, `#`, `<!--`, …).

## Toolchain

Bootstrap pins (`.sdkmanrc`):

```
java=25.0.3-graal
gradle=9.5.1
```

With SDKMAN: `sdk env install && sdk env`. Otherwise Gradle can provision a JDK via the
foojay resolver on first use.

## Building

```bash
./gradlew classes
./gradlew :cli:installDist :engine:shadowJar   # thin JVM client + engine fat jar
./gradlew dist                                  # native client + engine jar → build/dist/
./install.sh build/dist/jk                      # optional local install
```

`dist` builds the slim GraalVM native `jk` client and the engine fat jar
(`lib/jk-engine-<version>.jar`). The engine runs as a normal JVM app on a
jk-managed JDK — never as a native image. `nativeCompile` needs a GraalVM-capable
JDK (the pin above qualifies).

Full `./gradlew build` hits Maven Central; avoid rate-limited environments for the
full suite.

### Self-host (phase 2) — workspace modules + thin workers with jk

The repo is a jk **workspace** (root `jk.toml` + per-module manifests under `shared/`,
`server/`, `clients/`, and thin workers under `plugins/test-runner` +
`plugins/java-compiler`). Those two workers package as **shadow jars** with
`Main-Class = PluginMain` (plugin-sdk shaded in). Other `plugins/*` stay Gradle-only
(fat workers + `installLocal`).

#### A) Native client bootstrap (CI default; needs GraalVM)

```bash
# 1) Produce a local JumpKick + side-load worker jars into ~/.jk/cache
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.jk/versions/0.10.0-SNAPSHOT/bin:$PATH"   # or your install layout

# 2) Lock + compile/package workspace modules (no Gradle for javac)
jk lock
jk build --skip-tests
```

#### B) Thin JVM client + engine jar (no Graal; dogfood without native-image)

```bash
# 1) Slim client installDist + server-only engine fat jar + worker jars
./gradlew :cli:installDist :engine:shadowJar installLocal --no-daemon
CLIENT_BIN="$PWD/clients/cli/build/install/jk/bin/jk"
ENGINE_JAR=$(ls "$PWD/server/engine/build/libs/jk-engine-"*.jar | head -1)
"$CLIENT_BIN" self materialize "$CLIENT_BIN" "$ENGINE_JAR"
export PATH="$PWD/clients/cli/build/install/jk/bin:$PATH"

# 2) Same dogfood as (A)
jk lock
jk build --skip-tests
```

The client never embeds the engine (ticket-1020). Spawning uses
`~/.jk/versions/<v>/lib/jk-engine.jar` or `JK_ENGINE_EXE`.

| Still Gradle | Why |
|---|---|
| `./gradlew test` (full suite) | CI source of truth for the unit/integration suite |
| `./gradlew dist` / `nativeCompile` | Native-image + fat engine jar packaging |
| `./gradlew installLocal` | Worker jars into `~/.jk/cache/repos/local/` (PluginJar.locate) |
| Most `plugins/*` (not test-runner / java-compiler) | Fat workers without workspace manifests yet |

#### Engine / CLI tests under self-host

`server/engine` declares `[build].test-plugin-jars`. When those names are **workspace
siblings** (today: `test-runner`, `java-compiler`), the test JVM gets
`-Djk.<worker>.plugin.jar` pointing at the **built shadow jar** under
`plugins/<name>/target/`. Other workers still resolve from `installLocal` / CAS.

CLI integration tests (`:cli:test`) spawn a real engine from `:engine:shadowJar` (materialized
into the test `JK_HOME`) — no in-process dual path (ticket-1020).

**Suite timing (order of magnitude, warm laptop):** `:cli:test` ≈ **7 minutes** after
ticket-1042 (hybrid warm engine; was ~10 minutes with stop-after-every-method). Full
`./gradlew test` is longer (engine + plugins). Use module filters mid-ticket
(`:cli:test --tests '…'`, `:engine:test`); re-run full `./gradlew test` before merge to
`main`. Shared dep cache: `jk.test.cache.dir` under `clients/cli/build/test-shared-cache`.

Prefer `jk build --skip-tests` for the documented dogfood path; keep
`./gradlew :engine:test` / `:cli:test` for the full nested suites (Gradle wires
worker jars via configurations).

Refresh locks after dependency changes: `jk lock` (commit the per-module `jk.lock` files).

### One build at a time per checkout

`settings.gradle.kts` takes an OS file lock (`.gradle/cross-daemon-build.lock`) so
two Gradle daemons do not corrupt shared test outputs. A second invocation waits
with a clear message. Use a separate worktree for true parallel builds.

## Project layout

| Path | Role |
|---|---|
| `shared/` | Client-safe modules (`jk-api`, `core`, `plugin-sdk`, `wire`, …) |
| `server/` | Engine-only (`engine`, `resolver`, `io`, `toolchain`) |
| `clients/` | `cli` (native/thin JVM client + CLI tests), `web` |
| `plugins/` | First-party build/worker plugins |

See [docs/architecture.md](docs/architecture.md) for layering and process model, and
[docs/guide.md](docs/guide.md) for product behavior.

## Docs and planning

- Public docs live under [`docs/`](docs/README.md) (keep the set small and accurate).
- Engineering board: [`docs/kanban/`](docs/kanban/backlog.md).

## License

Contributions are under the [Apache 2.0](LICENSE) license.
