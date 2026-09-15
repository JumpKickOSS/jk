# Self-hosting JumpKick

jk builds jk once a client exists. The root **`jk.toml`** is a workspace (members under `shared/`,
`server/`, `clients/`, `plugins/*` and the rule packs under `server/guard/packs/`), `jk-lock.toml`
at the root is the one lock, and jk's outputs land under `target/`. One gate, two build
definitions: Gradle (`./gradlew`) is the bootstrap that produces the first native client and engine
jar when this OS has no hosted release — jumpkick.build serves Linux amd64 today; macOS, Windows
and Linux aarch64 still need this path — and it builds nothing CI judges.
The IntelliJ plugin under `clients/intellij` and the VS Code extension under `clients/vscode`
keep their own build tools because that is how those platforms ship plugins. The Gradle projects
under `bench/jar-size/` are fixtures the fat-jar bench compares jk against.

## The gate

Run in this order, in your checkout, with the installed jk; all four must be green before a change
is done:

```bash
jk format                        # formats; `jk format --check` is the same verdict without writing
jk guard                         # every house-rule lane, the tree lane and the fixture proofs included
jk build                         # every module compiled, packaged and its fast test tier run
jk test --profile integration    # the pre-merge bar: engine, e2e and worker suites
```

`jk build` must not rewrite the committed `jk-lock.toml`: the manifests pin exactly, so a build
that moves the lock means a pin drifted or the lock writer put something non-deterministic in the
file. `jk test --profile slow` is due when a change touches a framework or language plugin; the
`network` and `bench` profiles are nightly and gate nothing.

The shell fixtures and lints are part of the same bar when a script or a workflow moves:
`for f in scripts/test-*.sh; do bash "$f"; done`, `scripts/shellcheck.sh` (also the `shellcheck`
guard, G100, under `jk guard`) and `actionlint .github/workflows/*.yml`.

## CI

**The self-host job is the merge authority.** Every pull request installs the hosted release
`.jk/ci-bootstrap-version` pins the way a user installs it
(`curl -fsSL https://jumpkick.build/install.sh | JK_VERSION=<pin> bash`) into an isolated
`JK_HOME`, builds the checkout with it, then hands the home to the checkout's own jk
(`jk install`) and lets that jk run the house-rule gate, the fast test tier and the curated
integration lane (`scripts/curated-integration.sh` over `curated-integration.txt`, budgeted at
eight minutes). A guard failure is annotated with the rule that owns it.

| Question | Answer |
|---|---|
| What must be green to merge | the self-host job, the shell fixtures job, the workflow lint (actionlint, commit-pinned actions, read-only tokens: `scripts/check-workflows.sh`, the same rules `jk guard` holds as `workflow-pins-permissions`) and the authorship scan |
| What produces the `jk` under test | the hosted release the pin names bootstraps; `jk install` then swaps in the checkout's own client, engine and workers |
| Where CI installs it | `$GITHUB_WORKSPACE/.ci-jk-home` — never the runner's `~/.jk` |
| What proves the graph is honest | `jk build` must not rewrite the committed `jk-lock.toml`; `cmp` of `target/dist/jk` against `$JK_HOME/bin/jk` and the engine sha against `jk-engine.toml` prove the takeover |
| What audits the graph | `jk audit --severity HIGH --output json` over the committed lock, after the drift check, by the checkout's own jk: HIGH, CRITICAL and unlabelled advisories fail the job, `scripts/ci-audit-annotate.sh` names each one, the JSON is the `jk-audit` artifact; a pin bump or an `[audit] ignore` with a reason is the fix, never a skipped step |
| What runs nightly | `jk test --profile integration`, `--profile slow`, `--profile network`, `--profile bench`, the coverage ratchet (`jk test --coverage`, `jk guard`), the heap guard, the doc examples, the audit at `LOW` (informational), the macOS product smoke and the JVM client smoke on a runner with a plain JDK and no GraalVM (`ci-nightly.yml`) |
| Wall-clock series | `.github/workflows/wall-measure.yml`, weekly, ratcheted by `scripts/wall-band.py` against `wall-baseline.toml` |

