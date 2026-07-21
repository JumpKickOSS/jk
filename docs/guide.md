# User guide

jk is a declarative, lockfile-first build tool for **Java and Kotlin** (JDK 17+).
This guide covers the commands and files you touch every day.

## Install

```bash
curl -fsSL https://jumpkick.build/install.sh | bash

jk --help
```

Developer builds from this repo: see [CONTRIBUTING.md](../CONTRIBUTING.md).

State and cache live under `~/.jk/` (`JK_HOME` relocates the whole tree).

## Projects and `jk.toml`

```bash
jk init my-app          # or: jk new my-app
cd my-app
```

A minimal manifest:

```toml
[project]
group   = "com.example"
name    = "my-app"
version = "0.1.0"
jdk     = 25

[dependencies]
jackson = "2.18.2"   # caret by default: ^2.18.2

[test-dependencies]
junit = "5.11.0"
```

- **TOML is data** — no embedded scripts. Safe for `jk add` / `jk remove` to edit.
- Version strings: bare `"1.2.3"` means `^1.2.3`; use `"=1.2.3"` for exact, `"~1.2.3"` for patch-only, or ranges like `">=1.2,<2"`.
- Dependency scopes: `[dependencies]`, `[test-dependencies]`, `[provided-dependencies]`,
  `[runtime-dependencies]`, `[processor-dependencies]`, `[platform-dependencies]` (BOMs),
  `[export-dependencies]`, plus optional `[dev-dependencies]` / `[test-dev-dependencies]`.

Optional features (local to this project):

```toml
[dependencies]
postgres = { group = "org.postgresql", name = "postgresql", version = "42.7.4", optional = true }

[features]
default = ["postgres"]
[features.postgres]
deps = ["postgres"]
```

Profiles change *how* you compile (flags, JVM args), not *what* deps you have.
Variants change *which product* you build (sources, deps, plugin config) — see
`[variants]` and `--variant` / `--release` on artifact-producing commands.

## Lockfile

`jk.lock` is **canonical**. Commit it.

| Command | Role |
|---|---|
| `jk lock` | Resolve and write `jk.lock` |
| `jk sync` | Materialize cache / offline prep (`--offline-prepare`) |
| `jk outdated` | Read-only: which direct deps have newer versions than the lock |
| `jk update` | Re-resolve within declared constraints (rewrites the lock) |
| `jk build` | Builds from the lock — does not re-resolve |
| `jk tree` / `jk why` | Inspect the graph offline |

**Pre-release pins:** a lock (or BOM) that pins an RC/M/beta is a soft prefer. Conservative
re-locks (e.g. after editing another dep) keep that pin when it still satisfies the range.
Unpinned `latest` selection still prefers the newest **stable** over a newer pre-release.
Deliberate upgrades off a pre-release belong on `jk update` (within-range re-resolve), not on
the conservative path.

### Parallelism (`-j` / jobs)

One Mill-shaped knob for concurrent modules/workers (JK-1082). Free RAM may still reduce
live worker JVMs (`HeapPlan`).

| Value | Meaning |
|---|---|
| omit / `0` | All **effective** cores (default) |
| `1` | Serial (`-j1`) |
| `N` | Cap at N concurrent modules |

**Effective cores** (JK-1084): cgroup CPU quota when readable (Docker/k8s `cpu.max` /
cfs_quota), else `Runtime.availableProcessors()`. So `jobs = 0` on a 2-CPU container uses 2,
not the host’s 64. Memory is still free-RAM / HeapPlan — not a second memory probe.

```bash
jk build              # parallel, up to effective cores (and free RAM)
jk build -j1          # serial
jk build -j4          # at most 4 modules at once
jk build -w 2         # 2 test-runner JVMs *per module* (within -j)
jk build --parallel-tests   # also run tests across modules concurrently
```

| Layer | Setting |
|---|---|
| CLI | `-j` / `--jobs` (wins) |
| Env | `JK_JOBS` (or `JK_ENGINE_JOBS`) |
| Machine TOML | `~/.jk/config.toml` → `[engine] jobs = N` |

There is no separate `--parallel` / `--no-parallel` (removed; use `-j` / `-j1`).

### Lock-time trust

`jk lock` is the trust boundary. For each POM/artifact download jk:

1. Streams bytes into the content-addressed store and computes SHA-256 locally.
2. Fetches the repository's published sidecar (`.sha256`, else `.sha1`) when present and
   **fails closed** on mismatch (repo name + coordinate + expected vs actual).
