# Concepts

A small set of ideas. Everything else in the user docs is a specialization of these.

## `jk.toml` is data

The manifest is TOML. There is no Groovy/Kotlin DSL and no XML POM to program. That is
deliberate: `jk add` / `jk remove` can edit the file, code review stays tractable, and
JumpKick never evaluates your build as a general program.

Custom generate steps live **outside** the manifest in [`jk/` or `.jk/`](build-logic.md).
Heavy reusable behavior is a [plugin](plugins.md).

## The lockfile is law

`jk-lock.toml` records every resolved version and checksum. **Commit it.**

- `jk build` / `jk test` / `jk run` **do not re-resolve** when a valid lock exists.
- `jk lock` writes or refreshes the lock — pinned versions stay; `jk lock -F` floats them.
- `jk update` re-resolves **on purpose** within your declared ranges.
- `jk outdated` is read-only.

There is **one** lockfile: workspace root, or the standalone project root. Never per-module.

Details: [Lockfile](lockfile.md).

## `java =` vs `jdk =`

JumpKick **requires JDK 25+ to run** (it installs one if needed). Once `jk` works, you
already have a modern JDK. Prefer **language level**, not extra runtime downloads.

| Field | Meaning | Prefer |
|-------|---------|--------|
| **`java = N`** | Language + bytecode (`--release N`) | **Yes** — default 25; 17 and 21 are fine (host JDK 25 cross-compiles) |
| **`jdk = …`** | Which JDK *install* to use / provision | **Rare** — only when you truly need that runtime (Graal, a vendor pin, a major newer than the host) |

Do **not** write `jdk = 17` or `jdk = 21` just to emit older bytecode. That forces an obsolete
runtime download. Use `java = 17` / `java = 21` instead.

`java = 26` (newer than the host LTS) may provision a 26 toolchain.

Details: [JDK](jdk.md), [Projects](projects.md).

## Newest stable by default

Scaffolds, examples, and `jk update` prefer the **latest stable** of libraries and language
features unless you pin otherwise. Unpinned `latest` still prefers a newer **stable** over a
newer pre-release.

```bash
jk outdated     # what moved under your ranges? (read-only)
jk update       # rewrite the lock on purpose, then commit it
```

## Test rungs — cheapest first

Default `jk test` is the **unit** suite (`src/test/…` or `test/src/`). That is the
inner loop: agents and humans run it constantly.

Climb on purpose:

- **Integration** (`src/integration/…` or `integration/src/`) — one module plus
  real collaborators. One Testcontainer is fine. Run before you share a commit.
- **E2E** (`src/e2e/…` or `e2e/src/`) — Playwright, compose, full fixtures.
  CI / nightly. A local judgment call, not a habit.
- **`--all`** — every discovered suite. Nightly / release, not every turn.

Cost that does not change *scope* is a JUnit tag (`slow`, `network`, `bench`), not
a fourth directory. The named share-the-commit bar is `--guard`.
[Test](test.md) · [Why](why.md#test-rungs-the-execute-moat).

## Cache, don’t recompute

JumpKick hashes inputs (sources, classpath, options) and restores outputs from a
content-addressed **action cache** when they match. `jk explain` forecasts hits and misses
before you build. `jk clean` deletes `target/` but does **not** by itself force a full
recompute — unchanged inputs restore from cache.

Details: [Build](build.md), [Explain](explain.md), [Cache](cache.md).

## Client and engine

`jk` is a slim **native** CLI. Heavy work runs in a **resident JVM engine** (memory-capped,
started automatically). You rarely think about this; `jk engine status` / `jk engine stop`
are there when you need them. The engine also hosts the [web dashboard](web.md) and [MCP](mcp.md).

Details: [Engine](engine.md).

## Maven Central, not a new ecosystem

Coordinates are `group:artifact` (and optional version). BOMs are real Maven
`dependencyManagement`. Starters are real Maven artifacts (for example
`spring-boot-starter-web`). JumpKick does not invent a parallel package universe.

Built-in remotes, exclusive groups, and `jk import` / `jk export`: [Repositories](repositories.md),
[Migration](migration.md), [Platforms](platforms.md).
