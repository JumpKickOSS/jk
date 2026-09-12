# Self-hosting JumpKick

This repository is a **dual-build tree**: the same product sources build under **Gradle**
(bootstrap + parity) and under **JumpKick** (self-host / day-to-day dogfood). Root
**`jk.toml`** is a full workspace (members under `shared/`, `server/`, `clients/`,
`plugins/*`). There is no separate `jk.jk` tree and no second product checkout required.

| System | Config | Typical output | Role today |
|--------|--------|----------------|------------|
| **Gradle** | `gradlew`, `build.gradle.kts`, `buildSrc/` | `*/build/` | Bootstrap `jk`, unit/integration CI, parity oracle |
| **JumpKick** | `jk.toml`, module manifests, `jk-lock.toml` | `target/` | Self-host compile/package/test/install, worker publish |

Do not treat dual-build as temporary scaffolding you must hide: both layouts live in this
repo until a deliberate Gradle cut-over (backlog below).

## Which build is the oracle

**Gradle is the bootstrap and the merge authority.** It produces the `jk` a self-host build needs,
and `./gradlew checkFast` plus the curated integration lane are what a pull request has to pass.

**Self-host is a first-class oracle, not a courtesy run.** Every pull request also runs a
`self-host` job that bootstraps from that workflow's own Gradle artifacts, installs into an
isolated `JK_HOME`, and then uses that `jk` to build the checkout, run its fast test tier, and run
the house-rule gate. A guard failure there is annotated with the guard that owns it, so a parity
break reads as `G51 checkGuardParity` rather than as build output.

| Question | Answer today |
|---|---|
| What must be green to merge | Gradle: `checkFast`, the curated integration lane, and the self-host job |
| What produces the `jk` under test | Gradle `dist` + `installLocal`, in the same workflow run |
| Where CI installs it | `$GITHUB_WORKSPACE/.ci-jk-home` — never the runner's `~/.jk` |
| What proves the graph is honest | `jk build` must not rewrite the committed `jk-lock.toml` |
| Wall-clock comparison | `.github/workflows/wall-measure.yml`, weekly, informational |

Guard **G57** (`checkCiCadence`, both builds) keeps the self-host job, its isolated `JK_HOME`, both
verbs it runs, and the scheduled wall measurement in place: removing any of them fails the build
rather than quietly retiring the oracle. It also rejects `continue-on-error` anywhere in `ci.yml`.
That flag is how a merge requirement becomes a courtesy run without anyone deleting a job, and it
is the one demotion no other assertion here would notice. The weekly wall measurement keeps its own
`continue-on-error` in `wall-measure.yml`, where a wall time is a record rather than a verdict.

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

### When self-host can replace Gradle as the primary oracle

Judgement is not a criterion. All five of these have to be facts before the Gradle job becomes an
optional parity run:

1. **Bootstrap without in-tree Gradle** — a published release, or a sibling checkout, installs a
   `jk` capable of building this tree, so a clean product checkout never runs `./gradlew`.
2. **Coverage parity** — `jk test --profile integration` runs every class and every test case
   `./gradlew integrationTest` runs, with the same pass/fail verdict on the same commit.
   Measured, not asserted: `scripts/test-tier-parity.sh` runs both tiers and compares their
   JUnit XML; the nightly `integration` job runs it and fails on any difference.
3. **Guard parity with no exceptions that could be ported** — `guard-parity.txt` holds only
   letters that genuinely cannot live in both builds.
4. **Green on every supported OS** — the self-host lane passes on Linux, macOS and Windows, not
   only the Linux runner it starts on.
5. **A wall baseline that holds** — the scheduled measurement has enough history that a regression
   is distinguishable from runner noise, and the rebuild row has not regressed against it.

Until all five hold, Gradle stays the trusted bootstrap and the merge authority.

## Bootstrap (chicken-egg)

You need a working `jk` before pure-jk can build the monorepo.

```bash
# Native (Graal). Windows thin-client path is in CONTRIBUTING.
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.jk/bin:$PATH"
jk engine status
```

The native client is preferred (self-heal, sub-50 ms). **Windows also supports the thin JVM
client** (`:cli:installDist` → `jk.bat`): Smart App Control blocks unsigned `jk.exe`.
`:engine:installLocal` runs the materialize through a client that reports the engine jar's own
version — the `:cli:nativeCompile` binary first, then the thin launcher, then `build/dist/jk` —
and fails, listing what it found, when none does. A `build/dist` left over from an older version
is skipped rather than handed a new engine (`jk self materialize` refuses that too).
The thin client cannot self-heal a missing engine — materialize from this checkout.

Once a release is published this section shrinks to one line: install with
`curl -fsSL https://jumpkick.build/install.sh | bash` and let the binary bootstrap its own engine.

Helper: `./scripts/bootstrap-from-gradle.sh`.

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

Gradle tests remain the pre-merge bar for many paths (`./gradlew test` / `checkAll` —
see [AGENTS.md](../../AGENTS.md) and [test-suite-tiers.md](test-suite-tiers.md)).

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
| Full `./gradlew test` | Parity oracle + bootstrap CI source of truth |
| `./gradlew dist` / `nativeCompile` | Bootstrap binary when no prior `jk` install exists |
| `./gradlew installLocal` | Parity path for workers + engine; `jk install` on the tree does the same and the client too |
| `checkNoDisabledCompile` (G64), `checkGuardParity`, `checkGuardRegistry` | The one letter only Gradle's task graph can see, and the registry's two tasks — see [House-rule gate](#house-rule-gate) |

### Future cut-over (backlog)

Not started — keep dual-build green until this epic is scheduled. The gate on starting it is the
five criteria in [Which build is the oracle](#when-self-host-can-replace-gradle-as-the-primary-oracle):

1. **Bootstrap without in-tree Gradle** — install `jk` from a release (or a sibling Gradle-only
   checkout) so a clean product tree never needs `./gradlew`.
2. **CI primary = pure-jk** — Gradle job becomes optional `parity`.
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

Gradle produces the same layout under `build/dist/` (`./gradlew dist`), which is what CI's
self-host lane still bootstraps from until a release built by jk is hosted.

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
12. **Gradle cut-over** — backlog ([above](#future-cut-over-backlog))
