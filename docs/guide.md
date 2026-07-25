# User guide

jk is a declarative, lockfile-first build tool for **Java, Kotlin, and Groovy** (JDK 17+).
This guide covers the commands and files you touch every day.

## Install

```bash
curl -fsSL https://jumpkick.build/install.sh | bash

jk --help
```

Developer builds from this repo: see [CONTRIBUTING.md](../CONTRIBUTING.md).

State and cache live under `~/.jk/` (`JK_HOME` relocates the whole tree).

| Env / flag | Effect |
|------------|--------|
| `JK_HOME` | Root of jk’s on-disk tree (default `~/.jk`): cache, state, engine socket, bin, lib, jdks |
| `JK_CACHE_DIR` | Download / action cache only (CAS: `repos/`, `sha256/`, `metadata/`). Default `$JK_HOME/cache` |
| `JK_AOT_TRAIN=off` | Skip AOT **train-on-miss** (engine sidecar + plugin workers); still **use** existing `.aot` caches. Default on for live engines; CI / short-lived builds usually set `off`. Also `-Djk.aot.train=off`. |
| `JK_WORKER_AOT=off` | Plugin workers only: no AOT map **and** no train (`-Djk.worker.aot=off`). Control arm for benches. |
| `JK_CANCEL_GRACE_MS` | Shared cancel window for **all** forked workers (default **500 ms** total, then force). Not per-worker. Max env clamp 5000. Cancel never hangs. |
| `--cache-dir <dir>` | Same as `JK_CACHE_DIR` for one command; **passed to the resident engine** on the wire |

Cold resolve tests without wiping your real cache:

```bash
COLD=$(mktemp -d /tmp/jk-cold-XXXX)
jk lock --cache-dir "$COLD"          # or: JK_CACHE_DIR="$COLD" jk lock
# inspect: ls "$COLD/repos" "$COLD/sha256"
```

The engine process is still keyed by `JK_HOME` / state; only the CAS path is isolated.

### CI: what to cache between jobs

jk’s correctness does **not** depend on local caches — a cold machine with a valid
`jk.lock` always rebuilds. Caching only speeds up **CAS downloads**, **action hits**, and
(once present) local preflight memos. **Never commit** cache dirs to git.

| Path | What it holds | Safe to restore in CI? |
|------|----------------|------------------------|
| `~/.jk/cache` (or `$JK_CACHE_DIR`) | Content-addressed artifacts, action cache | **Yes** — primary win for warm builds |
| `~/.jk/jdks` | Managed JDKs | Yes if jobs share the same pin / OS |
| `target/.jk/` (per project) | Project-local engine state, including **preflight memos** (`dirty-memo.txt`, `graph-memo.txt`, `shape-memo.txt` under `target/.jk/preflight/`) | **Yes** with the project workspace |
| `target/.jk-cli/` | CLI session transcripts | Optional; not needed for speed |
| `jk.lock` | Resolved coords | **Commit** this (not a cache) |

**Do not cache** engine sockets / live process state under `~/.jk/state` across machines.

Example (GitHub Actions) — key on OS + lock hash so a lock bump invalidates the CAS restore:

```yaml
- uses: actions/cache@v4
  with:
    path: |
      ~/.jk/cache
      **/target/.jk
    key: jk-${{ runner.os }}-${{ hashFiles('**/jk.lock') }}
    restore-keys: |
      jk-${{ runner.os }}-
```

Also set `JK_AOT_TRAIN=off` on short-lived CI engines (see table above). After restoring
cache, a normal `jk build` should hit action cache for unchanged modules.

Preflight dirty memo fingerprints use **source content hashes** by default (CI-safe). Opt into
faster path/size/mtime fingerprints with `JK_PREFLIGHT_MEMO_MTIME=1` if needed.

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
- **Language**: Java by default (`java = 25` sets the compile target). Kotlin modules pin the
  compiler with `kotlin = "2.4.0"`, Groovy modules with `groovy = "5.0.4"` (Groovy 5+) —
  `jk new --lang kotlin|groovy` scaffolds either. A module may mix Java with Kotlin or with
  Groovy (cross-references resolve both directions); Kotlin + Groovy in one module is rejected.
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

### Parallelism (`-j` jobs, `-w` test workers)

Mill-shaped knobs (JK-1082 / JK-1087). Free RAM may still reduce live worker JVMs (`HeapPlan`).

#### Module graph (`-j` / `--jobs`)

| Value | Meaning |
|---|---|
| omit / `0` | All **effective** cores (default) |
| `1` | Serial (`-j1`) |
| `N` | Cap at N concurrent modules |