3. If no sidecar exists, pins TOFU-style and may report how many artifacts lacked a checksum.
4. Warns once per repository that still uses plaintext `http://` (prefer HTTPS).

After the lock exists, `jk sync` / builds enforce the pinned hashes only — they do not re-check
upstream sidecars. GPG/Sigstore signatures are out of scope here (see release / plugin signing
tickets).

### Check for updates (`jk outdated`)

Lockfile stays law until you deliberately rewrite it. The usual loop:

```bash
jk outdated                      # Current / Compatible / Latest table
jk outdated --exclude-up-to-date # only rows that can move
jk outdated --output json        # machine-readable array of rows
jk why com.foo:bar               # why a pin is there
jk tree                          # full graph
jk update                        # re-resolve on purpose, then commit jk.lock
```

| Column | Meaning |
|---|---|
| **Current** | Version pinned in `jk.lock` (empty if unlocked) |
| **Compatible** | Newest version that still satisfies the declared range |
| **Latest** | Newest stable version in the repo (may be outside the range) |
| **Tip** | With `--show-tip`: prerelease / git frontier ahead of Latest |

**Exit code:** always `0` on a successful report (whether or not any dependency is outdated).
For CI “fail if drift”, parse `--output json` (or the human table) rather than relying on exit
status — there is no `--fail-if-outdated` flag by design (lockfile changes stay intentional).

**JSON schema** (`--output json`): a JSON **array** of objects:

```json
[
  {
    "module": "com.acme:app",
    "dependency": "com.foo:leaf",
    "display": "leaf",
    "scope": "main",
    "current": "1.1",
    "compatible": "1.1",
    "latest": "2.0",
    "tip": ""
  }
]
```

`module` is empty for a single-project (non-workspace) root. `display` is the catalog short
name when known. Use `--exclude-up-to-date` to drop rows where Current already matches Compatible
and Latest.

**Offline:** with `--offline` (or session offline), enumeration uses only the local cache / repo
mirrors. Unreachable remotes look empty on Compatible/Latest — the CLI prints a note so that is
not mistaken for “everything is current.” Prefer `jk sync --offline-prepare` before offline CI.

Platform BOMs (`[platform-dependencies]`) are **recommendations** (Gradle `platform()` style):
the pin is preferred first; a stricter transitive floor may lift past it. Use an exact or
caret/tilde version on the BOM itself — not `latest`.

Resolution is **highest-version-wins** (not Maven nearest-wins), with PubGrub prose on conflict.
Main, test, and processor graphs are solved separately so annotation-processor constraints
do not force main classpath versions.

## Packaging (thin / assembly / shrink / Boot)

| Artifact | Config | Command |
|---|---|---|
| Thin jar | default | `jk build` |
| Assembly jar | `[application] assembly = true` | `jk assembly` / `jk assemble` / `jk build` |
| Shrunk jar | `[shrink]` (+ shrink plugin) | `jk build` (size before→after in labels) |
| Spring Boot jar | spring-boot plugin | `jk build` (not `assembly`) |

Assembly merge/exclude rules (SPI, Spring META-INF, drop signatures / `module-info.class`):
[features/packaging.md](features/packaging.md). Samples:
[assembly-app](features/examples/assembly-app/), [shrunk-cli](features/examples/shrunk-cli/).

```toml
[application]
main = "com.example.App"
assembly = true    # assembly jar — jk assembly / jk assemble
```

R8 is **opt-in** via `[shrink]` only — never the default.

## Common commands

```bash
jk add g:a:v                 # or catalog short name: jk add jackson
jk remove <coord>
jk outdated                  # check for newer deps (read-only; see lockfile section)
jk update                    # re-resolve within ranges (rewrites jk.lock)
jk compile                   # type-check
jk build                     # package (thin, assembly, shrink, or Boot per config)
jk assembly                  # assembly jar (alias: assemble; requires assembly = true)
jk test
jk run -- args…
jk clean
jk explain                   # forecast / cache status (why will this rebuild?)
jk format
jk audit                     # OSV
jk deny                      # apply [deny.sources] host denylist (see Deny policy)
jk publish                   # optional --sign / --sigstore / --slsa / --sbom
jk image                     # OCI (daemonless)
jk native                    # GraalVM native-image
jk verify                    # rebuild in a scratch dir and compare hashes
```