Guard **G57** (`ci-cadence`) keeps the self-host job, its isolated `JK_HOME`, the four verbs it
runs (`jk build`, `jk install`, `jk guard`, `jk test`), its bootstrap through `install.sh` at the
pinned version, the nightly tiers, the JVM client smoke and the scheduled wall measurement in
place. It fails on a
`gradlew` in the self-host job, on a `JK_VERSION=<digits>` literal in any workflow, and on
`continue-on-error` on any job of `ci.yml`: that flag is how a merge requirement becomes a
courtesy run without anyone deleting a job. The weekly wall measurement keeps its own
`continue-on-error` in `wall-measure.yml`, where a wall time is a record rather than a verdict.
Guard **G63** (`curated-integration`) owns the curated registry's shape, the classes it names, the
lane script and its step in the self-host job.

### The bootstrap pin

`.jk/ci-bootstrap-version` holds one line: the release every workflow bootstraps from. It is the
only place that version is written. `ci.yml`, `ci-nightly.yml`, `wall-measure.yml` and
`release.yml` read it with `tr -d '[:space:]' < .jk/ci-bootstrap-version`, and G57 refuses a
workflow that spells the version itself, a pin that is not one `x.y.z`, and a pin newer than
`jk.toml`'s own `version` — a bootstrap release is cut from a tree, so it is never ahead of one.

After a release ships to jumpkick.build:

1. Prove the release can build this tree, from a scratch home that is not your `~/.jk`:
   ```bash
   export HOME=$PWD/../bootstrap-probe JK_HOME=$HOME/.jk
   curl -fsSL https://jumpkick.build/install.sh | JK_VERSION=<new> bash
   "$JK_HOME/bin/jk" build --skip-tests && "$JK_HOME/bin/jk" install --skip-tests && "$JK_HOME/bin/jk" guard
   ```
