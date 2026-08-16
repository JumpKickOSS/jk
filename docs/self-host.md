# Self-hosting JumpKick

This repository is a **dual-build tree**: the same product sources build under **Gradle**
(bootstrap + parity) and under **JumpKick** (self-host / day-to-day dogfood). Root
**`jk.toml`** is a full workspace (members under `shared/`, `server/`, `clients/`,
`plugins/*`). There is no separate `jk.jk` tree and no second product checkout required.

| System | Config | Typical output | Role today |
|--------|--------|----------------|------------|
| **Gradle** | `gradlew`, `build.gradle.kts`, `buildSrc/` | `*/build/` | Bootstrap `jk`, unit/integration CI, parity oracle |
| **JumpKick** | `jk.toml`, module manifests, `jk-libs.toml`, `jk-lock.toml` | `target/` | Self-host compile/package/test/release, worker install |

Do not treat dual-build as temporary scaffolding you must hide: both layouts live in this
repo until a deliberate Gradle cut-over (backlog below).

## Bootstrap (chicken-egg)

You need a working `jk` before pure-jk can build the monorepo.

```bash
# In this clone (Graal for native dist; thin path in CONTRIBUTING)
./gradlew dist installLocal
./install.sh build/dist/jk
export PATH="$HOME/.local/bin:$PATH"
jk engine status
```

Thin JVM alternative (no Graal): [CONTRIBUTING.md](../CONTRIBUTING.md) path B
(`:cli:installDist` + `:engine:shadowJar` + `jk self materialize`).

Helper: `./scripts/bootstrap-from-gradle.sh`.

## Dogfood (same tree)

After `jk` is on `PATH`, stay in this checkout:

```bash
jk lock
jk build --skip-tests
jk plugin install-local

# Pure-jk unit suite (includes clients/cli — nested engines use isolated JK_STATE_DIR)
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain,server/engine,clients/cli,plugins/*'

jk release --skip-tests
./install.sh target/dist/jk
```

Workspace members: libraries, `clients/cli`, `clients/web`, `server/engine` (assembly fat
jar), and all first-party `plugins/*` workers (`assembly` + `PluginMain`).

### `jk test` coverage

| Modules | Under `jk test` |
|---|---|
| `shared/*`, `server/{io,resolver,toolchain,engine}`, `plugins/*`, `clients/cli` | **Green** dogfood / CI (CLI uses isolated nested engines) |

Gradle tests remain the pre-merge bar for many paths (`./gradlew test` / `checkAll` —
see [AGENTS.md](../AGENTS.md) and [test-suite-tiers.md](perf/test-suite-tiers.md)).

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

## Still Gradle (by design)

| Task | Why |
|---|---|
| Full `./gradlew test` | Parity oracle + bootstrap CI source of truth |
| `./gradlew dist` / `nativeCompile` | Bootstrap binary when no prior `jk` install exists |
| `./gradlew installLocal` | Workers + engine materialize/bounce; or `jk plugin install-local` after pure-jk build |

### Future cut-over (backlog)

Not started — keep dual-build green until this epic is scheduled:

1. **Bootstrap without in-tree Gradle** — install `jk` from a release (or a sibling Gradle-only
   checkout) so a clean product tree never needs `./gradlew`.
2. **CI primary = pure-jk** — Gradle job becomes optional `parity`.
3. **Relocate Gradle** for oracle builds only (if still wanted).
4. **Product tree Gradle-free** — delete `gradlew`, `buildSrc/`, module `build.gradle.kts`.

Until then: pure-jk dogfood is required for product tickets that touch runtime; Gradle remains
valid for bootstrap and comparison.

## Side-load workers (no Gradle)

After `jk build` produces thin PluginMain jars under `plugins/*/target/` (or
`target/plugins/…`):

```bash
jk plugin install-local
# or: jk plugin install-local --modules test-runner,java-compiler
# or: jk plugin install-local --dry-run
```

For each PluginMain worker:

1. Thin jar → `~/.local/share/jk/store/repos/local/cc/jumpkick/jk-<name>/<ver>/` (Maven layout;
   same as Gradle `installLocal`).
2. Runtime deps → `.classpath` sidecar next to the jar.
3. Worker + deps hard-linked into `~/.local/share/jk/store/lib/jk-<name>/` (same
   `JK_LIB_DIR` tree as `jk tool install` / `jk install` apps — default
   `$JK_STORE_DIR/lib`). Launch uses those short paths in `ps`. A normal CAS/`repos/`
   sweep that unlinks repo materializations leaves these hardlinks; the inode stays until
   you uninstall (remove that lib dir) or reinstall.

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

Gradle still produces a comparable bootstrap layout at `build/dist/` via `./gradlew dist`.

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
6. ~~Curated `jk test` + CI self-host dogfood~~ (done)
7. ~~Engine + plugins under pure-jk `jk test`~~ (done)
8. ~~Native CLI via `jk native` / `jk release`~~ (done)
9. ~~`clients/cli` under pure-jk `jk test` (nested-engine isolation)~~ (done)
10. ~~Same-repo dual-build (`jk.toml` + Gradle; no `jk.jk`)~~ (done)
11. **Mill-class test parallelism** — isolation + default `-w` / `--parallel-tests` policy
    ([test-parallelization.md](perf/test-parallelization.md))
12. **Gradle cut-over** — backlog ([above](#future-cut-over-backlog))