Machine-readable output: `--output json` (or `jsonl`) on commands that support it.

### CLI UX (human-first)

The terminal is for people. Prefer settled **CommandWedge** chips (success green / work blue /
error red), not `jk <command>: …` log prefixes. Agents should use `--json`, BSP, or the engine
wire — not scrape prose. Opt out of rich chrome with `NO_COLOR`, `--no-ansi`, or
`JK_NERDFONT=false`. Full charter and migration tickets live on the org board (kanartist
**JK-1076**–**JK-1081**).

```bash
# Detect Nerd Font support once; writes ~/.jk/config.toml [global].nerdfont
jk self setup-terminal
jk self setup-terminal --nerd      # force on
jk self setup-terminal --no-nerd   # force off
```

Install runs `setup-terminal` best-effort after a local dist materialize.

### Session transcripts (`details.json`)

`jk build` and `jk test` write a small, versioned session file by default:

```text
target/.jk-cli/<yyyy-MM-dd'T'HHmmss.SSSZ>/details.json
```

Schema version is the top-level `schema` field (currently `1`). Contents include the
command name, a compact argv snapshot, exit code, wall-clock duration, optional wedge
summary, selected modules, pipeline steps, and key engine errors. The terminal stays
terse; with `-v` / `--verbose`, jk prints a one-line `Details: <path>` pointer after the
run.

Writing is best-effort: a missing project, full disk, or permission error never fails the
user command. Disable with `JK_CLI_DETAILS=off` (or `0`).

### Deny policy

```toml
[deny.sources]
deny = ["jcenter.bintray.com"]   # enforced at lock / jk deny (host match)
# deny.licenses — NOT enforced yet; config is rejected at parse (JK-1062)
# deny.yanked = "deny" — NOT enforced yet; omit or set "allow" only
```

Host matching is exact or a DNS-label suffix (`evil.com` matches `repo.evil.com`, not
`notevil.com`). License and yanked policies will fail closed at parse until enforcement
ships — silent no-ops are not allowed.

## Quality (format + lint)

| Concern | Path |
|---|---|
| **Format** (style rewrite) | `jk format` — first-party formatter plugin |
| **Java lint** (analysis) | Documented **Checkstyle recipe** via `jk tool install` (ticket-1033) |
| **Kotlin analysis** | **Deferred** — use `jk format` for style; detekt later as the same recipe pattern |

```bash
jk format
jk tool install com.puppycrawl.tools:checkstyle:10.21.4
jk tool run checkstyle -c checkstyle.xml src/main/java
```

Sample + notes: [features/examples/checkstyle-recipe/](features/examples/checkstyle-recipe/).
We deliberately do **not** ship Mill’s full lint matrix as first-party plugins.

### Why did this rebuild?

Use **`jk explain`** (alias **`why-rebuilt`**) — offline, no network. It forecasts cache
hit/miss per module and step (sources changed, dependency changed, options/classpath, lock
stale). Prefer this over Gradle build scans for day-to-day rebuild questions.

```bash
jk explain                   # full plan: cached vs rebuild sections
jk why-rebuilt               # same command (migration alias)
jk explain --verbose         # expand every step

# Module dependency DAG as Graphviz DOT (no engine; pipe to graphviz yourself)
jk explain --graph dot > modules.dot
dot -Tsvg modules.dot -o modules.svg
jk explain --graph dot --modules 'libs/*' --graph-out filtered.dot

# Pipeline tasks (Mill resolve-lite)
jk tasks                         # list first-party steps
jk show package-jar              # primary jar path for this module
jk inspect compile-java          # phase + path + on-disk status
jk tasks show package-jar --modules 'libs/*'
```

### Build timeline (chrome tracing)

Every `jk build` / `jk test` writes a Chrome Trace Event file at
`out/jk-chrome-profile.json` (under the project or workspace root) and prints a one-line
**Timeline:** path on stderr when the file is written. Open it in Perfetto or
`chrome://tracing` to see step durations and parallel modules. **CI tip:** archive
`out/jk-chrome-profile.json` as a build artifact.

```bash
# disable for one run
jk build --no-timeline
# or via env
JK_CHROME_PROFILE=off jk build
# custom path
JK_CHROME_PROFILE=/tmp/trace.json jk build
```

### Project build logic (`.jk-build/`)

