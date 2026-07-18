# AGENTS.md

Guidance for anyone (human or agent) working in this repository.

## What this is

**JumpKick** (CLI / shorthand: **`jk`**) is a modern, lockfile-first build tool for **Java and Kotlin**: a slim native CLI, a memory-capped resident JVM engine, PubGrub dependency resolution, a content-addressed action cache, and Maven Central–compatible coordinates. Think Cargo/uv ergonomics on the JVM without inventing a new package ecosystem.

Product docs: [README.md](README.md), [docs/guide.md](docs/guide.md), [docs/architecture.md](docs/architecture.md). Build/layout: [CONTRIBUTING.md](CONTRIBUTING.md).

## Goals

- Fast, predictable builds: lockfile is law; skip work the cache can prove is done.
- Correct resolution: PubGrub + highest-version-wins; readable conflict diagnostics.
- Small mental model: declarative `jk.toml`, no build-script programming language.
- Adoption path: run existing Maven/Gradle builds; import/export when ready.
- Client/engine split: thin native client, heavy work in a capped engine process.

## Anti-goals

- Do not turn `jk.toml` into a scripting language or a Gradle-style configuration graph.
- Do not re-resolve in `jk build` when a valid `jk.lock` exists.
- Do not grow unbounded product docs — keep the public set small ([docs/README.md](docs/README.md)).
- Do not expand infinite ecosystem long tail (full KMP multiplatform, AGP parity, plugin marketplace, RBE) without an explicit ticket that says so.
- Do not leave long historical essays in code comments; keep Javadocs tight — let the code speak.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 25 |
| Build of jk itself | Gradle (multi-module Kotlin DSL) |
| Native CLI | GraalVM native-image (`clients/cli`) |
| Engine | JVM fat jar (`clients/cli-engine` + `server/*`) — never native |
| Config / lock | TOML (`jk.toml`), canonical `jk.lock` |
| Resolve | PubGrub (`server/resolver`) |
| Cache | Content-addressed store + action cache |
| Wire | JSONL client↔engine protocol (`shared/wire`) |
| Modules | `shared/` (client-safe), `server/` (engine-only), `clients/`, `plugins/` |

Bootstrap pins: [`.sdkmanrc`](.sdkmanrc). Prefer `./gradlew` for builds. One Gradle build at a time per checkout (see CONTRIBUTING); use a **separate worktree** for parallel builds.

## Reinstall from this checkout

After code changes, reinstall the **local** JumpKick so dogfood uses the build you just made (native client + engine jar under `~/.jk`):

```bash
./gradlew clean dist installLocal && ./install.sh build/dist/jk
```

| Step | What it does |
|---|---|
| `clean dist` | Fresh `build/dist/jk` (native CLI) + `build/dist/lib/jk-engine-*.jar` |
| `installLocal` | Side-loads plugin/worker jars into the local cache so the new engine can find them |
| `./install.sh build/dist/jk` | Installs that dist into `~/.jk` (bin + version layout via CAS materialize) |

Then verify on PATH (or the install dir):

```bash
jk engine status          # engine starts / answers; no version-skew crash
# simple smoke project (any temp dir):
jk init smoke-app && cd smoke-app && jk build
```

Needs a GraalVM-capable JDK for `dist` (see [CONTRIBUTING.md](CONTRIBUTING.md) / `.sdkmanrc`). If only unit tests matter mid-ticket, `./gradlew test` (or module filters) is fine; the reinstall smoke is required **before moving a code-changing ticket to done**.

## Kanban (source of truth on `main`)

Board lives under [`docs/kanban/`](docs/kanban/). **Only the kanban files on `main` count** for coordination. Other branches’ copies are not the board — pull/rebase `main` before claiming work.

| Column file | Meaning |
|---|---|
| [backlog.md](docs/kanban/backlog.md) | Not refined enough to start |
| [ready.md](docs/kanban/ready.md) | Refined; free to pull |
| [wip.md](docs/kanban/wip.md) | Actively being worked |
| [blocked.md](docs/kanban/blocked.md) | Stuck on an external dependency or decision |
| [done.md](docs/kanban/done.md) | Finished (newest at top) |

**Rules**

1. **One-liners** live only in the column files. Detail lives in `docs/kanban/ticket-NNNN-short-name.md`.
2. **Before starting:** update `main`, check that the ticket is not already in `wip.md` or `done.md`, then move the one-liner to `wip.md` (and update the ticket’s status if it has one).
3. Prefer a small WIP limit (a few active tickets).
4. When stuck, move to `blocked.md` with a short blocker note; when unblocked, back to `ready.md` or `wip.md`.
5. When finished: meet the **done criteria** below, move the one-liner to `done.md`, leave the ticket file as the record, merge to `main`, and push.

### Done criteria

**Docs-only / non-Java** tickets (markdown, kanban, comments-only, pure config with no runtime impact): ticket acceptance met is enough — no reinstall required.

**Any ticket that changes Java (or other runtime) code** must **not** move to `done` until all of the following pass:

1. **Tests** — relevant suite green (`./gradlew test` or the modules you touched; full `test` preferred when the change is wide).
2. **Reinstall** — `./gradlew clean dist installLocal && ./install.sh build/dist/jk` succeeds.
3. **Engine smoke** — `jk engine status` succeeds (engine up or able to start; no immediate failure).
4. **Project smoke** — a simple project builds with the reinstalled binary, e.g. `jk init … && jk build` (or equivalent lock/build path the ticket affects).

Record failures on the ticket or in `blocked.md`; do not mark done on green unit tests alone if dist/install/dogfood is broken.

## Git workflow (private repo)

- **Trivial** fixes (typos, one-liner comment, obvious bug with no ticket): commit on `main` is fine.
- **Everything else:** work on a **dedicated branch** (prefer a **git worktree** so `main` stays clean and other agents can coordinate). Name branches after the ticket when possible, e.g. `ticket-1001-wire-hardening`.
- While the repo is private: **no pull requests**. Merge into `main` when the work is **done** (see **Done criteria** above; ticket acceptance met), then **push `main`** so the kanban and code stay the shared truth.
- Do not leave half-finished tickets on `main`. Do not rewrite published history on `main` without an explicit human request.
- Author commits as a normal human contributor; no agent/tool co-author trailers or “generated by …” attribution in commits or comments.
