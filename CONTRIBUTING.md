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
