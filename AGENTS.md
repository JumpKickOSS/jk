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
| Engine | JVM fat jar (`server/engine` + `server/*`) — never native |
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

## Planning / tickets (KanArtist — not this repo)

**Live board:** org planning repo **[kanartist](https://github.com/jkbuild/kanartist)** (`jkbuild/kanartist`), project key **`jk`**, ticket ids **`JK-NNNN`**.

- Protocol: that repo’s [`AGENTS.md`](https://github.com/jkbuild/kanartist/blob/main/AGENTS.md).
- Tickets: `projects/jk/tickets/JK-NNNN-*.md` (status lives on the ticket file; board views are generated).
- Sibling checkout assumed: `../kanartist` next to this repo (or set `KANARTIST_WORKSPACE_ROOT`).
- **Do not** edit `docs/kanban/` for coordination — it is **frozen/historical** ([docs/kanban/README.md](docs/kanban/README.md)).

### Claim and ship a ticket

```bash
# in kanartist
git pull --rebase
ka next --project jk          # or: ka ls --status ready --project jk
ka claim JK-1044              # commits + pushes; push is the lock
# work in this repo on a branch (prefer worktree)
# … implement, meet Done criteria below …
ka set-status JK-1044 done    # only after product acceptance + Done criteria
```

Prefer a small WIP limit (a few claimed tickets). If blocked: `ka set-status JK-… blocked` and note why on the ticket.

### Done criteria

**Docs-only / non-Java** tickets (markdown, comments-only, pure config with no runtime impact): ticket acceptance met is enough — no reinstall required. Still run any tests that would catch doc-linked fixtures if you touched them.

**Any ticket that changes Java (or other runtime) code** must **not** move to `done` in kanartist until all of the following pass:

1. **Tests (required, non-negotiable)** — prove the change did not break the build:
   - Prefer full `./gradlew test` before merging to `main`.
   - If full suite is too heavy mid-ticket, run the modules that make sense for the change (e.g. `./gradlew :resolver:test :engine:test :cli:test`) and **always** re-run a green `./gradlew test` (or the same relevant filter plus any adjacent modules you touched) **before** marking the ticket done / merging to `main`.
   - Do not land on `main` with a red or un-run test suite for areas you changed. A broken main is a stop-the-line defect: fix tests first, then resume tickets.
2. **Reinstall** — `./gradlew clean dist installLocal && ./install.sh build/dist/jk` succeeds.
3. **Engine smoke** — `jk engine status` succeeds (engine up or able to start; no immediate failure).
4. **Project smoke** — a simple project builds with the reinstalled binary, e.g. `jk init … && jk build` (or equivalent lock/build path the ticket affects).

Record failures on the kanartist ticket (`status: blocked` or body notes); do not mark done on green unit tests alone if dist/install/dogfood is broken.

## Git workflow (private repo)

- **Trivial** fixes (typos, one-liner comment, obvious bug with no ticket): commit on `main` is fine.
- **Everything else:** work on a **dedicated branch** (prefer a **git worktree** so `main` stays clean and other agents can coordinate). Name branches after the ticket when possible, e.g. `JK-1044-build-logic-spi` (legacy `ticket-NNNN-…` names are fine for older branches).
- While the repo is private: **no pull requests**. Merge into `main` when the work is **done** (see **Done criteria** above; ticket acceptance met), then **push `main`** so the code stays the shared truth. Update the kanartist ticket status when the work lands.
- Do not leave half-finished tickets on `main`. Do not rewrite published history on `main` without an explicit human request.
- Author commits as a normal human contributor; no agent/tool co-author trailers or “generated by …” attribution in commits or comments.