**Effective cores** (JK-1084): cgroup CPU quota when readable (Docker/k8s `cpu.max` /
cfs_quota), else `Runtime.availableProcessors()`. So `jobs = 0` on a 2-CPU container uses 2,
not the host’s 64.

| Layer | Setting |
|---|---|
| CLI | `-j` / `--jobs` (wins) |
| Env | `JK_JOBS` (or `JK_ENGINE_JOBS`) |
| Machine TOML | `~/.jk/config.toml` → `[engine] jobs = N` |

There is no separate `--parallel` / `--no-parallel` (removed; use `-j` / `-j1`).

#### Within-module tests (`-w` / `--workers`) — Mill class sharding

Discover test classes, then fork N runners that **pull** classes until empty (same idea as Mill
`testParallelism` + `min(jobs, #classes)`).

| Value | Meaning |
|---|---|
| omit / `0` | **Auto** (default): `min(jobs, classCount)`, then heap-clamped |
| `1` | One test JVM (serial within the module) |
| `N` | Cap at N runners (still ≤ class count; heap-clamped) |

When `W > 1`, each runner gets its own `java.io.tmpdir` (isolation).

#### Cross-module tests (default on; `--serial-tests` to opt out)

By default **module suites overlap** (C2 — Mill/Gradle-shaped; C1 measured ~40% wall win on
`shared/*`). Hermetic modules pin `[test] workers = 1`. To serialize the whole monorepo’s
run-tests gate (shared ports / temp / statics debugging):

```bash
jk test --serial-tests
# alias: --no-parallel-tests
```

`--parallel-tests` remains accepted (affirmative no-op; default is already on).

#### Mill-like recipes

```bash
# Default: parallel compile (-j=cores); auto within-module workers; parallel across modules
jk test

# Explicit Mill-shaped (same as default; flags for clarity)
jk test -j0 -w0 --parallel-tests

# Serial within-module (debug flakes / one JVM per module)
jk test -w1

# Serialize cross-module run-tests only (within-module still auto)
jk test --serial-tests

# Cap class-shard pool without touching module concurrency
jk test -w4

# Build with tests (same parallel-test default as jk test)
jk build

# CI / dogfood: often also JK_AOT_TRAIN=off
export JK_AOT_TRAIN=off
jk test -j0 -w0
```

| Axis | Default | Mill analogue |
|---|---|---|
| Module graph | `-j0` (cores) | `--jobs 0` |
| Within-suite JVMs | `-w0` auto `min(jobs, classes)` | `testParallelism=true` |
| Cross-module tests | **parallel** (opt out: `--serial-tests`) | tasks share the jobs pool |
| RAM veto | `HeapPlan` shrinks W | process count vs machine |

#### Per-module serial opt-out (hermetic suites)

Modules that cannot share a JVM (fixed ports, statics, nested engines) pin workers in
`jk.toml` — same role as Mill’s `def testParallelism = false`:

```toml
# Prefer the [test] table (Mill-shaped):
[test]
workers = 1          # serial within this module
# parallel = false   # alias for workers = 1

# Or under [build]:
# [build]
# test-workers = 1
# test-parallel = false
```

The module pin **wins** over CLI auto/`-w N` so monorepo `jk test -j0` (default parallel modules)
stays safe for known hermetic suites. Use `-w1` for one JVM per module, or `--serial-tests` to
serialize the whole workspace run-tests gate.

#### Test isolation contract (suite authors)

Defaults assume tests are **hermetic enough to share a machine** with other modules’ suites and
(when `W > 1`) other worker JVMs in the same module. jk already provides:

| Isolation | When |
|-----------|------|
| Separate forked test JVMs | Always (tests never run in the engine process) |
| Per-worker `java.io.tmpdir` + `TMPDIR` | When within-module `W > 1` |
| Optional nested-engine env isolation | `jk-cli` suite (fixed by product; not general) |

**You still must avoid:**

- **Fixed ports** (HTTP, gRPC, DB) shared across tests or modules — allocate free ports, or pin
  `[test] workers = 1` and/or run with `--serial-tests` while debugging.
- **Shared mutable statics / singletons** that assume a single suite order.
- **Writing outside worker temp** into a shared project path without coordination.
- **Assuming one JVM for the whole monorepo** — cross-module parallel is the default.

**When in doubt:**

```toml
[test]
workers = 1   # this module serial within itself
```

```bash
jk test --serial-tests   # whole workspace: one module’s tests at a time
jk test -w1              # every module: one test JVM
export JK_AOT_TRAIN=off  # CI / short-lived engines (skip train-on-miss)
```

