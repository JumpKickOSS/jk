# AGENTS.md

Guidance for anyone (human or agent) working in this repository.

## What this is

**JumpKick** (CLI / shorthand: **`jk`**) is a modern, lockfile-first build tool for **Java, Kotlin, and Groovy**: a slim native CLI, a memory-capped resident JVM engine, PubGrub dependency resolution, a content-addressed action cache, and Maven Central–compatible coordinates. Think Cargo/uv ergonomics on the JVM without inventing a new package ecosystem.

Product docs: [README.md](README.md), [docs/user/](docs/user/README.md) (end users + coding agents), [docs/contributors/](docs/contributors/README.md) (this codebase). Build/layout: [CONTRIBUTING.md](CONTRIBUTING.md). Internal design records live in [kanartist](https://github.com/JumpKickOSS/kanartist) `projects/jk/docs/`, not here.

**Out-of-tree black-box suite / adopter examples:** [JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples) (sibling checkout `../jk-examples`). Real multi-module and plugin scenarios used to validate and benchmark product changes and to show idiomatic JumpKick to early adopters. Not a substitute for `./gradlew test` — re-run the scenarios that touch surfaces you change (workspaces, Boot, Kotlin, packaging, resolve, …).

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

Bootstrap pins: [`.sdkmanrc`](.sdkmanrc). Prefer `./gradlew` for builds. One Gradle build at a time per checkout (see CONTRIBUTING); use a **separate worktree** for parallel builds.

## Reinstall from this checkout

After code changes, reinstall the **local** JumpKick so dogfood uses the build you just made
(native client on PATH under `~/.local/bin`, engine jar under the product data root):

```bash
./gradlew clean dist installLocal && ./install.sh build/dist/jk
```

| Step | What it does |
|---|---|
| `clean dist` | Fresh `build/dist/jk` (native CLI) + `build/dist/lib/jk-engine-*.jar` |
| `installLocal` | Side-loads plugin/worker jars **and** materializes the engine jar + bounces the daemon (`:engine:installLocal`) |
| `./install.sh build/dist/jk` | Installs that dist into `~/.local/bin` + `$JK_HOME/lib/jk-engine/` (+ config under `$JK_HOME/config/jk-engine/`) via CAS materialize |

Thin JVM dogfood without Graal: `./gradlew :cli:installDist installLocal` then put `clients/cli/build/install/jk/bin` on `PATH`.

Then verify on PATH (or the install dir):

```bash
jk engine status          # engine starts / answers; no version-skew crash
# simple smoke project (any temp dir):
jk init smoke-app && cd smoke-app && jk build
```

Needs a GraalVM-capable JDK for `dist` (see [CONTRIBUTING.md](CONTRIBUTING.md) / `.sdkmanrc`). If only unit tests matter mid-ticket, `./gradlew test` (or module filters) is fine; the reinstall smoke is required **before moving a code-changing ticket to done**.

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
| `./gradlew test` | **Unit/fast** — excludes `@Tag("integration"\|"slow"\|"bench")` | Every ticket, PR, mid-work |
| `./gradlew integrationTest` | Engine/CLI e2e, Android, workers, network | When the ticket touches wire/engine/plans/CLI spawn paths |
| `./gradlew checkAll` | Both tiers for the whole repo | Nightly / pre-merge confidence |

Tag new heavy tests with `@Tag("integration")` (or `slow` / `bench`). Do **not** put multi-minute e2e in the default `test` task.

**Docs-only / non-Java** tickets (markdown, comments-only, pure config with no runtime impact): ticket acceptance met is enough — no reinstall required. Still run any tests that would catch doc-linked fixtures if you touched them.

**Any ticket that changes Java (or other runtime) code** must **not** move to `done` in kanartist until all of the following pass:

1. **Tests (required, non-negotiable)** — prove the change did not break the build:
   - **Always:** green `./gradlew test` (unit/fast tier) for the modules you touched (or full monorepo unit if unsure).
   - **Also** green `./gradlew :cli:integrationTest` and/or `:engine:integrationTest` (or full `./gradlew integrationTest`) when the ticket touches CLI↔engine wire, engine plans/workers, plugin forks, lock/resolve/fetch, or install/materialize.
   - Nightly / main confidence: `./gradlew checkAll` (unit + integration). Do not treat a 20+ minute full e2e as the only mid-ticket loop.
   - Do not land on `main` with a red or un-run test suite for areas you changed. A broken main is a stop-the-line defect: fix tests first, then resume tickets.
2. **Reinstall** — `./gradlew clean dist installLocal && ./install.sh build/dist/jk` succeeds (for code that ships client/engine).
3. **Engine smoke** — `jk engine status` succeeds (engine up or able to start; no immediate failure).
4. **Project smoke** — a simple project builds with the reinstalled binary, e.g. `jk init … && jk build` (or equivalent lock/build path the ticket affects).

Record failures on the kanartist ticket (`status: blocked` or body notes); do not mark done on green unit tests alone if dist/install/dogfood is broken.

## Comments and Javadoc

Comments document the **current** type or method. There is no past that belongs in
source. A brand-new contributor should learn facts, not parse memos from old tickets
or agents.

**Write**

- Facts the signature does not carry: units, invariants, on-disk / wire format, what
  `null` means, why an obvious alternative is illegal *right now*.
- One short class or public-method sentence when the name is not enough.

**Never write**

- Ticket ids (`JK-1234`) in comments, Javadoc, or `package-info`.
- History: formerly, used to, before this, landed in, back-compat, kept for migration.
- Decision records, agent breadcrumbs, “do not revert”, “for future turns”, PR narration.
- Comments that only restate the next line of code.

**Where history goes**

KanArtist (`projects/jk/docs/` or the ticket), the commit / PR body, or (last resort)
`docs/contributors/`. Never dump decision essays into `docs/user/`. Never the main source tree.

**When you touch a file**

Strip ticket refs and leftover essays in that file. Do not add new ones. Keep or
tighten comments that still state a live invariant.

## Code formatting (mandatory before every commit)

**Hard requirement — not optional.** Before creating **any** git commit (including `git commit`, amend, or any equivalent commit action), every agent **must** run:

```bash
jk format
```

Rules:

- Run `jk format` after you have finished creating or modifying source files and **before** you stage a commit. This covers **all** languages the command supports, including Java, Kotlin, Groovy, and any other files it formats.
- **Do not** proceed with `git commit` (or amend / any equivalent) until `jk format` has **successfully completed**.
- If `jk format` fails, **fix the issues and re-run `jk format`** until it succeeds. Do not skip, defer, or commit around a failed format run.
- This applies to every commit an agent creates in this repository. Unformatted source must not enter git history.

## Git workflow (private repo)

- **Trivial** fixes (typos, one-liner comment, obvious bug with no ticket): commit on `main` is fine.
- **Everything else:** work on a **dedicated branch** (prefer a **git worktree** so `main` stays clean and other agents can coordinate). Name branches after the ticket when possible, e.g. `JK-1044-build-logic-spi` (legacy `ticket-NNNN-…` names are fine for older branches).
- While the repo is private: **no pull requests**. Merge into `main` when the work is **done** (see **Done criteria** above; ticket acceptance met), then **push `main`** so the code stays the shared truth. Update the kanartist ticket status when the work lands.
- Do not leave half-finished tickets on `main`. Do not rewrite published history on `main` without an explicit human request.
- Author commits as a normal human contributor. No tool or model co-author trailers, no “generated by …” attribution, and no third-party AI product or model brand names in commits, comments, Javadoc, or user-facing docs. Optional local guard: `git config core.hooksPath scripts/git-hooks` (strips known agent trailers from commit messages).
