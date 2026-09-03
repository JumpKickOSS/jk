# Self-hosting JumpKick

This repository is a **dual-build tree**: the same product sources build under **Gradle**
(bootstrap + parity) and under **JumpKick** (self-host / day-to-day dogfood). Root
**`jk.toml`** is a full workspace (members under `shared/`, `server/`, `clients/`,
`plugins/*`). There is no separate `jk.jk` tree and no second product checkout required.

| System | Config | Typical output | Role today |
|--------|--------|----------------|------------|
| **Gradle** | `gradlew`, `build.gradle.kts`, `buildSrc/` | `*/build/` | Bootstrap `jk`, unit/integration CI, parity oracle |
| **JumpKick** | `jk.toml`, module manifests, `jk-libs.toml`, `jk-lock.toml` | `target/` | Self-host compile/package/test/install, worker publish |

Do not treat dual-build as temporary scaffolding you must hide: both layouts live in this
repo until a deliberate Gradle cut-over (backlog below).

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
`:engine:installLocal` uses native when `dist` already built one, otherwise the thin launcher.
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
- **Catalog pins** — workspace short names used by self-host manifests are pinned in
  root **`jk-libs.toml`** (name → `group:artifact`; versions stay in manifests / lock).

## Default repositories

With no `[repositories]` table, remotes are **Maven Central then Google Maven** (local CAS /
`repos/*` / `~/.m2` still win first). R8 and Android coords do not need an extra google stanza.

## House-rule gate

The guards in [code-as-art.md](code-as-art.md#the-guard-registry) run under
**both** builds. Gradle runs them as `tasks.registering` blocks wired to `check`
and `jar`; jk runs the tree-wide ones from `.jk/after-build.kts` at the workspace
root, and the rest are ordinary tests in the module they govern.

```bash
jk build                 # the gate runs last, after every module
jk test                  # the single-module rules, with everything else
```

A rule that reads more than one module belongs in the gate; a rule whose whole
corpus is one module belongs beside that module's code, as a test
(`ForecastKeyParityTest`, `SpikeCacheTempDirTest`, `CliSourceRulesTest`,
`IdeClientWiringTest`). Both builds cover both homes.

`after-build` is the root's own anchor: the script runs once per build, after
every member, with the whole tree on disk. Its action key covers every file in
the checkout except build output and VCS metadata, so an unchanged tree skips it
(~60 ms) and any edit re-runs it (~9 s). It writes nothing to `outDir`; the cache
records that as a verdict rather than an artifact, and records only successes, so
a red gate goes red again instead of replaying itself. It reports every broken
rule in one message rather than the first to fire.

Two arms stay Gradle-only because they read files `maven-publish` generates and
jk does not produce until `jk publish`: `checkPublishedPomCoordinates` and the
publication arm of `checkTestFixturesStayOutOfProduction`. Everything else is
enforced by whichever build you run.

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

That table is the only copy: the gate re-derives the partition from it and
proves, over every subset of the tag vocabulary, that each is run by exactly one
tier. A tag excluded from the fast tier and included by no profile fails the
build rather than silently never running.

## Still Gradle (by design)

| Task | Why |
|---|---|
| Full `./gradlew test` | Parity oracle + bootstrap CI source of truth |
| `./gradlew dist` / `nativeCompile` | Bootstrap binary when no prior `jk` install exists |
| `./gradlew installLocal` | Workers + engine materialize/bounce; or `jk install` after pure-jk build |
| Two publication-reading guard arms | See [House-rule gate](#house-rule-gate) |

### Future cut-over (backlog)

Not started — keep dual-build green until this epic is scheduled:

1. **Bootstrap without in-tree Gradle** — install `jk` from a release (or a sibling Gradle-only
   checkout) so a clean product tree never needs `./gradlew`.
2. **CI primary = pure-jk** — Gradle job becomes optional `parity`.
3. **Relocate Gradle** for oracle builds only (if still wanted).
4. **Product tree Gradle-free** — delete `gradlew`, `buildSrc/`, module `build.gradle.kts`.

Until then: pure-jk dogfood is required for product work that touches runtime; Gradle remains
valid for bootstrap and comparison.

## Install workers (no Gradle)

After `jk build` produces thin PluginMain jars under `plugins/*/target/` (or
`target/plugins/…`):

```bash
jk install
```

Each worker's thin jar and POM land in
`~/.jk/store/repos/jk-local/cc/jumpkick/jk-<name>/<ver>/` (Maven layout;
same as Gradle `installLocal`). Launch rebuilds the runtime classpath from that POM
and the jars already in the local repo.

## Ship layout

JumpKick’s ship shape is **native CLI** + **JVM engine** jar + PluginMain workers.
Until a dedicated command replaces the old `jk release` name, produce that layout
with Gradle and install workers with `jk install`:

```bash
./gradlew dist installLocal
./install.sh build/dist/jk
jk install   # after a pure-jk build, refreshes repos/jk-local workers
```

```text
build/dist/
  jk                         # native CLI
  lib/
    jk-engine-<version>.jar  # JVM engine assembly (includes web SPA)
```

## AOT during self-host / CI

Live engines train AOT on miss by default. Nested engines under `jk test` and short-lived CI
builds should not — use:

```bash
export JK_AOT_TRAIN=off   # train-on-miss off; still *use* existing caches
# full worker AOT off (map + train): JK_WORKER_AOT=off
```

Pure-jk test forks set `-Djk.aot.train=off` automatically. For host engines in CI, export
`JK_AOT_TRAIN=off` before the job starts (or restart the engine after exporting).

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