Failure lines include **module** (and **worker** when `W > 1`) so parallel flakes are locatable.

**JUnit Platform in-process parallel** (`junit.jupiter.execution.parallel.enabled`) is separate from
jk `-w` process workers. Prefer one layer: multi-worker `-w` *or* Jupiter parallel with **`-w1`**.
When both are active (`W>1` plus Jupiter parallel on the test classpath), jk emits a **warn**
(`jupiter-parallel`). Details: [junit-parallel-vs-jk-workers.md](perf/junit-parallel-vs-jk-workers.md).

Details and isolation roadmap: [docs/perf/test-parallelization.md](perf/test-parallelization.md).

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

Platform BOMs (`[platform-dependencies]` / `[spring-boot] version`) are **recommendations**
(Gradle `platform()` style): the pin is preferred first; a stricter transitive floor may lift
past it. Use an exact or caret/tilde version on the BOM itself — not `latest`. The BOM is a
**pin source** (recorded on managed lock rows as `pinned-by`), not a runtime jar; `jk tree`
shows it under the platform section with its version and a `(platform)` tag, not as missing.

Resolution is **highest-version-wins** (not Maven nearest-wins), with PubGrub prose on conflict.
Main, test, and processor graphs are solved separately so annotation-processor constraints
do not force main classpath versions.

## Packaging (thin / assembly / shrink / Boot)

| Artifact | Config | Command |
|---|---|---|
| Thin jar | default | `jk build` |
| Assembly jar (`target/<name>-<version>-all.jar`) | `[application] assembly = true` | `jk assembly` / `jk assemble` / `jk build` |
| Shrunk jar | `[application] assembly = "shrink"` | `jk assembly` / `jk build` (R8; size labels) |
| Spring Boot jar | spring-boot plugin | `jk build` (not `assembly`) |
| Grails jar (Boot layout) | grails plugin | `jk build` (not `assembly`) |

One-off without editing `jk.toml`: `jk assembly --fat` or `jk assembly --shrink`. Persist with
`--write-config` (surgical edit of `assembly` only). See [features/packaging.md](features/packaging.md).

Assembly merge/exclude rules (SPI, Spring META-INF, drop signatures / `module-info.class`):
[features/packaging.md](features/packaging.md). Samples:
[assembly-app](features/examples/assembly-app/), [shrunk-cli](features/examples/shrunk-cli/).

```toml
[application]
main = "com.example.App"
assembly = true       # fat jar — jk assembly / jk assemble
# assembly = "shrink" # R8 small fat jar — same commands
```

R8 is **opt-in** via `assembly = "shrink"` (or a legacy `[shrink]` table) — never the default.

### Grails (`[grails]`)

Grails 8 (Apache, Spring Boot 4.1) on the Groovy lane — `jk new --grails` scaffolds a
minimal REST app (GORM domain, controller, `grails-app/conf/application.yml`):

```toml
[project]
groovy = "5.0.7"

[grails]
version = "8.0.0-M4"          # pins org.apache.grails:grails-bom (imports the Boot BOM)

[dependencies]                # versionless under the BOM
grails-core     = { group = "org.apache.grails", name = "grails-core" }
grails-web-boot = { group = "org.apache.grails", name = "grails-web-boot" }
```

The plugin contributes the `grails-app/*` source/resource roots (domain, controllers,
services, taglib, init, jobs compile; conf, i18n, views package as resources), compiles
with `--parameters`, and `jk build` produces a Boot-launcher executable jar.

## Common commands

