# Self-hosting JumpKick (`jk-jk`)

Build JumpKick with JumpKick. Gradle remains the **bootstrap** and **parity oracle** until the
pure-jk path covers ship layout (`jk release`) and the full suite.

## Worktree layout

Recommended dual-checkout setup:

| Path | Role |
|---|---|
| `…/oss/jk` | Gradle bootstrap + product development on `main` |
| `…/oss/jk-jk` | Git worktree on branch `self-host-jk-jk` — dogfood with `jk` only |

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

# Curated unit suite (verified green; engine/cli nested suites stay on Gradle for now)
jk test --modules 'shared/*,server/io,server/resolver,server/toolchain'

jk release --skip-tests --jvm
./install.sh target/dist/jk
```

Current workspace: libraries, `clients/cli`, `clients/web`, `server/engine` (assembly fat jar),
and **all** first-party `plugins/*` workers (`assembly` + `PluginMain`).

### `jk test` coverage notes

| Modules | Under `jk test` |
|---|---|
| `shared/*`, `server/io`, `server/resolver`, `server/toolchain` | **Green** dogfood / CI |
| `server/engine`, `clients/cli` | Prefer `./gradlew :engine:test :cli:test` (nested worker wiring) |
| `plugins/*` | Light unit tests OK if present; heavy integration still Gradle |

## Default repositories

With no `[repositories]` table, remotes are **Maven Central then Google Maven** (local CAS /
`repos/*` / `~/.m2` still win first). R8 and Android coords do not need an extra google stanza.

## Still Gradle

| Task | Why |
|---|---|
| Full `./gradlew test` | Nested CLI/engine suites and worker wiring |
| `./gradlew dist` / `nativeCompile` | Bootstrap + native CI matrix; dogfood ship via `jk release` |
| `./gradlew installLocal` | Prefer `jk plugin install-local` after `jk build` for workspace workers |

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

After a bootstrap `jk` is on PATH:

```bash
jk release --skip-tests --jvm
# alias: jk dist --skip-tests --jvm
./install.sh target/dist/jk
```

Produces:

```text
target/dist/
  jk                         # running client (--jvm) or native binary (--native)
  lib/
    jk-engine-<version>.jar  # engine assembly (includes web SPA)
```

Also runs `jk plugin install-local` for workspace PluginMain workers.

Flags: `--out <dir>`, `--skip-tests`, `--native`, `--jvm`, `--dry-run`, `--modules <sel>`.

## Roadmap (summary)

1. ~~Default Google Maven~~ (done)
2. ~~`clients/web` + engine assembly~~ (done)
3. ~~`jk plugin install-local`~~ (done)
4. ~~`jk release` / `jk dist`~~ (done)
5. ~~All first-party plugins on the workspace~~ (done)
6. ~~Curated `jk test` + CI self-host dogfood~~ (done: shared/* + server libs)
7. Full engine/cli under `jk test`; native client via `jk native` in release CI

Details: session plan *Self-host JumpKick in ../jk-jk*.
