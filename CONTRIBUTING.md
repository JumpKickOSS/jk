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

Build with the released jk: `curl -fsSL https://jumpkick.build/install.sh | bash` installs the
client, the engine and the JDK the engine runs on. The native client links against a GraalVM-capable
JDK (`jk build` uses the one `--graal` / `GRAALVM_HOME` names, else an installed one). Java sources
compile at **JDK 25**; the two JDK-17 libraries (`shared/host`, `shared/plugin-sdk`,
`shared/guard-api`) say so in their manifests.

Dashboard JS suites (`clients/web`, part of the fast tier) need **Node** at the version in
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
curl -fsSL https://jumpkick.build/install.sh | bash   # the released jk, once per machine
export PATH="$HOME/.jk/bin:$PATH"
jk build --skip-tests        # every module; native client + engine jar → target/dist/
jk install --skip-tests      # this checkout's client, engine and workers take over ~/.jk
```

From then on the engine running your builds is the one you just compiled; the long form is
[self-host](docs/contributors/self-host.md#bootstrap). `./install.sh target/dist/jk` installs the
ship layout on a machine with no jk yet. A host with no native client gets the JVM client from the
same installer ([releases](docs/contributors/releases.md#platforms-without-a-hosted-client)).

### Dependency locking

`jk-lock.toml` at the root is the one lock for the whole workspace, and every manifest pins exactly
(`latest` resolves once, at lock time). After a dependency change:

```bash
jk lock          # re-resolves and rewrites jk-lock.toml; commit it
```

A build that rewrites the committed lock is a drifted pin or a non-deterministic writer, and CI's
self-host job fails on the diff.

**The native binary is the shipped client** — a slim GraalVM native image, and the client that
self-heals a missing engine (`EngineJarFetcher`). Released natives are signed when published;
Smart App Control blocks an unsigned `jk.exe`.

### Black-box examples (sibling repo)

End-to-end scenarios and early-adopter samples live in **[JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples)** (checkout next to this repo as `../jk-examples`). After product changes to lock/resolve/packaging/plugins/workspaces, reinstall local jk and run the relevant scenarios there (`jk lock && jk build && jk test`). They are the out-of-tree acceptance surface, not a replacement for `jk test`.

`jk build` produces the slim GraalVM native `jk` client and the engine fat jar
(`target/dist/lib/jk-engine-<version>.jar`). The engine runs as a normal JVM app on a
jk-managed JDK — never as a native image.

`jk test --profile network` talks to Maven Central; keep it off rate-limited environments.

### Formatting

jk formats itself. Run `jk format` before you commit. There is no pre-commit hook or CI
format job — `jk format --check` is the local gate (required before every commit; see
[AGENTS.md](AGENTS.md#code-formatting-mandatory-before-every-commit)).

### Code as Art

How we write Java (size budgets, Typed Envelope, JSpecify, fluent Lombok, pre-1.0 breakage):
**[docs/contributors/code-as-art.md](docs/contributors/code-as-art.md)**. Comments and Javadoc state the current type only
(no ticket ids, no historical essays) — **[AGENTS.md](AGENTS.md#comments-and-javadoc)**.
Code as Art / Typed Envelope (see [docs/contributors/code-as-art.md](docs/contributors/code-as-art.md)) preempts other work until it closes.

### Self-host

Long-form dogfood: **[docs/contributors/self-host.md](docs/contributors/self-host.md)**.

Catalog short names resolve through the **system catalog** (downloaded global registry +
bundled offline floor); jk's own manifests use registry names only and the tree carries no
`jk-libs.toml`. There is no host-local catalog file and no `catalog =` pin in `jk.toml`.

The repo is a jk **workspace** (root `jk.toml` + per-module manifests under `shared/`,
`server/`, `clients/`, and all first-party `plugins/*`). `clients/web` is a resources module;
`server/engine` packages as an **assembly** jar (fat) including the web SPA. Workers are thin
jars whose `Main-Class` is `PluginMain` (implied by `jk-plugin.toml` / the Plugin service file —
no `[application]` table). `jk install` shelves every module and takes the home over.

The client never embeds the engine. Spawning uses
`~/.jk/lib/jk-engine/` (or `$JK_HOME/lib/jk-engine/`) or `JK_ENGINE_EXE`.

### The gate

```bash
jk format                                    # before every commit
jk guard                                     # every house-rule lane, fixtures included
jk build                                     # every module, fast tier included
jk test --profile integration                # the pre-merge bar
```

### CI lanes

| Lane | When | What |
|---|---|---|
| **Push / PR** (`ci.yml`) | Every push to `main` and every PR | Commit-authorship scan; the shell fixtures; the self-host job: `jk build`, `jk install`, `jk guard`, `jk test`, the curated integration lane, the lock diff |
| **Nightly** (`ci-nightly.yml`) | Daily cron + manual `workflow_dispatch` | `jk test --profile integration`, `slow`, `network`, `bench`; the coverage ratchet (`jk test --coverage`, `jk guard`); heap guard; doc examples. macOS: product smoke (`scripts/ci-product-smoke.sh`). |

Native multi-OS **images** stay on the **release** matrix (`release.yml`). Coverage is a
per-module ratchet (`jk test --coverage`, then `jk guard`; G91) with no percentage target, and the
JaCoCo agent stays off unless asked for, so the fast tier does not pay for it.

**Reproduce locally**

```bash
jk format --check
jk guard
jk build
jk test --profile integration
scripts/curated-integration.sh               # what the pull request's boundary lane runs
scripts/shellcheck.sh                        # CI's shell lint: installers, scripts/, the wrapper (skipped on Windows; skips with a notice when shellcheck is absent)
for f in scripts/test-*.sh; do bash "$f"; done  # CI's shell fixtures (installer, wrapper bootstrap, Maven repo, release version, flatten, example lock drift, curated lane)
actionlint .github/workflows/*.yml
jk test --profile slow                       # nightly framework / language e2e
jk test --profile network                    # nightly, talks to real remotes
jk test --profile bench                      # nightly microbenchmarks
jk test --coverage && jk guard               # nightly coverage ratchet (G91)
./scripts/ci-product-smoke.sh                # nightly macOS smoke (jk on PATH)
```

#### Engine / CLI tests under self-host

`server/engine` and `clients/cli` declare `[build].test-plugin-jars`: each name is a build-order
edge and a `-Djk.<worker>.plugin.jar` the run-tests step hands the test JVM, so a test that forks a
worker runs the one this build produced. CLI integration tests spawn a real engine from the
assembly the same step names (`-Djk.engine.jar`), in an isolated sandbox `JK_HOME` — no in-process
dual path. The fast tier (`jk test`) is the pure unit tier (TUI/args/jsonl) with no engine spawn.

Use `-m` and `--class` mid-ticket (`jk test -m clients/cli --class cc.jumpkick.cli.engine.EngineClientTest --profile integration`);
the pre-merge bar is the whole integration profile. Tier model:
[docs/contributors/test-suite-tiers.md](docs/contributors/test-suite-tiers.md).

Refresh locks after dependency changes: `jk lock` (commit the workspace-root `jk-lock.toml`).

### Showcase monorepo smoke

Multi-module sample under
[`docs/user/examples/workspace-showcase/`](docs/user/examples/workspace-showcase/):

```bash
jk build --skip-tests && jk install --skip-tests

cd docs/user/examples/workspace-showcase
jk lock && jk build && jk test --modules app
# optional: jk build --modules app
```

## Project layout

| Path | Role |
|---|---|
| `shared/` | Client-safe modules (`host`, `jk-api`, `core`, `plugin-sdk`, `wire`, …) |
| `server/` | Engine-only (`engine`, `guard`, `resolver`, `io`, `toolchain`) |
| `server/guard/packs/` | First-party rule packs (`cc.jumpkick.guards:*`) |
| `server/guard/fixtures/` | Self-host must-bite trees for root/`@Fixture` rules |
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

Local guard: install jk's git hooks once per clone. The `commit-msg` hook refuses a message that
breaks a `commit` rule in `jk-guards.toml` (here: agent attribution trailers); the `pre-commit`
hook refuses a hand-edited guard baseline or a suppression-shaped comment.

```bash
jk guard hooks install      # writes .git/hooks/commit-msg and pre-commit; `jk guard hooks` prints them
```

The hooks are advisory (`--no-verify` skips them); CI also scans commit messages on push/PR for those trailers.

## License

Contributions are under the [Apache 2.0](LICENSE) license.
