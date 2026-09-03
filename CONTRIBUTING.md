# Contributing to jk

## Source headers

Every source file starts with an SPDX line:

```
// SPDX-License-Identifier: Apache-2.0
```

Use the comment syntax appropriate to the file type (`//`, `#`, `<!--`, …).

## Line endings

LF everywhere (`.gitattributes` `eol=lf`). `*.bat` / `*.cmd` stay CRLF for `cmd.exe`.
On Windows, Git for Windows still ships `core.autocrlf=true`; override it so checkout
stays LF:

```
git config --global core.autocrlf false
git config --global core.eol lf
```

## Toolchain

Build with **JDK 25+**. Native `dist` needs a GraalVM-capable JDK (GraalVM CE is fine).
Gradle comes from the wrapper (`gradle/wrapper/`). SDKMAN is optional; otherwise Gradle can
provision a JDK via the foojay resolver on first use.

Dashboard JS suites (`:web:test`, part of `checkFast`) need **Node** at the version in
[`.nvmrc`](.nvmrc). `nvm`, `fnm`, and `mise` all read that file:

```bash
nvm install   # or: fnm install / mise install
nvm use
```

A missing `node` fails the gate rather than skipping. Opt out only with `JK_WEB_JS_SKIP=1`.

## Build-family commands

`jk build`, `jk test`, `jk native`, and workspace `jk image` share **one** engine
orchestrator (`WorkspaceExecute`). Do not add a new per-verb cascade (dirty set, ETA,
prepare, schedule). Add a `WorkspaceTarget` + module filter. See
[docs/contributors/architecture.md](docs/contributors/architecture.md).

## Building

```bash
./gradlew classes
./gradlew dist                                  # native client + engine jar → build/dist/
./install.sh build/dist/jk                      # local install (Unix)
# Windows native (needs unsigned PE runnable — SAC off, or a signed release):
#   .\install.cmd build\dist\jk.exe
# Windows thin client (supported; SAC-safe):
#   .\gradlew :cli:installDist installLocal
#   .\install.cmd clients\cli\build\install\jk\bin\jk.bat
```

**The native binary is the preferred shipped client** — a slim GraalVM native image, sub-50 ms
cold start, and the only client that can self-heal a missing engine (`EngineJarFetcher`). Building
one needs a GraalVM-capable JDK (SDKMAN is the least ceremony):

```bash
sdk install java 25-graalce && sdk use java 25-graalce
```

**Windows also supports the thin JVM client** (`:cli:installDist` → `jk.bat`). Smart App Control
blocks unsigned `jk.exe`. Contributors
who want unsigned `gradlew dist` / Graal SVM helpers can turn SAC off — it is optional, not
required. The thin client cannot self-heal a missing engine; materialize from this
checkout (`./gradlew installLocal` or `jk self materialize`).

Once a release is published, the common install is
`curl -fsSL https://jumpkick.build/install.sh | bash` (Windows: `irm …/install.ps1 | iex`).
Signed `jk.exe` is the user-facing Windows path once releases are published; `jk.bat` remains supported.

### Black-box examples (sibling repo)

End-to-end scenarios and early-adopter samples live in **[JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples)** (checkout next to this repo as `../jk-examples`). After product changes to lock/resolve/packaging/plugins/workspaces, reinstall local jk and run the relevant scenarios there (`jk lock && jk build && jk test`). They are the out-of-tree acceptance surface, not a replacement for `./gradlew test`.

`dist` builds the slim GraalVM native `jk` client and the engine fat jar
(`lib/jk-engine-<version>.jar`). The engine runs as a normal JVM app on a
jk-managed JDK — never as a native image. `nativeCompile` needs a GraalVM-capable
JDK (the pin above qualifies).

Full `./gradlew build` hits Maven Central; avoid rate-limited environments for the
full suite.

### Formatting