2. Write `<new>` to `.jk/ci-bootstrap-version`.
3. In `release.yml` and the nightly `os-smoke` matrix, add a row for every platform whose client
   `releases/<new>/SHA256SUMS` lists ([releases](releases.md#platforms-without-a-hosted-client)).
4. `jk guard`, then commit the pin and the workflows together.

### The bootstrap chain

The pinned release is the only jk that can build this tree from nothing, so the tree must stay
within what that release reads. Two facts keep it there, each held by a guard on every
`jk guard`: **G86** (`lock-version-is-one`) holds that the lock's `version` is the frozen schema
every hosted release reads, and **G105** (`bootstrap-pin-reads-tree`) that the lock's `jk-min`
floor never exceeds the pin, with a tree fixture that proves it bites. A branch that changes a manifest key or the
lock format so that the pinned release cannot read the tree has no hosted bootstrap — only the
Gradle build, which produces a client and engine but runs no gate — so a format change ships as
two releases, in this order:

1. **Reader first.** A release whose engine reads the new format *and* the old one, while the
   tree still writes the old. Host it, prove it with the probe above, bump the pin to it.
2. **Writer second.** Only once that pin is in place does the tree start writing the new format.
   The pinned release reads it, so the self-host job keeps its bootstrap.

When a branch must carry the new format before step 1 has shipped — a schema bump under
development — build it from the last commit the pinned release can read, and let that build
bootstrap the branch:

```bash
export JK_HOME=/some/scratch/home            # never your ~/.jk
git worktree add ../jk-reader <last commit the pinned release reads>
(cd ../jk-reader && curl -fsSL https://jumpkick.build/install.sh | JK_VERSION="$(cat .jk/ci-bootstrap-version)" bash \
   && "$JK_HOME/bin/jk" build --skip-tests && "$JK_HOME/bin/jk" install --skip-tests)   # a jk that reads the branch
"$JK_HOME/bin/jk" build --skip-tests && "$JK_HOME/bin/jk" install --skip-tests            # the branch, built by it
JK_HOME="$JK_HOME" jk engine stop --now
```

The failure mode names itself: a jk older than the lock's writer refuses the lock with
`written by jk <newer> and this is jk <older>; a newer lock format needs a newer reader, not a
re-lock` and points here, instead of suggesting `jk lock` — which would restate the branch's lock
in the old format and hand a newer tree an older lock. A manifest key the pinned release does not
know fails its parse with the key's name and the keys that table accepts; the remedy is the same
chain. The self-host job's bootstrap step is where either shows up first, which is why the pin
moves only after step 1's release is hosted.

### What the lane catches

Blocking is the measured position, not a default. The failures to expect are the shared test
suite's timing-sensitive population, because the lane runs `jk test` on a four-core runner: an
interrupt-ordering assertion or a cache-freshness assertion that holds on a quiet machine and
loses under load. Fix the flake, not the lane. Sonatype 429s appear in every run and the
resolver's retries absorb them.

### Cache and home isolation

The CI lane sets `JK_HOME` to a directory inside the workspace, so the store, the action cache and
the engine jar all come from that run. Nothing it reads survives from another job, and nothing it
writes leaks into one. Locally the same isolation is a `JK_HOME=/some/scratch/dir` prefix; do not
point a scratch run at your real `~/.jk`, and stop the scratch engine when you are done
(`JK_HOME=… jk engine stop --now`).

## Bootstrap

On a machine with no hosted client (macOS, Windows, Linux aarch64), Gradle produces the first
binary:

```bash
./gradlew dist installLocal
./install.sh build/dist/jk          # Windows: .\install.cmd build\dist\jk.exe
export PATH="$HOME/.jk/bin:$PATH"
```

`./scripts/bootstrap-from-gradle.sh` is that sequence. **G106** (`gradle-bootstrap-parity`) holds
the bootstrap to the tree on every `jk guard`: every Gradle `version` and `JkVersion.VERSION` equal
the root `jk.toml` version (buildSrc reads it through `JkTreeVersion`), and every
`gradle/libs.versions.toml` library sits at the version `jk-lock.toml` resolves, so the first client
Gradle produces starts the engine jar it built. The native client is preferred (self-heal,
sub-50 ms). **Windows also supports the thin JVM client** (`:cli:installDist` → `jk.bat`): Smart
App Control blocks unsigned `jk.exe`. `:engine:installLocal` runs the materialize through a client
that reports the engine jar's own version — the `:cli:nativeCompile` binary first, then the thin
launcher, then `build/dist/jk` — and fails, listing what it found, when none does.

Where jumpkick.build already serves a client (Linux amd64), install the released jk and let it
build the tree; the tree's own jk then takes over:

```bash
curl -fsSL https://jumpkick.build/install.sh | bash   # or JK_VERSION="$(cat .jk/ci-bootstrap-version)" bash
export PATH="$HOME/.jk/bin:$PATH"
jk engine status
jk build --skip-tests
jk install --skip-tests   # this checkout's client, engine and workers replace the release's
jk install --skip-tests   # once more: the release's client stops after the pass its engine ran
```

`jk install` is what makes the checkout self-hosting rather than merely built: from then on the
engine running your builds is the one you just compiled ([Install jk with jk](#install-jk-with-jk)).
The native client needs a GraalVM-capable JDK on the machine: `jk build` links it with the GraalVM
`--graal` / `GRAALVM_HOME` names, else an installed one.

A platform with no hosted client (Linux aarch64, macOS, Windows) bootstraps through Gradle, as
above, until a client is published; [releases](releases.md#platforms-without-a-hosted-client) says
how the first hosted client for a platform is produced.

### The JVM client

The client module is a plain JVM program, and two things run it without a native binary:

- **`jk install` writes `bin/jk-jvm`** beside the native client — the same `java -cp` launcher
  every application install gets, over the shelf's `jk-cli` jar and its closure — and writes it
  alone when the build linked no native binary. `jk-jvm --version` and `jk-jvm build` talk to the
  engine this same install put under `lib/jk-engine/`, so a checkout that has a jk of any kind can
  produce a JVM client for the machine it runs on. The tree's own build still links the native
  client, so on a machine with no GraalVM it provisions one (`--yes` answers the offer) rather
  than needing one preinstalled.
- **The release's JVM client**, `jk-<version>.jar` under `releases/<version>/`, is the CLI
  module's assembly under its shipped name; `install.sh` / `install.ps1` install it on a host with
  no native client, and the jar writes its own launcher (`jk self write-launcher`) over the JDK the
  installer found — see [releases](releases.md#platforms-without-a-hosted-client) and the [user
  install page](../user/install.md#the-jvm-client).
- **The published closure**, `cc.jumpkick:jk-cli:<version>` on `jumpkick.build/repo/`, runs from
  any project that names the repository and depends on it: `jk run . -- --version` executes
  `cc.jumpkick.cli.Jk` from the resolved jars, and `jk install` of that project writes a launcher
  of the project's name that does the same. Both were exercised against the hosted 0.13.2 closure
  and print `jk 0.13.2`.

What the JVM client cannot do yet is *bootstrap* a platform with no jk: a JVM client only pairs
with an engine of its own version, and the hosted pieces do not pair today — `repo/` holds the
0.13.2 closure while `releases/` holds the 0.13.3 engine (there is no `releases/0.13.2/`), so a
0.13.2 JVM client stops at `no build engine for jk 0.13.2` on its first build. The owner closes
the gap by publishing the 0.13.3 closure the way [releases](releases.md#platforms-without-a-hosted-client)
describes (`jk install`, then `scripts/publish-maven-repo.sh`), after which a JVM client from the
closure materializes the hosted engine with `jk self materialize <client> <engine-jar>`. Until
then the JVM client is produced by the tree's own build, and `ci-nightly.yml`'s `jvm-client` job
runs it nightly on a runner that brings a plain JDK and no GraalVM: `jk install --skip-tests`
writes the launcher, then `jk-jvm --version` and `scripts/ci-product-smoke.sh` with
`JK_BIN=jk-jvm` build a fresh sample through the installed engine under the same `JK_HOME`.
G57 holds the job, its lack of `setup-graalvm` and both commands in place.

## Dogfood (same tree)

After `jk` is on `PATH`, stay in this checkout:

```bash
jk lock
jk build --skip-tests
jk install
jk test                          # the fast tier, every module
jk test --profile integration    # the pre-merge bar
```

Workspace members: libraries, `clients/cli`, `clients/cli-terminal`, `clients/web`, `server/engine`
(assembly fat jar), the rule packs, and all first-party `plugins/*` workers (thin jars;
`PluginMain` implied by `jk-plugin.toml`). The CLI's integration classes spawn nested engines in
isolated sandboxes, so `jk test` in this tree never touches your resident engine's state.

## Default repositories

With no `[repositories]` table, remotes are **Maven Central then Google Maven** (local CAS /
`repos/*` / `~/.m2` still win first). R8 and Android coords do not need an extra google stanza.

## House-rule gate

The house rules in [code-as-art.md](code-as-art.md#the-guard-registry) are the rules of
`jk-guards.toml` and the guard tests under each module's `src/guard`. The model, module,
workspace and output lanes run in every `jk build`; the tree lane (text, metric, parity,
generated) and the fixture proofs run on `jk guard` and `--guard`, which is what CI's self-host
job runs after the build.

Nullness is part of the gate: every module with Java sources compiles with Error Prone + NullAway
at error severity through its own `[javac]` and `[javac.plugins.ErrorProne]` tables, and G53
(`null-marked-packages`) keeps every production package `@NullMarked`.

```bash
jk build                 # the lanes run with the build: model, module, workspace, tree, output
jk guard                 # the lanes alone
jk guard explain <id>    # one rule, with its source and its exemptions
```

A rule the closed vocabulary can express is a `[guards.<id>]` table; a rule it cannot is a guard
test — a `@Guard` method in `HouseRules`, `ParityRules` (`server/guard/src/guard`), `EngineRules`
or `CliRules`. The registry table in code-as-art.md says which enforces each letter, and G79
(`guard-letters-registry`) holds that table to the rules and guard tests that exist.

Each lane is keyed to what it reads — the model lane to the manifests and lock, a module lane to
that module's classes, the tree lane to the text corpus — so an unchanged input skips its lane and
an edit re-runs only the lanes it can affect. A lane writes a verdict, never an artifact, and only
a success is recorded, so a red lane goes red again instead of replaying itself. Every broken rule
is reported, each with its baseline state and the exemption path, and the machine view lands in
`target/jk-guards.sarif` and `target/jk-guards.jsonl`.

## Test tiers

The root manifest's `[test]` table is the fast tier and each `[profiles.*]` table is one slow
tier:

```bash
jk test                          # fast tier, untagged only
jk test --profile integration    # the pre-merge bar
jk test --profile slow           # framework / language e2e (nightly)
jk test --profile network        # talks to a real remote (nightly)
jk test --profile bench          # microbenchmarks (nightly)
jk test --all                    # everything, no tag filter
```

That table is the only copy: the engine's `tiers` validation (G23) re-derives the partition from it
and proves, over every subset of the tag vocabulary, that each is run by exactly one tier, and G52
(`test-tier-docs`) renders it into [test-suite-tiers.md](test-suite-tiers.md). A tag excluded from
the fast tier and included by no profile fails the build rather than silently never running.

## Install jk with jk

One command installs everything the tree builds:

```bash
jk install            # or --skip-tests; install runs the suite like `jk build`
```

| Module kind | What `jk install` does with it |
|---|---|
| Library (`shared/*`, `server/*`, rule packs) | Thin jar + POM onto the shelf, `~/.jk/store/repos/jk-local/<g>/<a>/<v>/` |
| Plugin worker (`plugins/*`) | Same shelf entry; launch rebuilds the runtime classpath from that POM |
| `server/engine` — declares `[install] product-lib = "jk-engine"` | Assembly jar materialized into `~/.jk/lib/jk-engine/`, pointer stamped by sha; the next client invocation takes over the resident engine |
| `clients/cli` — declares `[install] product-bin = "jk"` | Native binary replaces `~/.jk/bin/jk` (previous client parked as `.old`, `jkx` re-linked), the same swap `jk self update` performs — and `~/.jk/bin/jk-jvm` (`jk-jvm.cmd` on Windows) is written beside it: `cc.jumpkick.cli.Jk` on a JVM over the shelf's `jk-cli` closure. On a machine that built no native client (no GraalVM) the launcher is the whole install and the PATH client is left alone |

The shelf always holds the full entry; with the machine default `[m2] install` on, the same bytes
are also copied into `~/.m2` for Maven and Gradle builds beside jk. The two `[install]` keys exist
only because jk installs itself — no other project should declare them. The shelf is also what
`scripts/publish-maven-repo.sh` stages for `jumpkick.build/repo/` ([maven-repo](maven-repo.md)).

A module the forecast finds clean is still checked against its destination: a shelf entry, engine
jar or PATH client holding other bytes than the build output is reinstalled.

The pass is run by the engine the home names when it starts, and every artifact-shaped action key
names the engine that packaged the artifact. So when the pass materializes another engine than the
one that ran it, `jk install` runs one more pass under that engine (announced as *re-shelving*):
the jars the displaced engine packaged are packaged afresh and the ones that changed are re-shelved,
and one command leaves the shelf packaged by the tree's own engine. An engine serves only clients
of its own version, so a client of another version than the tree's (the released client a
contributor bootstraps from, the previous release the hosted CI installs with) stops after the
first pass, succeeds, and says which client runs the second — the PATH client the pass installed:
run `<home>/bin/jk install --skip-tests` once more, which is what the CI takeover step does. A
client older than this second pass stops silently after the first. The passes are bounded at two,
so when the re-shelving pass itself ends on yet another engine, `jk install` says so and asks for
that one more run.
`scripts/check-shelf-descriptors.sh "$JK_HOME"` then proves the shelf: every first-party worker
jar's root `jk-plugin.toml` names its own module's `[plugin] table`.

The first build under a freshly installed engine re-runs every plugin step, guard lane,
build-logic run and packaging step once: their action keys carry the installed engine's identity,
so an artifact the previous engine produced is never restored under the new one. Compile steps
keep their keys and stay cached, so the one-time re-run is packaging and verdicts, not a rebuild.

## Ship layout

JumpKick's ship shape is **native CLI** + **JVM engine** jar + PluginMain workers, plus the
**JVM client** jar for hosts with no native CLI. `jk build` writes it under `target/dist/`
(`.jk/after-build-dist.kts`), and `install.sh` installs it on a machine with no jk yet:

```bash
jk build --skip-tests
./install.sh target/dist/jk                     # the native client
./install.sh target/dist/lib/jk-<version>.jar   # the JVM client, on a host with no native one
```

```text
target/dist/
  jk                         # native CLI
  lib/
    jk-engine-<version>.jar  # JVM engine assembly (includes web SPA)
    jk-<version>.jar         # JVM client assembly (cc.jumpkick.cli.Jk and its closure)
```

The release workflow assembles `target/dist` per platform (`scripts/assemble-release-dir.sh`);
G75 (`ship-layout-installer-jk`) holds the installer and the dist script to one directory name.

## AOT during self-host / CI

Live engines train AOT on miss by default. Nested engines under `jk test` and short-lived CI
builds should not — use:

```bash
export JK_AOT_TRAIN=off   # train-on-miss off; still *use* existing caches
# full worker AOT off (map + train): JK_WORKER_AOT=off
```

Test forks set `-Djk.aot.train=off` automatically. For host engines in CI, export
`JK_AOT_TRAIN=off` before the job starts (or restart the engine after exporting).

## Wall-clock series

`scripts/dogfood-wall-measure.sh` builds this tree with jk on one machine and writes three rows —
rebuild, warm no-op, and one file really edited — to `target/dogfood-wall/`, each measured raw
(`[guards] on-build = false`) and with the house-rule guards on:

| File | For |
|---|---|
| `row.md` | people: the rows plus the machine they were measured on |
| `row.jsonl` | tooling: one `env` object, then one `measurement` per side per row, walls in seconds |

`.github/workflows/wall-measure.yml` runs it weekly and `scripts/wall-band.py` ratchets each row
against `wall-baseline.toml` ([perf](../perf/README.md)).
