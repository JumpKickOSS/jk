# Self-hosting JumpKick

This repository is a **dual-build tree**: the same product sources build under **Gradle**
(bootstrap + parity) and under **JumpKick** (self-host / day-to-day dogfood). Root
**`jk.toml`** is a full workspace (members under `shared/`, `server/`, `clients/`,
`plugins/*`). There is no separate `jk.jk` tree and no second product checkout required.

| System | Config | Typical output | Role today |
|--------|--------|----------------|------------|
| **Gradle** | `gradlew`, `build.gradle.kts`, `buildSrc/` | `*/build/` | Advisory parity run, curated and nightly integration tiers, bootstrap where no client is hosted |
| **JumpKick** | `jk.toml`, module manifests, `jk-lock.toml` | `target/` | Self-host compile/package/test/install, worker publish |

Do not treat dual-build as temporary scaffolding you must hide: both layouts live in this
repo until a deliberate Gradle cut-over (backlog below).

## Which build is the oracle

**Self-host is the merge authority.** Every pull request runs a `self-host` job that installs the
hosted release `.jk/ci-bootstrap-version` pins the way a user installs it
(`curl -fsSL https://jumpkick.build/install.sh | JK_VERSION=<pin> bash`) into an isolated `JK_HOME`,
builds the checkout with it, then hands the home to the checkout's own jk (`jk install`) and lets
that jk run the house-rule gate and the fast test tier. No step of the job runs Gradle. A guard
failure there is annotated with the guard that owns it, so a parity break reads as
`G51 checkGuardParity` rather than as build output.

**Gradle is a parity run.** `./gradlew checkFast` runs in the `gradle-parity` job, advisory
(`continue-on-error`): the unit tier under the second build, the buildSrc tests and the one letter
only Gradle's task graph can see (G64). Two Gradle pieces stay merge requirements: the curated
integration lane (`./gradlew curatedIntegrationTest`, the only integration coverage a pull request
gets, G63) and the nightly full tier, compared with jk's integration profile.

| Question | Answer today |
|---|---|
| What must be green to merge | the self-host job, the curated integration lane, the shell fixtures and the authorship scan |
| What produces the `jk` under test | the hosted release the pin names bootstraps; `jk install` then swaps in the checkout's own client, engine and workers |
| Where CI installs it | `$GITHUB_WORKSPACE/.ci-jk-home` — never the runner's `~/.jk` |
| What proves the graph is honest | `jk build` must not rewrite the committed `jk-lock.toml`; `cmp` of `target/dist/jk` against `$JK_HOME/bin/jk` and the engine sha against `jk-engine.toml` prove the takeover |
| Wall-clock comparison | `.github/workflows/wall-measure.yml`, weekly, informational |

Guard **G57** (`ci-cadence`, both builds) keeps the self-host job, its isolated `JK_HOME`, the four
verbs it runs (`jk build`, `jk install`, `jk guard`, `jk test`), its bootstrap through `install.sh`
at the pinned version, and the scheduled wall measurement in place. It fails on a `gradlew`
anywhere in that job, on a `JK_VERSION=<digits>` literal in any workflow, and on
`continue-on-error` on any job but `gradle-parity`. That flag is how a merge requirement becomes a
courtesy run without anyone deleting a job, and the Gradle job is the one courtesy run by design.
The weekly wall measurement keeps its own `continue-on-error` in `wall-measure.yml`, where a wall
time is a record rather than a verdict.

### The bootstrap pin

`.jk/ci-bootstrap-version` holds one line: the release every workflow bootstraps from. It is the
only place that version is written. `ci.yml`'s self-host job and `release.yml`'s hosted lanes read
it with `tr -d '[:space:]' < .jk/ci-bootstrap-version`, and G57 refuses a workflow that spells the
version itself, a pin that is not one `x.y.z`, and a pin newer than `jk.toml`'s own `version` — a
bootstrap release is cut from a tree, so it is never ahead of one.