```bash
jk add g:a:v                 # or catalog short name: jk add jackson
jk remove <coord>
jk outdated                  # check for newer deps (read-only; see lockfile section)
jk update                    # re-resolve within ranges (rewrites jk.lock)
jk compile                   # type-check
jk build                     # package (thin, assembly, shrink, or Boot per config)
jk assembly                  # assembly/shrink jar (alias: assemble; or --fat/--shrink)
jk release                   # local ship layout (alias: dist) — build + workers + target/dist
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

### Machine / agent output (JSONL)

Human TTY mode stays terse and visual. **Agents, scripts, and CI should not scrape it.**

```bash
jk build --output json …     # live JSONL on stdout (one object per line)
jk test  --output jsonl …    # identical to json — both mean live events
export JK_OUTPUT=json        # same for any command that uses PipelineConsole
```

- **`json` and `jsonl` are the same mode:** a **live** event stream (phases, progress ticks, labels,
  errors with structured test fields, step/pipeline finish). Not a single end-of-run blob.
- Every line includes `"schema":1`, `"ts"`, `"type"`. Schema stays **1** until jk 1.0 (no pre-release
  version churn). See [machine-output.md](machine-output.md) for the event table and how it aligns
  with web SSE and **MCP** (`POST /mcp`; `jk engine status` prints **MCP**).
- Session log (same JSONL shape, live append) lands in `target/.jk-cli/<ts>/details.jsonl`
  (below). Deep timings: `target/jk-chrome-profile.json`.

### CLI UX (human-first)

The terminal is for people. Prefer settled **CommandWedge** chips (success green / work blue /
error red), not `jk <command>: …` log prefixes. Agents should use **`--output json`/`jsonl`**,
BSP, the engine wire, or (later) MCP — not scrape prose. Opt out of rich chrome with `NO_COLOR`,
`--no-ansi`, or `JK_NERDFONT=false`. Full charter: kanartist **JK-1076**–**JK-1081**; machine
surface: [machine-output.md](machine-output.md).

```bash
# Detect Nerd Font support once; writes ~/.jk/config.toml [global].nerdfont
jk self setup-terminal
jk self setup-terminal --nerd      # force on
jk self setup-terminal --no-nerd   # force off
```

Install runs `setup-terminal` best-effort after a local dist materialize.

### Session transcripts (`details.jsonl`)

`jk build` and `jk test` write a **live** JSONL session log by default (same event shape as
`--output json`/`jsonl`):

```text
target/.jk-cli/<yyyy-MM-dd'T'HHmmss.SSSZ>/details.jsonl
```

One JSON object per line (`schema: 1`), appended as events arrive — safe to `tail -F` mid-run.
Lines carry an aggregate `progress` percent (0–100) matching the human bar. Opens with
`session-start`, ends with `session-finish` (`exit`, duration, optional wedge/modules). The
terminal stays terse; with `-v` / `--verbose`, jk prints `Details: <path>` when the session
opens (and again at finish).

Writing is best-effort: a missing project, full disk, or permission error never fails the
user command. Disable with `JK_CLI_DETAILS=off` (or `0`). See [machine-output.md](machine-output.md)
for the materialize cadence (TTY ~80 ms paint; disk flush ≤2 s on hot ticks).

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



## Project layout

Progress bar and ETA are **run-wide aggregates** of outstanding real work (cache skips are token
ticks only); see [progress-contract.md](perf/progress-contract.md).

jk modules use a **Mill-like** source layout by default (`layout = "simple"` / AUTO when
no Maven tree is present). Language is by file extension (`.java` / `.kt` / `.groovy` may
share a dir).

| Input | Simple (default) | Traditional (Maven import) |
|-------|------------------|----------------------------|
| Main sources | `src/` | `src/main/{java,kotlin,groovy}` |
| Main resources | `resources/` | `src/main/resources` |
| Default tests | `test/src/` | `src/test/{java,kotlin,groovy}` |
| Default test resources | `test/resources/` | `src/test/resources` |
| Named test suite `<name>` | `<name>/src/` (e.g. `integration/src/`) | `src/<name>/{java,kotlin,groovy}` |
| Named suite resources | `<name>/resources/` | `src/<name>/resources` |

Outputs always land under `target/`. `jk new` scaffolds the simple columns; use traditional
paths (or `layout = "traditional"`) when importing a Maven tree. Suite resources ride the test
classpath only when that suite is selected (`jk test --suite integration`, `--all`, etc.).

`jk test` runs the **test** suite only by default; see [Test suites and tags](#test-suites-and-tags).
`jk ide` marks every discovered suite as IDE test source roots.

## Test suites and tags

`jk test` runs the **default suite** only: sources under `test/src/` (simple layout) or
`src/test/{java,kotlin,groovy}` (traditional). Optional sibling suites are discovered when they
exist — for example `integration/src/` or `src/integration/java`.

```bash
jk test                           # default suite ("test") only
jk test --suite integration       # only that suite
jk test --suite test --suite integration
jk test --all                     # every discovered suite
jk test --exclude-tag slow        # JUnit Platform tags (repeatable)
jk test --include-tag smoke
jk test --all --exclude-tag bench
```

`--all` and `--suite` cannot be combined. Unknown suite names error with the available list.

Declarative defaults (CLI wins when you pass tags):

```toml
[test]
workers = 1
default-exclude-tags = ["slow", "bench"]