Custom generate / prep steps live in a **hidden project-local directory**, not in TOML scripts
(unlike Gradle’s visible `buildSrc/`).

**Convention:** if `.jk-build/` exists next to `jk.toml`, its Java sources compile and run on
build (action-cached; outputs merge onto the classpath as resources). Prefer a
`BuildLogicContributor` SPI for **named tasks** at anchors (`AFTER_COMPILE`,
`AFTER_RESOURCES`, `BEFORE_PACKAGE`); legacy `*Build` mains still run at
`AFTER_RESOURCES`. `jk.toml` stays data-only (`logic` path / `logic-main` only).

```toml
# optional override — only when you do not want the .jk-build/ convention
[build]
logic = "tools/codegen"            # project-relative dir
logic-main = "demo.LineCountBuild" # optional public static void main(String[])
# logic = "off"                    # disable even if .jk-build/ exists
```

```text
my-app/
  jk.toml
  src/…
  .jk-build/src/demo/LineCountBuild.java   # legacy main, or BuildLogicContributor
```

Sample: `docs/features/examples/line-count-build/`. Prefer plugins for heavy/reusable tools; use
`.jk-build` for small project-local codegen (Mill task analogue). See
[project-build-logic.md](features/project-build-logic.md).

### IDE / BSP

```bash
jk bsp install               # write .bsp/jk.json
# IDE launches: jk bsp serve  (stdio BSP — no engine jars in the IDE process)
jk ide                       # offline .idea / .vscode files (export path)
```

**BSP capabilities (stdio `jk bsp serve`):**

| Capability | Status |
|---|---|
| `workspace/buildTargets`, sources, dependency modules | yes |
| `buildTarget/compile` | yes (per-target / module) |
| `buildTarget/test` | yes (engine `jk test` path; JUnit) |
| `buildTarget/run` | **no** — use IDE tasks / `jk run` |
| `workspace/reload` | yes |
| Debug adapter | no |

**VS Code (ticket-1017):** [`clients/vscode/`](../clients/vscode/) — VSIX via `./scripts/package-vscode.sh`.

**IntelliJ (ticket-1054):** [`clients/intellij/`](../clients/intellij/) — zip via `./scripts/package-intellij.sh`
(Tools → JumpKick actions).

Both are **wire-only** (shell `jk` / BSP; no engine jars in the IDE process). Requires `jk` on PATH.

```bash
./scripts/package-vscode.sh      # → clients/vscode/jumpkick-*.vsix
./scripts/package-intellij.sh    # → clients/intellij/build/distributions/*.zip
```
### jshell / REPL

```bash
jk jshell                    # build --skip-tests if needed, then jshell on compile classpath
jk jshell --no-build         # use existing target/classes + lock deps only
jk repl                      # alias
```

Requires a full JDK with `jshell` on `JAVA_HOME` / `java.home`. Run from a **module**
directory (not a pure workspace root). Extra args after the verb are forwarded to jshell.

### Live loops (`jk watch` / `jk dev`)

One mechanism: re-run a verb when sources change. **`jk dev` is only an alias for `jk watch run`.**

Watches **`src/`**, **`test/`**, and project-root **`jk.toml`** by default — not `target/`,
`out/`, `build/`, or VCS trees. Editor save bursts are debounced (default **150ms**).

```bash
jk watch compile             # typecheck loop
jk watch test                # TDD loop
jk watch build               # package loop (--skip-tests)
jk watch run                 # run the app + rebuild/reload on change
jk dev                       # same as: jk watch run
jk dev -- --port=8080        # app args after --
jk watch test --debounce-ms 300   # calmer loop on slow disks / network FS
```

`watch run` / `dev` use classes-dir execution, Spring Boot DevTools when present, otherwise process
restart; Android projects redeploy via the packaging plugin.

### Migration aliases

Hidden shortcuts map familiar verbs (`package` → `build`, `why-rebuilt` → `explain`, etc.).
See `jk --help` for the canonical set; aliases are for muscle memory only.

## Workspaces

```toml
# root jk.toml
[workspace]
modules = ["libs/*", "services/*"]

[workspace.dependencies]
jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.18.2" }
```

Monorepo tip: rebuild or retest only what you need:

