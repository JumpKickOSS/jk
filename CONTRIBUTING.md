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

### Black-box examples (sibling repo)

End-to-end scenarios and early-adopter samples live in **[jkbuild/jk-examples](https://github.com/jkbuild/jk-examples)** (checkout next to this repo as `../jk-examples`). After product changes to lock/resolve/packaging/plugins/workspaces, reinstall local jk and run the relevant scenarios there (`jk lock && jk build && jk test`). They are the out-of-tree acceptance surface, not a replacement for `./gradlew test`.

`dist` builds the slim GraalVM native `jk` client and the engine fat jar
(`lib/jk-engine-<version>.jar`). The engine runs as a normal JVM app on a
jk-managed JDK — never as a native image. `nativeCompile` needs a GraalVM-capable
JDK (the pin above qualifies).

Full `./gradlew build` hits Maven Central; avoid rate-limited environments for the
full suite.

### Self-host (phase 2+) — workspace modules + thin workers with jk

Long-form dogfood and the `jk-jk` worktree: **[docs/self-host.md](docs/self-host.md)**.
Bootstrap helper: `./scripts/bootstrap-from-gradle.sh`.

The repo is a jk **workspace** (root `jk.toml` + per-module manifests under `shared/`,
`server/`, `clients/`, and all first-party `plugins/*`). `clients/web` is a resources module;
`server/engine` packages as an **assembly** jar (fat) including the web SPA. Workers package as
**assembly jars** with `Main-Class = PluginMain`. Side-load with `jk plugin install-local`.

#### A) Native client bootstrap (CI default; needs GraalVM)

```bash
# 1) Produce a local JumpKick + side-load worker jars into ~/.cache/jk
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.local/bin:$PATH"   # install.sh default; or versions/<v>/bin

# 2) Lock + compile/package + curated tests + ship layout (no Gradle for javac)
jk lock
jk build --skip-tests
jk plugin install-local
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*'
jk release --skip-tests
```

#### B) Thin JVM client + engine jar (no Graal; dogfood without native-image)

```bash
# 1) Slim client + workers + engine materialize + daemon bounce (JK-1194)
./gradlew :cli:installDist installLocal --no-daemon
# Root installLocal side-loads every plugin worker, then :engine:installLocal
# (shadowJar → jk self materialize → engine stop/start).
export PATH="$PWD/clients/cli/build/install/jk/bin:$PATH"

# Engine-only refresh after an engine code change:
# ./gradlew :cli:installDist :engine:installLocal --no-daemon

# 2) Same dogfood as (A); --skip-native stages the bootstrap client (no Graal)
jk lock
jk build --skip-tests
jk plugin install-local
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*'
jk release --skip-tests --skip-native
```

The client never embeds the engine (ticket-1020). Spawning uses
`~/.local/share/jk/versions/<v>/lib/jk-engine.jar` or `JK_ENGINE_EXE`.

| Still Gradle | Why |
|---|---|
| `./gradlew test` (unit tier) / `integrationTest` / `checkAll` | CI source of truth for the test suite (Linux; unit on every push/PR, integration on `main` pushes + heavy-path PRs) — see [docs/perf/test-suite-tiers.md](docs/perf/test-suite-tiers.md) |
| `./gradlew dist` / `nativeCompile` | Prefer `jk release` for dogfood ship layout; Gradle still for native CI matrix |
| `./gradlew installLocal` | Workers + **engine materialize/bounce** (JK-1194); or `jk plugin install-local` after `jk build` for workers only |

Dogfood ship layout (after bootstrap `jk` on PATH; Graal for native CLI):

```bash
jk release --skip-tests    # native CLI + JVM engine + workers; alias: jk dist
./install.sh target/dist/jk
```

### Per-OS CI (JK-1073)

| Lane | When | What |
|---|---|---|
| **Linux** (`ci.yml`) | Every push / PR | Unit `./gradlew test` always; `./gradlew integrationTest` on `main` pushes + heavy-path PRs; self-host, showcase |
| **Windows + macOS** (`ci-os-nightly.yml`) | **Nightly** (cron) + manual `workflow_dispatch` | Filtered `:core:test :wire:test :engine:test :cli:test` plus the matching `:integrationTest` tasks; Windows exercises real TCP+token engine transport (JK-1011 field path); macOS thin-client smoke |

Rationale: macOS runners ~10× and Windows ~2× Linux minutes — not every push. Native-image
per OS waits on the release matrix (JK-1066).

**Reproduce locally**

```bash
# Same filter as nightly (unit + integration for the OS-sensitive modules):
./gradlew :core:test :wire:test :engine:test :cli:test \
  :core:integrationTest :wire:integrationTest :engine:integrationTest :cli:integrationTest

# Windows local install of a thin client (PowerShell):
#   .\gradlew :cli:installDist :engine:shadowJar
#   pwsh -File scripts\install.ps1 -LocalPath clients\cli\build\install\jk\bin\jk.bat
# Download install on Windows is stubbed until JK-1066.
```

Flakes on new OS lanes: open a ticket; known flake classes include TempDir/pipe-closed on
Linux and are expected to grow Windows path/FD variants.

#### Engine / CLI tests under self-host

`server/engine` declares `[build].test-plugin-jars`. When those names are **workspace
siblings** (today: `test-runner`, `java-compiler`), the test JVM gets
`-Djk.<worker>.plugin.jar` pointing at the **built shadow jar** under
`plugins/<name>/target/`. Other workers still resolve from `installLocal` / CAS.

CLI integration tests (`:cli:integrationTest`) spawn a real engine from `:engine:shadowJar`
(materialized into the test `JK_HOME`) — no in-process dual path (ticket-1020). `:cli:test` is
the pure unit tier (TUI/args/jsonl) with no shadowJar or worker-jar dependency.

**Suite timing (order of magnitude, warm laptop):** default `./gradlew test` (unit tier) ≈
**3 minutes**; `:cli:integrationTest` ≈ **7 minutes** with warm engine across methods
(1042/1055). TempDir cleanup uses `JkTempDirDeletionStrategy` (stop engine only when delete
fails). Use module filters mid-ticket; the pre-merge bar is `./gradlew checkAll` (unit +
integration) before merge to `main`. Tier model:
[docs/perf/test-suite-tiers.md](docs/perf/test-suite-tiers.md). Shared dep cache:
`jk.test.cache.dir` under `clients/cli/build/test-shared-cache`.

Prefer `jk build --skip-tests` plus `jk test --modules 'shared/*,server/…,plugins/*'`
for dogfood; keep `./gradlew :cli:integrationTest` for the CLI integration suite (nested engines).

Refresh locks after dependency changes: `jk lock` (commit the workspace-root `jk-lock.toml`).

### Showcase monorepo smoke (ticket-1038)

Multi-module sample under
[`docs/features/examples/workspace-showcase/`](docs/features/examples/workspace-showcase/):

```bash
./gradlew :cli:installDist :engine:shadowJar installLocal --no-daemon
CLIENT_BIN="$PWD/clients/cli/build/install/jk/bin/jk"
ENGINE_JAR=$(ls "$PWD/server/engine/build/libs/jk-engine-"*.jar | head -1)
"$CLIENT_BIN" self materialize "$CLIENT_BIN" "$ENGINE_JAR"
export PATH="$PWD/clients/cli/build/install/jk/bin:$PATH"

cd docs/features/examples/workspace-showcase
jk lock && jk build && jk test --modules app
# optional: jk build --modules app
```

CI job **Showcase monorepo (jk)** runs the same path (no `continue-on-error`).

### One build at a time per checkout

`settings.gradle.kts` takes an OS file lock (`.gradle/cross-daemon-build.lock`) so
two Gradle daemons do not corrupt shared test outputs. A second invocation waits
with a clear message. Use a separate worktree for true parallel builds.

## Project layout

| Path | Role |
|---|---|
| `shared/` | Client-safe modules (`jk-api`, `core`, `plugin-sdk`, `wire`, …) |
| `server/` | Engine-only (`engine`, `resolver`, `io`, `toolchain`) |
| `clients/` | `cli` (native/thin JVM client + CLI tests), `web`, `vscode` (VS Code extension) |
| `plugins/` | First-party build/worker plugins |

### IDE plugins (wire-only)

```bash
./scripts/package-vscode.sh      # → clients/vscode/jumpkick-*.vsix (gitignored)
./scripts/package-intellij.sh    # → clients/intellij/build/distributions/*.zip
```

Requires `jk` on PATH. No engine jars in the IDE process. See `clients/vscode/README.md` and
`clients/intellij/README.md`.
See [docs/architecture.md](docs/architecture.md) for layering and process model, and
[docs/guide.md](docs/guide.md) for product behavior. CLI human chrome rules (CommandWedge,
blank envelope, script-mode allowlist, nerd/ansi/plain): [docs/tui.md](docs/tui.md).

## Docs and planning

- Public docs live under [`docs/`](docs/README.md) (keep the set small and accurate).
- Engineering board: **[kanartist](https://github.com/jkbuild/kanartist)** project `jk` (`JK-NNNN`). Claim/work rules and Done criteria: root [`AGENTS.md`](AGENTS.md).

## License

Contributions are under the [Apache 2.0](LICENSE) license.
