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
./gradlew :cli-engine:installDist   # JVM dist → cli-engine/build/install/
./gradlew dist                      # native client + engine jar → build/dist/
./install.sh build/dist/jk          # optional local install
```

`dist` builds the slim GraalVM native `jk` client and the engine fat jar
(`lib/jk-engine-<version>.jar`). The engine runs as a normal JVM app on a
jk-managed JDK — never as a native image. `nativeCompile` needs a GraalVM-capable
JDK (the pin above qualifies).

Full `./gradlew build` hits Maven Central; avoid rate-limited environments for the
full suite.

### Self-host (phase 1) — build workspace modules with jk

The repo is a jk **workspace** (root `jk.toml` + per-module manifests under `shared/`,
`server/`, `clients/`). First-party **plugins** stay Gradle-only for now (worker packaging).

**Bootstrap once with Gradle** (needs GraalVM for the native client), then dogfood:

```bash
# 1) Produce a local JumpKick + side-load worker jars
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.jk/versions/0.10.0-SNAPSHOT/bin:$PATH"   # or your install layout

# 2) Lock + compile/package the 12 workspace modules (no Gradle for javac)
jk lock
jk build --skip-tests
```

| Still Gradle | Why |
|---|---|
| `./gradlew test` (full suite) | CI source of truth until phase 2 |
| `./gradlew dist` / `nativeCompile` | Native-image + fat engine jar packaging |
| `./gradlew installLocal` | Plugin/worker jars into local Maven layout |
| `plugins/*` modules | No workspace `jk.toml` yet |

`jk build` (with tests) works for most library modules; `server/engine` and
`clients/cli-engine` integration tests need worker jars resolved into isolated test
caches (`[build].test-plugin-jars` + `installLocal`). Prefer `--skip-tests` for the
documented dogfood path; keep `./gradlew :engine:test` for those suites.

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
| `clients/` | `cli` (native), `cli-engine` (engine jar + tests), `web` |
| `plugins/` | First-party build/worker plugins |

See [docs/architecture.md](docs/architecture.md) for layering and process model, and
[docs/guide.md](docs/guide.md) for product behavior.

## Docs and planning

- Public docs live under [`docs/`](docs/README.md) (keep the set small and accurate).
- Engineering board: [`docs/kanban/`](docs/kanban/backlog.md).

## License

Contributions are under the [Apache 2.0](LICENSE) license.
