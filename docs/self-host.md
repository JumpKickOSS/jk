# Self-hosting JumpKick (`jk-jk`)

Build JumpKick with JumpKick. **Gradle and pure-jk coexist** in this tree for now: Gradle is
bootstrap + parity oracle; day-to-day monorepo dogfood is pure-jk (`jk build` / `jk test` /
`jk release`). A full cut-over (Gradle only in a sibling `jk-gradle` checkout, product tree
Gradle-free) is **backlog** — see [Future cut-over](#future-cut-over-backlog).

## Worktree layout

Recommended dual-checkout setup:

| Path | Role |
|---|---|
| `…/oss/jk` | Primary product tree (Gradle + `jk.toml` dual-build) |
| `…/oss/jk-jk` | Optional worktree for pure-jk-only dogfood |

```bash
# From the primary clone (once):
git worktree add -b self-host-jk-jk ../jk-jk main
```

Both trees share history; use **one Gradle build at a time** in `jk` only. Prefer `jk` for
compile/package dogfood in `jk-jk`.

## Bootstrap (chicken-egg)

You need a working `jk` binary before the worktree can build itself.

```bash
# In …/oss/jk (needs Graal for native dist; or use the thin path in CONTRIBUTING)
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.jk/bin:$PATH"   # or versions/<v>/bin
jk engine status
```

Thin JVM alternative (no Graal): see [CONTRIBUTING.md](../CONTRIBUTING.md) path B
(`:cli:installDist` + `:engine:shadowJar` + `jk self materialize`).

## Dogfood in `jk-jk` (or primary tree)

```bash
cd ../jk-jk   # or stay in jk after install
jk lock
jk build --skip-tests
jk plugin install-local

# Pure-jk unit suite (includes clients/cli — nested engines use isolated JK_STATE_DIR)
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*'

jk release --skip-tests
./install.sh target/dist/jk
```

Current workspace: libraries, `clients/cli`, `clients/web`, `server/engine` (assembly fat jar),
and **all** first-party `plugins/*` workers (`assembly` + `PluginMain`).

### `jk test` coverage notes

| Modules | Under `jk test` |
|---|---|
| `shared/*`, `server/{io,resolver,toolchain,engine}`, `plugins/*`, `clients/cli` | **Green** dogfood / CI (CLI uses isolated nested engines) |

## Default repositories

With no `[repositories]` table, remotes are **Maven Central then Google Maven** (local CAS /
`repos/*` / `~/.m2` still win first). R8 and Android coords do not need an extra google stanza.

## Still Gradle (by design, for now)

Dual-build is intentional: the same sources build under Gradle **and** pure-jk. Do not remove
Gradle files until the cut-over epic lands.

| Task | Why |
|---|---|
| Full `./gradlew test` | Parity oracle + bootstrap CI source of truth |
| `./gradlew dist` / `nativeCompile` | Bootstrap binary when no prior `jk` install exists |
| `./gradlew installLocal` | Or `jk plugin install-local` after pure-jk build |

### Future cut-over (backlog)

Not started — keep dual-build green until this epic is scheduled:

1. **Bootstrap without in-tree Gradle** — install `jk` from a release (or a sibling `jk-gradle`
   checkout) so a clean product tree never needs `./gradlew`.
2. **CI primary = pure-jk** — Gradle job becomes optional `parity`.
3. **Relocate Gradle** to `jk-gradle` (or a comparison repo) for oracle builds only.
4. **Product tree Gradle-free** — delete `gradlew`, `buildSrc/`, module `build.gradle.kts`.

Until then: pure-jk dogfood is required for product tickets that touch runtime; Gradle remains
valid for bootstrap and comparison.

## Side-load workers (no Gradle)

After `jk build` produces assembly jars under `plugins/*/target/`:

```bash
jk plugin install-local
# or: jk plugin install-local --modules test-runner,java-compiler
# or: jk plugin install-local --dry-run
```

Copies each PluginMain assembly jar into
`~/.jk/cache/repos/local/cc/jumpkick/jk-<name>/<ver>/` (same layout as Gradle
`installLocal`) so the engine can locate workers.

## Ship layout (`jk release` / `jk dist`)

JumpKick’s ship shape is fixed: **native CLI** + **JVM engine** jar + PluginMain workers.
There is no `--native` / `--jvm` mode switch.

After a bootstrap `jk` is on PATH (GraalVM on PATH for the native step):

```bash
jk release --skip-tests
# alias: jk dist --skip-tests
# If no native CLI is present yet and clients/cli has [native] always = true,
# release runs `jk native --skip-tests` first. Use --skip-native to stage the
# currently running jk as a bootstrap client only.
./install.sh target/dist/jk
```

Produces:

```text
target/dist/
  jk                         # native CLI (preferred) or bootstrap client
  lib/
    jk-engine-<version>.jar  # JVM engine assembly (includes web SPA)
```

Also runs `jk plugin install-local` for workspace PluginMain workers.

Flags: `--out <dir>`, `--skip-tests`, `--skip-native`, `--dry-run`, `--modules <sel>`.

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
3. ~~`jk plugin install-local`~~ (done)
4. ~~`jk release` / `jk dist`~~ (done)
5. ~~All first-party plugins on the workspace~~ (done)
6. ~~Curated `jk test` + CI self-host dogfood~~ (done: shared/* + server libs)
7. ~~Engine + plugins under pure-jk `jk test`~~ (done)
8. ~~Native CLI via `jk native` / `jk release` (auto-native when eligible)~~ (done)
9. ~~`clients/cli` under pure-jk `jk test` (nested-engine isolation)~~ (done)
10. **Mill-class test parallelism** — isolation + default `-w` / `--parallel-tests` policy
    ([test-parallelization.md](perf/test-parallelization.md))
11. **Gradle cut-over** — backlog ([above](#future-cut-over-backlog))

Details: session plan *Self-host JumpKick in ../jk-jk*.