After a release ships to jumpkick.build:

1. Prove the release can build this tree, from a scratch home that is not your `~/.jk`:
   ```bash
   export HOME=$PWD/../bootstrap-probe JK_HOME=$HOME/.jk
   curl -fsSL https://jumpkick.build/install.sh | JK_VERSION=<new> bash
   "$JK_HOME/bin/jk" build --skip-tests && "$JK_HOME/bin/jk" install --skip-tests && "$JK_HOME/bin/jk" guard
   ```
2. Write `<new>` to `.jk/ci-bootstrap-version`.
3. In `release.yml`, flip `bootstrap: gradle` to `hosted` for every platform whose client
   `releases/<new>/SHA256SUMS` lists; the rows say why each platform is where it is.
4. `jk guard`, then commit the pin and the workflow together.

### What the lane has actually caught

Blocking is the measured position, not a default. Since the job landed it has produced exactly one
change-attributable failure, and Gradle's gate was green throughout it: CI installs jk into
`.ci-jk-home` **inside the checkout**, and both tree walkers read that store as source, so the
house-rule gate failed on a metadata index's prose. Gradle never saw it because its `JK_HOME` is
outside the checkout. That is the whole argument for the lane in one bug.

The failures to expect are neither that nor the network. Sonatype 429s appear in every run (3–17 of
them) and retries have absorbed all of them so far — none has failed the job. What does fail it is
the shared test suite's timing-sensitive population, because the lane runs `jk test` on the same
four-core runner: an interrupt-ordering assertion or a cache-freshness assertion that holds on a
quiet machine and loses under load. Those block the Gradle gate identically, so demoting self-host
would not buy a single unblocked merge — it would only remove the evidence. Fix the flakes, not the
lane.

### Cache and home isolation

The CI lane sets `JK_HOME` to a directory inside the workspace, so the store, the action cache and
the engine jar all come from that run. Nothing it reads survives from another job, and nothing it
writes leaks into one. Locally the same isolation is a `JK_HOME=/some/tmp/dir` prefix; do not point
a scratch run at your real `~/.jk`.

### What deleting Gradle still waits on

Self-host is the merge authority on Linux. Deleting Gradle from the tree, rather than demoting it,
waits on all five of these being facts:

1. **Bootstrap without in-tree Gradle** — holds on Linux: the self-host job installs the pinned
   release and never runs `./gradlew`.
2. **Coverage parity** — `jk test --profile integration` runs every class and every test case
   `./gradlew integrationTest` runs, with the same pass/fail verdict on the same commit.
   Measured, not asserted: `scripts/measure-tier-parity.sh` runs both tiers and compares their
   JUnit XML; the nightly `integration` job runs it and fails on any difference.
3. **Guard parity with no exceptions that could be ported** — `guard-parity.txt` holds only
   letters that genuinely cannot live in both builds.
4. **Green on every supported OS** — the self-host lane passes on Linux, macOS and Windows, not
   only the Linux runner it starts on. macOS Intel, Linux aarch64 and Windows have no hosted
   client at the pin, so their release lanes bootstrap from Gradle.
5. **A wall baseline that holds** — the scheduled measurement has enough history that a regression
   is distinguishable from runner noise, and the rebuild row has not regressed against it.

Until all five hold, Gradle stays in the tree: the advisory parity run, and the bootstrap for
the platforms without a hosted client.

## Bootstrap

Install the released jk and let it build the tree; the tree's own jk then takes over:

```bash
curl -fsSL https://jumpkick.build/install.sh | bash   # or JK_VERSION="$(cat .jk/ci-bootstrap-version)" bash
export PATH="$HOME/.jk/bin:$PATH"
jk engine status
jk build --skip-tests
jk install --skip-tests   # this checkout's client, engine and workers replace the release's
```