[profiles.ci]
exclude-tags = ["bench"]
include-tags = []   # optional
```

`--profile` (and CI auto-profile `ci`) merges profile tag filters. Suites and tags are part of
the test stamp: changing selection re-runs tests even if sources are unchanged.

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
jk explain                   # full plan: cached vs rebuild sections + ETA
jk why-rebuilt               # same command (migration alias)
jk explain --verbose         # expand every step
jk explain --rebuild         # global flag: forecast full rebuild ETA (same as `jk build --rebuild`)

# Module dependency DAG (no engine)
jk explain --graph dot > modules.dot
dot -Tsvg modules.dot -o modules.svg
jk explain --graph mermaid > build.mmd
jk explain --graph mermaid --modules 'libs/*' --graph-out filtered.mmd
jk explain --graph dot --modules 'libs/*' --graph-out filtered.dot

# Host calibration for cold ETAs (offline multi-probe; JK-1180)
jk engine calibrate          # skip if already measured
jk engine calibrate --force  # re-run + retime cold engine start


# Pipeline tasks (Mill resolve-lite)
jk tasks                         # list first-party steps
jk show package-jar              # primary jar path for this module
jk inspect compile-java          # phase + path + on-disk status
jk tasks show package-jar --modules 'libs/*'
```

### Build timeline (chrome tracing)

Every `jk build` / `jk test` has the **engine** write a Chrome Trace Event file at
`target/jk-chrome-profile.json` (no terminal noise). Spans use the same step durations as
build metrics. Open the file in Perfetto or `chrome://tracing`. **CI tip:** archive that
path as a build artifact.

```bash
jk build --no-timeline              # skip the file (global flag)
JK_CHROME_PROFILE=off jk build      # same via env
JK_CHROME_PROFILE=/tmp/trace.json jk build   # custom path
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

**Multi-suite tests (JK-1139–1142 / JK-1198):** `jk ide` registers **every discovered test suite**
(`test/src/`, `integration/src/`, `src/test/…`, `src/integration/…`, …) as IDE **test** source roots
in the same module — IntelliJ `.iml` and VS Code/JDT `.classpath`. One test output directory;
no extra IDE module per suite. BSP `buildTarget/sources` lists the same roots. Named suite
resource dirs (`integration/resources/`, …) are marked as test resources when present.

Execution still follows the CLI default: `jk test` runs only the **test** suite. Use
`jk test --suite integration`, `jk test --all`, or tags for other selections. After
`jk ide`, IntelliJ gains shell run configurations (`jk test`, `jk test (all suites)`, and
one per extra suite) and VS Code gets matching `.vscode/tasks.json` entries.

**BSP `buildTarget/test` selection (JK-1143):** omit `params.data` for default-suite only
(same as bare `jk test`). Optional jk extension:

```json
{
  "params": {
    "targets": [{ "uri": "file:///path/to/module#name" }],
    "data": {
      "allSuites": false,
      "suites": ["test", "integration"],
      "includeTags": ["smoke"],
      "excludeTags": ["slow"]
    }
  }
}
```

Fields mirror CLI: `allSuites` ↔ `--all`, `suites` ↔ `--suite`, tags ↔
`--include-tag` / `--exclude-tag`.

**BSP capabilities (stdio `jk bsp serve`):**

| Capability | Status |
|---|---|
| `workspace/buildTargets`, sources, dependency modules | yes |
| `buildTarget/compile` | yes (per-target / module) |
| `buildTarget/test` | yes (engine `jk test`; optional suite/tag `data`) |
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

Watches **`src/`**, **`test/src/`**, and project-root **`jk.toml`** by default — not `target/`,
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

Maven Central and Google Maven are the default remotes (Central first, then Google) so
AndroidX / R8 / apksig resolve without a per-project `[repositories]` table. Local lookup
still prefers CAS, per-repo mirrors under the cache, and `~/.m2` before the network. Corporate
mirrors, forge package registries, S3/MinIO, and GCS are supported. Prefer `auth = "env:TOKEN"`
over secrets in TOML.

### Exclusive groups (dependency-confusion defense)

When you declare an **internal** repository next to a public one, bind internal Maven namespaces
so versions of those coordinates are **never** discovered or fetched from other remotes:

```toml
[repositories.central]
url = "https://repo.maven.apache.org/maven2/"

[repositories.internal]
url = "https://repo.acme.com/maven"
# Exact group or prefix.* (group + subpackages). Matching GAs only resolve from this repo
# (and any other repo that also lists the same group).
groups = ["com.acme", "com.acme.*"]
```

- **Bound group** → solver only sees versions from claiming repos (a higher version planted on
  Central cannot win at `jk lock` / `jk update`).
- **Unbound group** → all remotes union as before.
- **Already locked** artifacts keep their lockfile source pin until you re-resolve that line
  (`jk update` re-opens discovery for updated/new deps).
- If you configure **multiple repositories without any `groups`**, jk **warns once** per lock
  (still resolves). Add exclusive bindings for internal namespaces.

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