```bash
# Git-changed modules (+ reverse dependents)
jk build --affected-since=origin/main
jk test --affected-since=origin/main

# Explicit module selectors (comma list, globs, braces)
jk build --modules api,worker
jk test --modules 'libs/*'
jk explain --modules '{api,worker}'

# Intersection when both flags set
jk build --modules 'libs/*' --affected-since=origin/main

# CI prepare → run (writes .jk/selective-plan.json with content hashes)
jk selective prepare --since=origin/main
jk selective run test            # skips modules whose src/ + jk.toml hash still match the plan
jk selective resolve --modules 'api,worker'   # dry list
```

`prepare` records per-module content fingerprints (`jk.toml` + `src/**`). A later
`selective run` without `--force`/`--rebuild` skips modules that still match (prints
“nothing changed” when the whole plan is clean). Hashes are content-based (not absolute
paths) so plans are shareable when trees match. Generated/`target` trees are not fingerprinted.

**Caveat:** fingerprints are **not** transitive. An unchanged module can be skipped even when
an upstream sibling it depends on changed. Use a full `jk build` / `--force` when the graph
matters more than incremental CI savings.

Outside a git repo or with an invalid ref, jk prints a clear error. If nothing under the
workspace matched, it exits 0 with “nothing affected” / “nothing selected”.
```toml
# services/api/jk.toml
[project]
name = "api"
# …

[dependencies]
jackson-databind.workspace = true   # shared external
widget-core.workspace = true        # sibling module (matches [project].name)
```

- **One `jk.lock` at the workspace root**
- `jk new path/to/mod` and `jk add ./path` register modules for you

## Git and path dependencies

```toml
[dependencies]
mylib = { git = "https://github.com/acme/mylib", tag = "v1.4.0" }
# or: branch / rev; path = "subdir" inside the repo
local = { path = "../sibling" }   # local project with its own jk.toml
```

The lock pins the resolved git SHA. Tag moves fail loudly until `jk update`.

## JDK and shell

```bash
jk jdk install temurin-25
jk jdk pin temurin-25          # writes .jdk-version
jk jdk list
eval "$(jk activate bash)"     # directory-aware JAVA_HOME (bash/zsh/fish/pwsh)
jk shell                       # subshell with project JDK
```

jk discovers existing installs (IntelliJ, SDKMAN, mise, asdf, Homebrew, system, …)
and can install from the JetBrains JDK feed. Supported project floor: **JDK 17+**.

## Migration from Maven / Gradle

```bash
jk mvn package                 # real Maven (wrapper-aware), managed by jk
jk gradle build                # real Gradle
jk import pom.xml              # → jk.toml + fidelity report
jk import build.gradle.kts     # declarative only (no script evaluation)
jk export maven                # publishable POM
jk export idea | vscode
```

POM import is the high-fidelity path. Gradle import is honest about limits: it does not
execute build scripts (no Groovy/Kotlin evaluation). It does read on-disk
`gradle/libs.versions.toml` version catalogs (libraries, bundles, `version.ref`) and maps
type-safe accessors like `libs.guava` into `[dependencies]`. Unresolved catalog refs show up
in the import report rather than vanishing. Versions stay on deps/BOMs — they are not written
into jk library catalog layers. Keep `jk gradle` for modules that still need full Gradle.

Single-file scripts (JBang-compatible headers): `jk tool run script.java` / `jkx`.

## Auth and repositories

```bash
jk auth login                  # GitHub / GitLab / Gitea / Bitbucket
# repositories in jk.toml or ~/.jk/config.toml — credentials via env / keychain / settings.xml
```

Maven Central is default. Corporate mirrors, forge package registries, S3/MinIO, and GCS
are supported. Prefer `auth = "env:TOKEN"` over secrets in TOML.

## Wrapper

```bash
jk wrapper                     # emit ./jk + jk.bat, pin-on-first-use
jk wrapper update
```

## Engine (brief)

Build work runs in a **resident engine** (JVM, memory-capped), started automatically on first
build. The CLI stays a slim native client.

```bash
jk engine status
jk engine stop
```

Details: [architecture.md](architecture.md#the-engine).

## Layout reference

| Path | Role |
|---|---|
| `jk.toml` | Project / workspace manifest |
| `jk.lock` | Locked graph (commit this) |
| `.jdk-version` | Optional pin (`temurin-21`) |
| `target/` | Build outputs (gitignored) |
| `.jk/` | Generated project state (gitignored) |
| `~/.jk/` | Global cache, JDKs, engine, tools |

## Status

jk is **pre-1.0 (alpha)**. APIs and lock schema may still change. See the repository README
for the current milestone focus.
