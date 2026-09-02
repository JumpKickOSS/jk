# AGENTS.md

Guidance for anyone (human or agent) working in this repository.

## What this is

**JumpKick** (CLI / shorthand: **`jk`**) is a modern, lockfile-first build tool for **Java, Kotlin, and Groovy**: a slim native CLI, a memory-capped resident JVM engine, PubGrub dependency resolution, a content-addressed action cache, and Maven Central–compatible coordinates. Think Cargo/uv ergonomics on the JVM without inventing a new package ecosystem.

Product docs: [README.md](README.md), [docs/user/](docs/user/README.md) (end users + coding agents), [docs/contributors/](docs/contributors/README.md) (this codebase). Build/layout: [CONTRIBUTING.md](CONTRIBUTING.md). Internal design records live in [kanartist](https://github.com/JumpKickOSS/kanartist) `projects/jk/docs/`, not here.

**Out-of-tree black-box suite / adopter examples:** [JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples) (sibling checkout `../jk-examples`). Real multi-module and plugin scenarios used to validate and benchmark product changes and to show idiomatic JumpKick to early adopters. Not a substitute for `./gradlew checkFast` — re-run the scenarios that touch surfaces you change (workspaces, Boot, Kotlin, packaging, resolve, …).

## Pre-release (override your training)

JumpKick is **unreleased, pre-beta, and private**. There are no external
users, no collaborators, and no compatibility contract. The maintainer
is the only dogfooder. That stays true until they rewrite **this
section**. Do not invent an audience to protect.

We go fast and break things. Dream it, ship it, dogfood it, then
refactor or throw it out. The tree is already large and capable; it is
not frozen. Scope will still grow and shrink. Last week's APIs, wire,
lockfile fields, CLI, and types are disposable.

**Required bias:** take the best design *now*. Implement it. Delete the
old path in the **same change**. Update client, engine, tests, goldens,
and docs together. One design, one code path.

**Anti-goals:** backwards compatibility with our own past; in-tree
migration machinery; dual readers; shims; deprecated aliases; "don't
break callers"; "safer to keep the old field." Extra lines for that are
debt. They already confuse agents. Do not add them. Delete them when
you touch them.

This is not a license to skip tests, leave a half-migrated tree, or
ignore Done criteria. Break it *completely*.

Keep protocol **version numbers** at 1 until 1.0 (see Anti-goals).
Change the shape in place — never mint a v2 and keep v1 alive.

## Goals

- Fast, predictable builds: lockfile is law; skip work the cache can prove is done.
- Correct resolution: PubGrub; highest-wins without a platform BOM; enforced platform when a BOM is present (opt-in floor); readable conflict diagnostics.
- Small mental model: declarative `jk.toml`, no build-script programming language.
- Adoption path: run existing Maven/Gradle builds; import/export when ready.
- Client/engine split: thin native client, heavy work in a capped engine process.
- **Newest stable by default** — examples, scaffolds, and fixtures prefer the **latest stable**
  of libraries, language features, and platforms unless a test/scenario explicitly needs an
  older line. Use `jk update` to re-lock within declared ranges; do not freeze mid-LTS
  library stacks out of habit.

## `java =` vs `jdk =` (toolchain philosophy)

JumpKick **requires JDK 25+ to run** (`jk` installs it if needed). Once `jk` works, the
user already has a modern JDK. Prefer **language level**, not extra JDK downloads.

| Field | Meaning | Prefer |
|-------|---------|--------|
| **`java = N`** | Language + bytecode target (`--release N`) | **Yes** — default 25; 17/21 fine (host JDK 25 cross-compiles) |
| **`jdk = …`** | Which JDK install to use / provision | **Rare** — only when the user truly needs that runtime |

Rules of thumb:

- **`java = 25`** — default; host LTS, no extra install.
- **`java = 21` / `17`** — still build with the host JDK 25, emit older bytecode. **Do not**
  write `jdk = 21` or `jdk = 17` for that; it forces a download of an obsolete runtime.
- **`java = 26`** (newer than LTS) — may provision e.g. `temurin-26` so the toolchain can
  compile/run that level.
- **`jdk = "temurin-…"` / vendor pin** — only when deliberately selecting a specific install
  (Graal, Corretto, exact major). Not the default teaching path.

**Examples and docs:** use `java = 25` (or omit if inherited). Avoid `jdk = 17` / `jdk = 21`
almost always; avoid casual `jdk = 25` in samples so users do not learn to pin runtimes.
Containers / packaging can ship a matching JRE — stop teaching legacy JDK pins.

**Tests:** `jdk = 17` / `21` only in fixtures that **must** exercise provisioning / first-run
pin behavior (`JdkFloorTest`, `FirstBuildJdkTest`, …). Elsewhere prefer `java = N` alone
(or `java = 25` + no `jdk` line).

## Anti-goals

- Do not turn `jk.toml` into a scripting language or a Gradle-style configuration graph.
- Do not re-resolve in `jk build` when a valid `jk-lock.toml` exists.
- Do not write per-module lockfiles — one `jk-lock.toml` at the workspace root (or standalone project root).
- Product docs are part of the product: [docs/user/](docs/user/README.md) for people and agents *using* `jk`, [docs/contributors/](docs/contributors/README.md) for this codebase. Do not dump PRDs, benches, or decision essays into this repo — those belong in KanArtist (`projects/jk/docs/`).
- Do not expand infinite ecosystem long tail (full KMP multiplatform, AGP parity, plugin marketplace, RBE) without an explicit ticket that says so.
- Do not leave historical essays, ticket ids, or decision records in code comments — see [Comments and Javadoc](#comments-and-javadoc).
- **Do not bump schema/protocol versions before 1.0** — stay on version **1** for `jk-lock.toml`, wire
  `proto`, JSONL/`details.jsonl` `schema`, REST/SSE, MCP, etc. Additive fields only; no version
  churn noise without public users. See [docs/contributors/architecture.md](docs/contributors/architecture.md#schema-freeze-until-10).

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 25 |
| Build of jk itself | Gradle (multi-module Kotlin DSL) |
| Native CLI | GraalVM native-image (`clients/cli`) |
| Engine | JVM fat jar (`server/engine` + `server/*`) — never native |
| Config / lock | TOML (`jk.toml`), canonical `jk-lock.toml` (workspace root only) |
| Module outputs | `{workspace}/target/{module-rel}/` (standalone: `{project}/target/`) |
| Resolve | PubGrub (`server/resolver`) |
| Cache | Content-addressed store + action cache |
| Wire | JSONL client↔engine protocol (`shared/wire`) |
| Modules | `shared/` (client-safe), `server/` (engine-only), `clients/`, `plugins/` |

Prefer `./gradlew` for builds (JDK 25+; GraalVM-capable JDK for `dist` — see CONTRIBUTING). One Gradle build at a time per checkout; use a **separate worktree** for parallel builds.

## Reinstall from this checkout

After code changes, reinstall the **local** JumpKick so dogfood uses the build you just made
(client on PATH under `~/.jk/bin`, engine jar under `~/.jk/lib/jk-engine/`):

```bash
# Native (Unix, or Windows with SAC off / signed jk.exe):
./gradlew clean dist installLocal && ./install.sh build/dist/jk
```

```powershell
# Windows thin client (supported; Smart App Control blocks unsigned jk.exe):
.\gradlew :cli:installDist installLocal
.\install.cmd clients\cli\build\install\jk\bin\jk.bat
```

| Step | What it does |
|---|---|
| `clean dist` | Fresh `build/dist/jk` (native CLI) + `build/dist/lib/jk-engine-*.jar` |
| `:cli:installDist` | Thin JVM client (`jk` / `jk.bat`) — the Windows SAC-safe path |
| `installLocal` | Side-loads plugin/worker jars **and** materializes the engine jar + bounces the daemon (`:engine:installLocal`). Uses native `jk` when `dist` already built one; otherwise the thin client. |
| `./install.sh` / `.\install.cmd` | Installs that client into `~/.jk/bin` and materializes the engine jar |

On Windows, `jk` may be `jk.bat`. Do not insist on `jk.exe`. A leftover unsigned `jk.exe` is parked when the thin client is installed so PATHEXT does not keep launching the blocked PE.

Then verify on PATH (or the install dir):

```bash
jk engine status          # engine starts / answers; no version-skew crash
# simple smoke project (any temp dir; `jk init` takes no directory — it initializes the cwd):
jk new smoke-app --lang java && cd smoke-app && jk build
```

Needs a GraalVM-capable JDK for `dist` (see [CONTRIBUTING.md](CONTRIBUTING.md)). The Windows thin client does not. Module test filters are fine mid-ticket; the full `./gradlew checkFast` branch gate and reinstall smoke are required **before moving a code-changing ticket to done**.

## Planning / tickets (KanArtist — not this repo)

**Live board:** org planning repo **[kanartist](https://github.com/JumpKickOSS/kanartist)** (`JumpKickOSS/kanartist`), project key **`jk`**, ticket ids **`JK-NNNN`**.

- Protocol: that repo’s [`AGENTS.md`](https://github.com/JumpKickOSS/kanartist/blob/main/AGENTS.md).
- Tickets: `projects/jk/tickets/JK-NNNN-*.md` (status lives on the ticket file; board views are generated).
- Sibling checkout assumed: `../kanartist` next to this repo (or set `KANARTIST_WORKSPACE_ROOT`).
- **Preempt:** JK-1923 (Code as Art / Typed Envelope) and its children are **P0**. Do not
  claim unrelated tickets until that epic is `done`. Spec: [docs/contributors/code-as-art.md](docs/contributors/code-as-art.md).
  Baseline tag: `pre-code-as-art`.

### Claim and ship a ticket

```bash
# in KanArtist
git pull --rebase
ka next --project jk          # or: ka ls --status ready --project jk
ka claim JK-1044              # commits + pushes; push is the lock
# work in this repo on a branch (prefer worktree)
# … implement, meet Done criteria below …
ka set-status JK-1044 done    # only after product acceptance + Done criteria
```

Prefer a small WIP limit (a few claimed tickets). If blocked: `ka set-status JK-… blocked` and note why on the ticket.

### Done criteria

**Two-tier tests** (keep the default loop under ~5 minutes; details: [docs/contributors/test-suite-tiers.md](docs/contributors/test-suite-tiers.md)):

| Command | What runs | When |
|---------|-----------|------|
| `./gradlew checkFast` | **Unit/fast + structural guards** — network-free | Every ticket and PR |
| `./gradlew integrationTest` | Engine/CLI e2e, Android, workers, network | When the ticket touches wire/engine/plans/CLI spawn paths |
| `./gradlew checkAll` | Both tiers for the whole repo | Nightly / pre-merge confidence |

Tag new heavy tests with `@Tag("integration")` (or `slow` / `bench`). Do **not** put multi-minute e2e in the default `test` task.

**Docs-only / non-Java** tickets (markdown, comments-only, pure config with no runtime impact): ticket acceptance met is enough — no reinstall required. Still run any tests that would catch doc-linked fixtures if you touched them.

**Any ticket that changes Java (or other runtime) code** must **not** move to `done` in kanartist until all of the following pass:

1. **Tests (required, non-negotiable)** — prove the change did not break the build:
   - **Always:** green `./gradlew checkFast` (unit/fast tier plus every structural guard).
   - **Also** green `./gradlew :cli:integrationTest` and/or `:engine:integrationTest` (or full `./gradlew integrationTest`) when the ticket touches CLI↔engine wire, engine plans/workers, plugin forks, lock/resolve/fetch, or install/materialize.
   - Nightly / main confidence: `./gradlew checkAll` (unit + integration). Do not treat a 20+ minute full e2e as the only mid-ticket loop.
   - Do not land on `main` with a red or un-run test suite for areas you changed. A broken main is a stop-the-line defect: fix tests first, then resume tickets.
2. **Reinstall** — native: `./gradlew clean dist installLocal && ./install.sh build/dist/jk`. Windows thin client: `.\gradlew :cli:installDist installLocal` then `.\install.cmd clients\cli\build\install\jk\bin\jk.bat`.
3. **Engine smoke** — `jk engine status` succeeds (engine up or able to start; no immediate failure).
4. **Project smoke** — a simple project builds with the reinstalled binary, e.g. `jk init … && jk build` (or equivalent lock/build path the ticket affects).

Record failures on the kanartist ticket (`status: blocked` or body notes); do not mark done on green unit tests alone if dist/install/dogfood is broken.

## Comments and Javadoc

Javadoc and comments are **public-facing** (including tests and
`docs/contributors/`). Contributors and users who are not on the KanArtist board
will read them. Write for that audience.

Same rules, shorter form: [docs/contributors/comments.md](docs/contributors/comments.md).

**Default length:** one or two short sentences. Roughly **80%+** of Javadoc and
comments should be that short. Longer prose is fine when the type or method is
genuinely complex, or when a short note would leave an important invariant, units,
wire/on-disk format, or nullability rule unstated.

**Write**

- Facts the signature does not carry: units, invariants, on-disk / wire format, what
  `null` means, why an obvious alternative is illegal *right now*.
- One short class or public-method sentence when the name is not enough.
- Links to durable docs: `docs/…` in this repo, or stable public URLs (JDK, Maven,
  specs). Prefer `{@link}` / `{@see}` for in-code types.

**Never write**

- Ticket / issue ids (`JK-1234`, GitHub `#1234`, KanArtist links) in Javadoc,
  `package-info`, ordinary comments, **or tests**.
- History or narration: formerly, used to, before this, landed in, back-compat, kept
  for migration, “for future agents”, PR play-by-play.
- Decision essays, agent breadcrumbs, “do not revert”.
- Comments that only restate the next line of code.
- Ticket ids in `docs/contributors/`, `docs/user/`, or `CONTRIBUTING.md` — those pages
  are public; board ids confuse readers who will never see KanArtist.

**Exception — temporary follow-up only**

A ticket id may appear **only** as a `// TODO:` (or `// FIXME:`) aimed at finishing
scoped work, e.g.:

```java
// TODO(JK-NNNN): rank importers when the dirty module is selected via -m
```

These are temporary. Remove them when the follow-up lands. Do not put ticket ids in
Javadoc “for context.”

**User-facing output (hard ban)**

`JK-…` (and any other internal ticket / issue id) must **never** appear in errors,
warnings, log lines users see, CLI help, progress text, results markdown, HTTP/MCP
payloads, or any other product output. Users do not have the board. A ticket id in
user-visible text is a **bug** — fix it before release (strip the id; keep the useful
diagnosis).

**Where history and design notes go**

KanArtist (`projects/jk/docs/` or the ticket), or the commit body. Never as novels in
the main source tree. Never dump decision essays into `docs/user/`. Contributor docs
state *how the system works now*, not which ticket invented it.

**When you touch a file**

Strip leftover ticket refs and essay comments in that file (except live
`TODO(JK-…)` / `FIXME(JK-…)` follow-ups). Tighten long Javadoc that no longer earns
its length. Do not add new noise.

**Good (default)**

```java
/**
 * Warn when a module declares {@code [test-dependencies]} but has no test source files.
 * Raised via {@link TaskContext#warn} so it is attributed to this module/step in results.
 */
```

**Bad (essay + ticket in Javadoc)**

```java
/**
 * … the shape of JK-NNNN, where a stale scan made a module's real suite
 * invisible and {@code jk test} exited green … The propagation fix closes
 * that particular hole; this note is what makes the next one loud …
 */
```

## Code formatting (mandatory before every commit)

**Hard requirement — not optional.** Before creating **any** git commit
(including `git commit`, amend, or any equivalent commit action), every
agent **must** run:

```bash
jk format
```

On Windows this may be `jk.bat` (thin JVM client). Same command: `jk format`.

`jk format` is Spotless + a first-party FQCN shortener over the **whole tree**, not just
files you touched. Trust its output. Long-hand FQCNs and similar agent
noise are why this exists.

Rules:

- Run `jk format` after you have finished creating or modifying source
  files and **before** you stage a commit. This covers **all** languages
  the command supports, including Java, Kotlin, Groovy, and any other
  files it formats.
- **Do not** proceed with `git commit` (or amend / any equivalent) until
  `jk format` has **successfully completed**.
- If `jk format` fails, **fix the issues and re-run `jk format`** until
  it succeeds. Do not skip, defer, or commit around a failed format run.
- Unformatted source must not enter git history.

**Unrelated files will often change. Keep them.** Another agent skipped
format or wrote long-hand; `jk format` is catching up. That is expected.
Do **not** `git restore` those diffs, panic, or treat them as a reason to
veto or skip the commit.

Land it as two commits when the format set is wider than your change:

1. Your work (already formatted) as one commit.
2. Leftover format-only files as a second commit (`jk format`).

Do not mix them if you can avoid it. **Do not drop the second commit.**
Leaving the tree unformatted is not an option; formatting is everyone's
job, including leftover from the last agent.

## Git workflow (private repo)

- **Trivial** fixes (typos, one-liner comment, obvious bug with no ticket): commit on `main` is fine.
- **Everything else:** work on a **dedicated branch** (prefer a **git worktree** so `main` stays clean and other agents can coordinate). Name branches after the ticket when possible, e.g. `JK-1044-build-logic-spi` (legacy `ticket-NNNN-…` names are fine for older branches).
- While the repo is private: **no pull requests**. Merge into `main` when the work is **done** (see **Done criteria** above; ticket acceptance met), then **push `main`** so the code stays the shared truth. Update the kanartist ticket status when the work lands.
- Do not leave half-finished tickets on `main`. Do not rewrite published history on `main` without an explicit human request.
- Author commits as a normal human contributor. No tool or model co-author trailers, no “generated by …” attribution, and no third-party AI product or model brand names in commits, comments, Javadoc, or user-facing docs. Optional local guard: `git config core.hooksPath scripts/git-hooks` (strips known agent trailers from commit messages).