jk formats itself. Run `jk format` before you commit. There is no pre-commit hook or CI
format job — `jk format --check` is the local gate (required before every commit; see
[AGENTS.md](AGENTS.md#code-formatting-mandatory-before-every-commit)).

### Code as Art

How we write Java (size budgets, Typed Envelope, JSpecify, fluent Lombok, pre-1.0 breakage):
**[docs/contributors/code-as-art.md](docs/contributors/code-as-art.md)**. Comments and Javadoc state the current type only
(no ticket ids, no historical essays) — **[AGENTS.md](AGENTS.md#comments-and-javadoc)**.
Code as Art / Typed Envelope (see [docs/contributors/code-as-art.md](docs/contributors/code-as-art.md)) preempts other work until it closes.

### Self-host (phase 2+) — workspace modules + thin workers with jk

Long-form dogfood (Gradle + pure-jk in this same repo): **[docs/contributors/self-host.md](docs/contributors/self-host.md)**.
Bootstrap helper: `./scripts/bootstrap-from-gradle.sh`.

Catalog short names resolve through the **system catalog** (downloaded global registry +
bundled offline floor) plus optional workspace-root **`jk-libs.toml`**. There is no host-local
catalog file and no `catalog =` pin in `jk.toml`.

The repo is a jk **workspace** (root `jk.toml` + per-module manifests under `shared/`,
`server/`, `clients/`, and all first-party `plugins/*`). `clients/web` is a resources module;
`server/engine` packages as an **assembly** jar (fat) including the web SPA. Workers are thin
jars whose `Main-Class` is `PluginMain` (implied by `jk-plugin.toml` / the Plugin service file —
no `[application]` table). Side-load with `jk install`.

#### Client bootstrap

Native (Unix, or Windows with SAC off / a signed `jk.exe`):

```bash
# 1) Produce a local JumpKick + side-load worker jars into ~/.jk/store
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.jk/bin:$PATH"   # install.sh default

# 2) Lock + compile/package + curated tests + ship layout (no Gradle for javac)
jk lock
jk build --skip-tests
jk install
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*'
```

Windows thin client (SAC-safe, no Graal):

```powershell
.\gradlew :cli:installDist installLocal
.\install.cmd clients\cli\build\install\jk\bin\jk.bat
# PATH: %USERPROFILE%\.jk\bin  (jk.bat; leftover jk.exe is parked)
```

The client never embeds the engine. Spawning uses
`~/.jk/lib/jk-engine/` (or `$JK_HOME/lib/jk-engine/`) or `JK_ENGINE_EXE`.

| Still Gradle | Why |
|---|---|
| `./gradlew test` (unit tier) / `integrationTest` / `checkAll` | CI: `checkFast` on every push/PR (`ci.yml`); integration/slow/network/bench + coverage inventory + OS smoke on nightly (`ci-nightly.yml`). Local pre-merge bar is still `checkAll` when you touch wire/engine/CLI — see [docs/contributors/test-suite-tiers.md](docs/contributors/test-suite-tiers.md) |
| `./gradlew dist` / `nativeCompile` | Bootstrap / ship layout (`build/dist/jk`); Gradle still for native release matrix |
| `./gradlew installLocal` | Workers + **engine materialize/bounce**; or `jk install` after `jk build` for workers only |

Dogfood ship layout (bootstrap `jk` on PATH):

```bash
./gradlew dist installLocal
./install.sh build/dist/jk
```

Workers after a pure-jk build: `jk install`.

### CI lanes

| Lane | When | What |
|---|---|---|
| **Push / PR** (`ci.yml`) | Every push to `main` and every PR | Commit-authorship scan; `./gradlew checkFast` on Linux |
| **Nightly** (`ci-nightly.yml`) | Daily cron + manual `workflow_dispatch` | Linux: `integrationTest`, `slowTest`, `networkTest`, `benchTest`, coverage inventory, heap guard, doc examples. macOS + Windows: product smoke (`scripts/ci-product-smoke.sh`). |

Native multi-OS **images** stay on the **release** matrix (`release.yml`). Coverage is an
inventory (`./gradlew coverageReport -Pjk.coverage`); it never fails on a percentage. The
JaCoCo agent stays off unless that property is set, so `checkFast` does not pay for it.

**Reproduce locally**

```bash
./gradlew checkFast                          # same as push/PR CI
./gradlew integrationTest                    # nightly Linux integration
./gradlew benchTest                          # nightly microbenchmarks
./gradlew coverageReport -Pjk.coverage       # nightly coverage inventory
./gradlew checkAll                           # unit + integration before merge when you touch heavy paths
./scripts/ci-product-smoke.sh                # nightly macOS/Windows smoke (jk on PATH)
```

#### Engine / CLI tests under self-host

`server/engine` declares `[build].test-plugin-jars`. When those names are **workspace
siblings** (today: `test-runner`, `java-compiler`), the test JVM gets
`-Djk.<worker>.plugin.jar` pointing at the **built shadow jar** under
`plugins/<name>/target/`. Other workers still resolve from `installLocal` / CAS.

CLI integration tests (`:cli:integrationTest`) spawn a real engine from `:engine:shadowJar`
(materialized into the test `JK_HOME`) — no in-process dual path. `:cli:test` is
the pure unit tier (TUI/args/jsonl) with no shadowJar or worker-jar dependency.

**Suite timing (order of magnitude, warm laptop):** default `./gradlew test` (unit tier) ≈
**3 minutes**; `:cli:integrationTest` ≈ **7 minutes** with warm engine across methods
(1042/1055). TempDir cleanup uses `JkTempDirDeletionStrategy` (stop engine only when delete
fails). Use module filters mid-ticket; the pre-merge bar is `./gradlew checkAll` (unit +
integration) before merge to `main`. Tier model:
[docs/contributors/test-suite-tiers.md](docs/contributors/test-suite-tiers.md). Shared dep cache:
`jk.test.cache.dir` under `clients/cli/build/test-shared-cache`.

Prefer `jk build --skip-tests` plus `jk test --modules 'shared/*,server/…,plugins/*'`
for dogfood; keep `./gradlew :cli:integrationTest` for the CLI integration suite (nested engines).

Refresh locks after dependency changes: `jk lock` (commit the workspace-root `jk-lock.toml`).

### Showcase monorepo smoke

Multi-module sample under
[`docs/user/examples/workspace-showcase/`](docs/user/examples/workspace-showcase/):

```bash
./gradlew dist installLocal --no-daemon
export PATH="$PWD/build/dist:$PATH"

cd docs/user/examples/workspace-showcase
jk lock && jk build && jk test --modules app
# optional: jk build --modules app
```

### One build at a time per checkout

`settings.gradle.kts` takes an OS file lock (`.gradle/cross-daemon-build.lock`) so
two Gradle daemons do not corrupt shared test outputs. A second invocation waits
with a clear message. Use a separate worktree for true parallel builds.

## Project layout

| Path | Role |
|---|---|
| `shared/` | Client-safe modules (`host`, `jk-api`, `core`, `plugin-sdk`, `wire`, …) |
| `server/` | Engine-only (`engine`, `resolver`, `io`, `toolchain`) |
| `clients/` | `cli` (the native client + its tests), `web`, `vscode` (VS Code extension) |
| `plugins/` | First-party build/worker plugins |

### IDE plugins (wire-only)

```bash
./scripts/package-vscode.sh      # → clients/vscode/jumpkick-*.vsix (gitignored)
./scripts/package-intellij.sh    # → clients/intellij/build/distributions/*.zip
```

Requires `jk` on PATH. No engine jars in the IDE process. See `clients/vscode/README.md` and
`clients/intellij/README.md`.
See [docs/contributors/architecture.md](docs/contributors/architecture.md) for layering and
process model, and [docs/user/](docs/user/README.md) for product behavior. CLI human chrome
rules (CommandWedge, blank envelope, script-mode allowlist, nerd/ansi/plain):
[docs/contributors/tui.md](docs/contributors/tui.md).

## Docs and planning

- Product docs: [`docs/user/`](docs/user/README.md) (using JumpKick) and
  [`docs/contributors/`](docs/contributors/README.md) (this codebase). Internal PRDs and
  benches live in KanArtist `projects/jk/docs/`.
- Engineering board: **[kanartist](https://github.com/JumpKickOSS/kanartist)** project `jk` (`JK-NNNN`). Claim/work rules and Done criteria: root [`AGENTS.md`](AGENTS.md).

## Commit authorship

Commits must read as ordinary human contributions:

- Use your own name and email as author/committer.
- Do **not** add tool or model co-author trailers (`Co-Authored-By: …` for bots/tools), nor “generated by …” lines in commit messages, comments, or Javadoc.
- Do **not** name third-party AI products or models in commit messages, code comments, Javadoc, or user-facing docs (product docs may describe MCP clients generically).

Optional local guard that strips known agent trailers from the commit message before the commit is created:

```bash
git config core.hooksPath scripts/git-hooks
```

CI also scans commit messages on push/PR for those trailers.

## License

Contributions are under the [Apache 2.0](LICENSE) license.