`jk install` is what makes the checkout self-hosting rather than merely built: from then on the
engine running your builds is the one you just compiled ([Install jk with jk](#install-jk-with-jk-no-gradle)).

The Gradle path serves two cases — a platform with no hosted client (macOS Intel, Linux aarch64,
Windows), and a branch whose manifests or lock the released engine cannot read yet:

```bash
# Native (Graal). Windows thin-client path is in CONTRIBUTING.
./gradlew dist installLocal
./install.sh build/dist/jk
```

The native client is preferred (self-heal, sub-50 ms). **Windows also supports the thin JVM
client** (`:cli:installDist` → `jk.bat`): Smart App Control blocks unsigned `jk.exe`.
`:engine:installLocal` runs the materialize through a client that reports the engine jar's own
version — the `:cli:nativeCompile` binary first, then the thin launcher, then `build/dist/jk` —
and fails, listing what it found, when none does. A `build/dist` left over from an older version
is skipped rather than handed a new engine (`jk self materialize` refuses that too).
The thin client cannot self-heal a missing engine — materialize from this checkout.

Helper for the Gradle path: `./scripts/bootstrap-from-gradle.sh`.

## Dogfood (same tree)

After `jk` is on `PATH`, stay in this checkout:

```bash
jk lock
jk build --skip-tests
jk install

# Pure-jk unit suite (includes clients/cli — nested engines use isolated JK_STATE_DIR)
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*'
```

Workspace members: libraries, `clients/cli`, `clients/web`, `server/engine` (assembly fat
jar), and all first-party `plugins/*` workers (thin jars; `PluginMain` implied by `jk-plugin.toml`).

### `jk test` coverage

| Modules | Under `jk test` |
|---|---|
| `shared/*`, `server/{io,resolver,toolchain,engine}`, `plugins/*`, `clients/cli` | **Green** dogfood / CI (CLI uses isolated nested engines) |

On a pull request the bar is `jk test` in the self-host job plus the curated integration lane;
`./gradlew checkAll` stays the local pre-merge sweep for wire/engine/CLI changes
(see [AGENTS.md](../../AGENTS.md) and [test-suite-tiers.md](test-suite-tiers.md)).

## Coexistence notes

- **Two output roots** — Gradle writes under `build/`; JumpKick under `target/`. They do
  not share class trees. Clean one system does not wipe the other.
- **One Gradle daemon build at a time** per checkout (OS lock in `settings.gradle.kts`).
  Parallel Gradle work needs a **git worktree**, not a second tool name.
- **Optional pure-jk worktree** — still fine for isolation (`git worktree add …`), but not
  required for self-host. Prefer dogfooding in the primary clone after bootstrap.
- **Catalog short names** — every short name a self-host manifest uses resolves from the
  downloaded global registry (`jk library update`); the tree carries no `jk-libs.toml`.

## Default repositories

With no `[repositories]` table, remotes are **Maven Central then Google Maven** (local CAS /
`repos/*` / `~/.m2` still win first). R8 and Android coords do not need an extra google stanza.

## House-rule gate

The house rules in [code-as-art.md](code-as-art.md#the-guard-registry) are jk's:
the rules of `jk-guards.toml` and the guard tests under each module's `src/guard`.
The model, module, workspace and output lanes run in every `jk build`; the tree
lane (text, metric, parity, generated) and the fixture proofs run on `jk guard`
and `--guard`, which is what CI's self-host job runs after the build. Gradle keeps the one letter only its
task graph can see (G64, a disabled `JavaCompile`) and two registry tasks:
`checkGuardRegistry` renders the table, and `checkGuardParity` fails when a letter
has a Gradle task and no jk side, or when `jk-guards.toml` differs from the file
the last `jk build` recorded a digest of under `target/`. Gradle running the rule
file itself is the follow-up `guard-parity.txt` names.

Nullness is enforced by both builds too. The modules `NullMarking.enforcedRoots` names
compile with Error Prone + NullAway at error severity under Gradle's `jk.nullmarked-conventions`
and under `jk build` through each module's `[javac]` table, with the two processors locked at
the catalog's pins (`catalog-lock` keeps them equal). `checkGuardParity` fails when a module
carries the table and not the convention, or the reverse.

```bash
jk build                 # the lanes run with the build: model, module, workspace, tree, output
jk guard                 # the lanes alone
jk guard explain <id>    # one rule, with its source and its exemptions
```

A rule the closed vocabulary can express is a `[guards.<id>]` table; a rule it
cannot is a guard test — a `@Guard` method in `HouseRules`, `ParityRules`
(`server/guard/src/guard`), `EngineRules` or `CliRules`. Both builds cover both
homes; the registry in code-as-art.md says which side each letter lives on, and
G51 fails when a letter has a Gradle task and no jk side.

Each lane is keyed to what it reads — the model lane to the manifests and lock,
a module lane to that module's classes, the tree lane to the text corpus — so
an unchanged input skips its lane and an edit re-runs only the lanes it can
affect. A lane writes a verdict, never an artifact, and only a success is
recorded, so a red lane goes red again instead of replaying itself. Every broken
rule is reported, each with its baseline state and the exemption path, and the
machine view lands in `target/jk-guards.sarif` and `target/jk-guards.jsonl`.

One letter stays Gradle-only because only Gradle's task graph can answer it:
whether a `JavaCompile` task is enabled (G64). Everything else is enforced by
`jk build`; `./gradlew checkFast` is the unit tier plus that task and the two
registry tasks.

## Test tiers

Gradle's `test` / `integrationTest` / `slowTest` / `networkTest` / `benchTest`
tasks are, on the jk side, the root manifest's `[test]` baseline plus one
`[profiles.*]` per slow tag:

```bash
jk test                          # fast tier, untagged only
jk test --profile integration    # the pre-merge bar
jk test --profile slow           # framework / language e2e (nightly)
jk test --profile network        # talks to a real remote (nightly)
jk test --profile bench          # microbenchmarks (nightly)
jk test --all                    # everything, no tag filter
```

That table is the only copy: the engine's `tiers` validation (G23) re-derives the
partition from it and proves, over every subset of the tag vocabulary, that each
is run by exactly one tier. A tag excluded from the fast tier and included by no profile fails the
build rather than silently never running.

## Still Gradle (by design)

| Task | Why |
|---|---|
| Full `./gradlew test` | The advisory `gradle-parity` job and the nightly tier comparison |
| `./gradlew dist` / `nativeCompile` | Bootstrap where no client is hosted, or for a branch the released engine cannot read |
| `./gradlew installLocal` | Parity path for workers + engine; `jk install` on the tree does the same and the client too |
| `checkNoDisabledCompile` (G64), `checkGuardParity`, `checkGuardRegistry` | The one letter only Gradle's task graph can see, and the registry's two tasks — see [House-rule gate](#house-rule-gate) |

### Future cut-over (backlog)

Steps 1 and 2 hold; 3 and 4 wait on the five criteria in
[What deleting Gradle still waits on](#what-deleting-gradle-still-waits-on):

1. ~~**Bootstrap without in-tree Gradle** — install `jk` from a release so a clean product tree
   never needs `./gradlew`.~~ (done: the pinned hosted release, `.jk/ci-bootstrap-version`)
2. ~~**CI primary = pure-jk** — Gradle job becomes optional `parity`.~~ (done: `gradle-parity`, advisory)
3. **Relocate Gradle** for oracle builds only (if still wanted).
4. **Product tree Gradle-free** — delete `gradlew`, `buildSrc/`, module `build.gradle.kts`.

Until then: pure-jk dogfood is required for product work that touches runtime; Gradle remains
valid for bootstrap and comparison.

## Install jk with jk (no Gradle)

One command installs everything the tree builds:

```bash
jk install            # or --skip-tests; install runs the suite like `jk build`
```

| Module kind | What `jk install` does with it |
|---|---|
| Library (`shared/*`, `server/*`, rule packs) | Thin jar + POM onto the shelf, `~/.jk/store/repos/jk-local/<g>/<a>/<v>/` |
| Plugin worker (`plugins/*`) | Same shelf entry; launch rebuilds the runtime classpath from that POM |
| `server/engine` — declares `[install] product-lib = "jk-engine"` | Assembly jar materialized into `~/.jk/lib/jk-engine/`, pointer stamped by sha; the next client invocation takes over the resident engine |
| `clients/cli` — declares `[install] product-bin = "jk"` | Native binary replaces `~/.jk/bin/jk` (previous client parked as `.old`, `jkx` re-linked), the same swap `jk self update` performs |

The shelf always holds the full entry; with the machine default `[m2] install` on, the same bytes
are also copied into `~/.m2` for Maven and Gradle builds beside jk. The two `[install]` keys exist
only because jk installs itself — no other project should declare them.

A module the forecast finds clean is still checked against its destination: a shelf entry, engine
jar or PATH client holding other bytes than the build output is reinstalled.

## Ship layout

JumpKick’s ship shape is **native CLI** + **JVM engine** jar + PluginMain workers. The pure-jk
build writes it under `target/dist/`, and `install.sh` installs it on a machine with no jk yet:

```bash
jk build --skip-tests
./install.sh target/dist/jk
```

```text
target/dist/
  jk                         # native CLI
  lib/
    jk-engine-<version>.jar  # JVM engine assembly (includes web SPA)
```

Gradle produces the same layout under `build/dist/` (`./gradlew dist`); the release lanes with no
hosted client bootstrap from it, and Windows ships it. Every other lane, CI's self-host job
included, bootstraps from the hosted release and ships what `jk build` writes under `target/dist`.

## AOT during self-host / CI

Live engines train AOT on miss by default. Nested engines under `jk test` and short-lived CI
builds should not — use:

```bash
export JK_AOT_TRAIN=off   # train-on-miss off; still *use* existing caches
# full worker AOT off (map + train): JK_WORKER_AOT=off
```

Pure-jk test forks set `-Djk.aot.train=off` automatically. For host engines in CI, export
`JK_AOT_TRAIN=off` before the job starts (or restart the engine after exporting).

## Wall-clock comparison

`scripts/dogfood-wall-measure.sh` builds this tree both ways on one machine and writes three rows —
rebuild, warm no-op, and one file really edited — to `build/dogfood-wall/`:

| File | For |
|---|---|
| `row.md` | people: the rows plus the machine they were measured on |
| `row.jsonl` | tooling: one `env` object, then one `measurement` per side per row, walls in seconds |

`.github/workflows/wall-measure.yml` runs it weekly and uploads both. It is **informational** and
`continue-on-error`: a hosted runner's wall time moves with the runner it lands on, so a single row
proves nothing about a change. A threshold belongs here only once the series is long enough to say
what normal is — criterion 5 above.

## Roadmap (summary)

1. ~~Default Google Maven~~ (done)
2. ~~`clients/web` + engine assembly~~ (done)
3. ~~`jk install`~~ (done)
4. ~~local worker publish via `jk install`~~ (done; a future ship-layout command will replace the retired `jk release` name)
5. ~~All first-party plugins on the workspace~~ (done)
6. ~~Curated `jk test` + CI self-host dogfood~~ (done)
7. ~~Engine + plugins under pure-jk `jk test`~~ (done)
8. ~~Native CLI via `jk native`~~ (done)
9. ~~`clients/cli` under pure-jk `jk test` (nested-engine isolation)~~ (done)
10. ~~Same-repo dual-build (`jk.toml` + Gradle; no `jk.jk`)~~ (done)
11. **Mill-class test parallelism** — isolation + default `-w` / `--parallel-tests` policy
    (see KanArtist `projects/jk/docs/perf/test-parallelization.md`)
12. **Gradle cut-over** — CI primary is pure-jk; deleting Gradle from the tree is the remainder ([above](#future-cut-over-backlog))
