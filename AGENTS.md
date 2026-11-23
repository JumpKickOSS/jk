# AGENTS.md

Guidance for anyone (human or agent) working in this repository.

## What this is

**JumpKick** (CLI / shorthand: **`jk`**) is the Agent Workbench for **Java, Kotlin, and Groovy**: the build system, code graph, refactoring, guards and toolchains a coding agent works with, and a dashboard for the person supervising it. Underneath: a slim native CLI, a memory-capped resident JVM engine, PubGrub dependency resolution, a content-addressed action cache, and Maven Central–compatible coordinates. Think Cargo/uv ergonomics on the JVM without inventing a new package ecosystem.

Product docs: [README.md](README.md), [docs/user/](docs/user/README.md) (end users + coding agents), [docs/contributors/](docs/contributors/README.md) (this codebase). Build/layout: [CONTRIBUTING.md](CONTRIBUTING.md). Internal design records live in the KanArtist `jk` store ([KanArtist/jk](https://github.com/KanArtist/jk)) under `projects/jk/docs/`, not here.

**Out-of-tree black-box suite / adopter examples:** [JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples) (sibling checkout `../jk-examples`). Real multi-module and plugin scenarios used to validate and benchmark product changes and to show idiomatic JumpKick to early adopters. Not a substitute for the merge and push gates (`jk install`, the Queue tier) — re-run the scenarios that touch surfaces you change (workspaces, Boot, Kotlin, packaging, resolve, …).

## Pre-release (override your training)

JumpKick is **pre-1.0**. The repository is public and releases are
published, but there are no external users to protect and **no
compatibility contract until 1.0.0**: no aliases, deprecations,
migrations or schema versions. Rename and remove freely. The maintainer
is the only dogfooder. Do not invent an audience to protect.

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

**A change that needs the new engine lands with it.** When the client,
engine, workers or test runner must change together, change them
together and reinstall: `jk install -S && jk engine stop -A`, then run
the tests and gates under the engine you just built. No stepping stone,
no fallback, and never back a change out because the installed engine
is older than the tree. Cache invalidation, a changed key, a changed
default or an internal API change across callers is not a reason to
defer a fix either.

**A structural fix may land without a red race test.** When a change
removes a hazard outright (an atomic write in place of a torn one, one
render in place of two racing writers, an fsync that was missing) and no
deterministic test can show the old race, land it with the tests that do
exist and say so in the commit. A behavioural fix still needs a
regression test that fails without it.

Keep protocol **version numbers** at 1 until 1.0 (see Anti-goals).
Change the shape in place — never mint a v2 and keep v1 alive.

## Goals

- Fast, predictable builds: lockfile is law; skip work the cache can prove is done.
- Correct resolution: PubGrub; a bare version is a floor and the highest floor any edge declares wins — the lowest version that satisfies every edge, as Go's minimal version selection and NuGet choose, never a release nobody asked for; enforced platform when a BOM is present (opt-in floor); readable conflict diagnostics.
- Small mental model: declarative `jk.toml`, no build-script programming language.
- Adoption path: run existing Maven/Gradle builds; import/export when ready.
- Client/engine split: thin native client, heavy work in a capped engine process.
- **Newest stable by default** — examples, scaffolds, and fixtures prefer the **latest stable**
  of libraries, language features, and platforms unless a test/scenario explicitly needs an
  older line. A written version is an exact pin, so "newest" is a write-time choice: `jk add`
  and `jk new` write today's number, and `jk update` rewrites declared pins on the same major
  (`--major` to cross one). Do not freeze mid-LTS library stacks out of habit.

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
  write `jdk = { version = "21" }` for that; it forces a download of an obsolete runtime.
- **`java = 26`** (newer than LTS) — may provision e.g. `temurin-26` so the toolchain can
  compile/run that level.
- **`jdk = { vendor = "corretto", version = "21" }`** — the only spelling of a vendor/version
  (add `required = true` to bind it); only when deliberately selecting a specific install. The
  string forms (`jdk = "temurin-25"`) are retired. Not the default teaching path.

**Examples and docs:** use `java = 25` (or omit if inherited). Avoid a `jdk` table almost always,
so users do not learn to pin runtimes.
Containers / packaging can ship a matching JRE — stop teaching legacy JDK pins.

**Tests:** a `jdk` table only in fixtures that **must** exercise provisioning / first-run
pin behavior (`JdkFloorTest`, `FirstBuildJdkTest`, …). Elsewhere prefer `java = N` alone
(or `java = 25` + no `jdk` line).

## Anti-goals

- Do not turn `jk.toml` into a scripting language or a Gradle-style configuration graph.
- Do not re-resolve in `jk build` when a valid `jk-lock.toml` exists.
- Do not plan a build-like verb outside `ModulePipeline`, re-root a plan from a verb's tail, or
  decide "skip tests" in a verb. Every build-like goal runs the same phases through PACKAGE and
  differs only in its tail and test policy — [docs/contributors/lifecycle.md](docs/contributors/lifecycle.md).
- Do not write per-module lockfiles — one `jk-lock.toml` at the workspace root (or standalone project root).
- Product docs are part of the product: [docs/user/](docs/user/README.md) for people and agents *using* `jk`, [docs/contributors/](docs/contributors/README.md) for this codebase. Do not dump PRDs, benches, or decision essays into this repo — those belong in the KanArtist `jk` store (`projects/jk/docs/`).
- Do not expand infinite ecosystem long tail (full KMP multiplatform, AGP parity, plugin marketplace, RBE) without an explicit ticket that says so.
- Do not leave historical essays, ticket ids, or decision records in code comments — see [Comments and Javadoc](#comments-and-javadoc).
- **Do not bump schema/protocol versions before 1.0** — stay on version **1** for `jk-lock.toml`, wire
  `proto`, JSONL/`details.jsonl` `schema`, REST/SSE, MCP, etc. Shapes change in place (see
  Pre-release); no version churn noise without public users. See [docs/contributors/architecture.md](docs/contributors/architecture.md#schema-freeze-until-10).

## What coding agents read

The `AGENTS.md` section `jk` writes, the MCP tools' answers and the files `jk agents` writes are a
product surface tuned per agent (Claude Code first, then Cursor). Before changing any of it, read
[docs/contributors/agent-integration.md](docs/contributors/agent-integration.md), and measure before
and after as [docs/contributors/agent-bench.md](docs/contributors/agent-bench.md) says: breadth-first,
one run per cell, fail fast.

## Tech stack

| Layer | Choice |
|---|---|
| Language | Java 25 |
| Build of JumpKick itself | JumpKick (root `jk.toml` workspace, `jk-lock.toml`, `.jk/scripts/*.kts` build scripts) |
| Native CLI | GraalVM native-image (`clients/cli`) |
| Engine | JVM fat jar (`server/engine` + `server/*`) — never native |
| Config / lock | TOML (`jk.toml`), canonical `jk-lock.toml` (workspace root only) |
| Module outputs | `{module}/target/`, Maven's layout (`classes/`, `test-classes/`, jars at the root, `surefire-reports/`, …) |
| Resolve | PubGrub (`server/resolver`) |
| Cache | Content-addressed store + action cache |
| Wire | JSONL client↔engine protocol (`shared/wire`) |
| Modules | `shared/` (client-safe), `server/` (engine-only), `clients/`, `plugins/`, `e2e/` (tests only) |

Build with the installed JumpKick (`jk build`; a GraalVM-capable JDK for the native client — see CONTRIBUTING). Use a **separate worktree** for parallel work; the resident engine serializes the builds of one workspace. **One heavy run per machine:** JumpKick's full integration tier and an uncached full `jk build` of this tree start nested engines with their own worker pools, so do not run two at once or alongside a benchmark (`bench/`, jk-examples `bench/`), which also needs an otherwise idle host. Editing and narrow `-m` / `--class` tests in parallel worktrees are fine ([details](docs/contributors/test-suite-tiers.md#one-heavy-run-per-machine)).

**Several sessions on one host share one installed JumpKick and one resident engine.** JumpKick coordinates them itself with a host install lock ([engine](docs/user/engine.md#sessions-sharing-one-install)): `jk test`, `guard`, `build` and `explain` hold it shared, and an install of JumpKick itself (`jk install`), `jk self update` and `jk engine stop` hold it exclusively, each waiting for the other and naming the holder; `jk engine status` lists who holds or waits. The lock covers one command, so a gate that must run against the build it just installed (`jk install`, then `jk install -g`) checks that `jk engine status`'s `Source` row still names its checkout before trusting the result.

## Reinstall from this checkout

After code changes, reinstall the **local** JumpKick so dogfood uses the build you just made
(client on PATH under `~/.jk/bin`, engine jar under `~/.jk/lib/jk-engine/`):

```bash
jk install -S
```

JumpKick follows the flow it asks its users to follow. The root `jk.toml` `version` is the **next
release** and is never written with `-SNAPSHOT`; `jk install` installs the tree as
`<version>-SNAPSHOT` (the build stamps that into `JkVersion.VERSION`), replaced on every run.
`jk release` cuts a release: the version is installed once, tagged, and `jk.toml` moves to the
next one (bump the installers' `RELEASE_FLOOR` with it). A snapshot of an installed release is refused.

`jk install` is `jk build` plus the install steps: the same plan, the same lanes, tests unless `-S`.
Never run `jk build` first; it repeats work the install does anyway.

| Command | What it does |
|---|---|
| `jk install -S` | Everything `jk build -S` does (every module compiled and packaged; `target/dist/jk` native CLI + `target/dist/lib/jk-engine-<version>.jar`), then shelves every library and worker jar under `~/.jk/store/repos/jk-local` (bytes into the artifact CAS first), materializes the engine jar into `~/.jk/lib/jk-engine/`, pins the shelved jars to that engine by sha in `~/.jk/lib/jk-engine/jk-shelf.toml` and swaps the native client into `~/.jk/bin/jk`. The next invocation takes over the resident engine; every fork of an engine runs the workers its own install pinned, so a second worktree's install never swaps a worker under a running engine, and the displaced engine drains with its own |
| `./install.sh target/dist/jk` | The same ship layout onto a machine with no JumpKick yet |
| `scripts/reinstall-chain.sh` | The same install when the installed JumpKick is too old to build the checkout: installs stepping stones from a scratch worktree first ([self-host](docs/contributors/self-host.md#the-bootstrap-chain)) |

A host with no native client gets the JVM client from the same installer ([releases](docs/contributors/releases.md#platforms-without-a-hosted-client)).

Then verify on PATH (or the install dir):

```bash
jk engine status          # engine answers, no version-skew crash; `Source` names this checkout
# simple smoke project (any temp dir; `jk init` takes no directory — it initializes the cwd):
jk new smoke-app -l java && cd smoke-app && jk build
```

Needs a GraalVM-capable JDK for the native client (see [CONTRIBUTING.md](CONTRIBUTING.md)). Module and class filters are fine mid-ticket; the merge gate (`jk guard`, `jk install`) and the reinstall smoke are required **before merging a code-changing ticket into local `main`**; the Queue tier is the push gate, which the merge queue runs before it moves `origin/main`, and `jk install -g` is the Full tier behind it.

## Planning / tickets (KanArtist — not this repo)

Tickets live in the KanArtist store `jk` ([KanArtist/jk](https://github.com/KanArtist/jk)), project
key **`jk`**, ticket ids like **`JK-3B0SH`**. The `ka` CLI ([KanArtist/ka](https://github.com/KanArtist/ka))
and its MCP tools read, write, commit and push them; never edit ticket files by hand and never
`git pull` or `git push` a store yourself. `ka skill` prints the full guide; `ka <command> -h`
explains any command. Tickets imported from the old board keep their old id in the field
`legacy_id` (`ka ls field.legacy_id:JK-4661 --all`).

- **Priority lives on the board, not here.** `ka next` returns the best ready, unblocked ticket
  and claims it; a P0 there outranks everything. Read a ticket's status and priority with
  `ka show <ID>` before trusting any claim that it blocks other work. Do not restate board
  priorities or preemptions in this file; they go stale the day the ticket closes.

### Claim and ship a ticket

```bash
ka next --project jk                    # finds and claims the best ready ticket (or: ka claim <ID>)
ka show JK-3B0SH                        # description, acceptance items, decisions, recent progress
# work in this repo on a branch (prefer worktree)
ka comment JK-3B0SH --type progress -m "…"   # at milestones; --type decision for choices others must respect
ka check-item JK-3B0SH 0                # tick an acceptance item as it passes
# … implement, meet Done criteria below …
ka move JK-3B0SH done -m "what changed and where"   # only after product acceptance + Done criteria
```

Prefer a small WIP limit (a few claimed tickets). Blocked on a person: `ka ask <ID> -m "…"`, then
work on something else and read answers with `ka inbox`. Stopping without finishing: `ka release
<ID>`. Use the ticket's id in commit subjects, never in code or comments.

### Done criteria

**Two gates** (keep the merge loop under ~5 minutes; details: [docs/contributors/test-suite-tiers.md](docs/contributors/test-suite-tiers.md)):

- **Merge gate** — before every local merge into `main`: `jk guard` (it formats what changed), then
  `jk install` (`jk build` plus the reinstall; never run `jk build` before it).
- **Push gate** — the Queue tier, on the exact commit that becomes `origin/main`. The merge queue
  on bocabox runs it, as `scripts/gate/gate.sh` with `GATE_TIER=queue`, on each batch of submitted
  branches under the host's gate lock; nobody else pushes `main` (see
  [Landing on `origin/main`](#landing-on-originmain)). The Queue tier: `jk guard`, `jk install -S` (the
  reinstall without tests, so the tests run under the batch's own engine), one `jk install` (every
  module's fast tier, then the reinstall), the integration classes each module the batch changed
  ranks highest within a budget ([ranking](docs/contributors/test-suite-tiers.md#the-queue-tier-runs-the-classes-a-batch-ranks-highest);
  the Full tier runs the classes it defers) and the [curated lane](docs/contributors/test-suite-tiers.md#the-curated-integration-lane)
  in the others, and the shell fixtures; a failure only in a quarantined class does not block it.
- **Full tier** — `scripts/gate/gate.sh` with `GATE_TIER=full`: `jk guard`, `jk install -S` (the
  reinstall without tests), `jk install -g` (the fast tier once, with every integration and e2e
  class, against the JumpKick it installed), the release suite, the slow tier when a plugin changed
  since `green`, and the shell fixtures. It does not
  block a push, so `main` may be red on it for a cycle or two; a class that fails and passes on
  re-run leaves the gate green, is recorded, and past a threshold quarantined with a ticket
  ([self-host](docs/contributors/self-host.md#the-gate)). `scripts/gate/full-loop.sh` runs it back
  to back on the newest `main` and moves origin's `green` branch to each commit that passes;
  releases are cut from `green`. A red cycle names the batch that caused it on its tickets; when
  the failing classes fail with one of its commits and pass without it, the loop submits that
  commit's revert to the queue and reopens its ticket, and after two red cycles alike the queue lands
  only the fix
  ([Full tier loop](docs/contributors/self-host.md#the-full-tier-loop)).
- **Branch tier** — before a branch is submitted to the queue: `scripts/queue/branch-tier.sh`,
  which runs `jk guard` and the fast tier of the modules the branch changed and of their
  dependents (no native client, no suites of the modules they build on), and records the pass
  that `scripts/queue/submit.sh` requires. `jk guard` counts a guard it had to skip as clean, so
  the Branch tier warns when this machine has no runnable shellcheck: the shell scripts are then
  linted first by the queue's gate.
- **Prove it cheaply first** — before any run that takes more than a few minutes, however it is
  spelled (a gate, a whole suite, an integration or slow tier, an uncached build of this tree),
  run the tests that can fail it, one module and one class at a time: every class an earlier run
  of that gate failed, the tests of the code you changed, and the tests of whatever you merged or
  rebased in. On the host that will run the gate, first make the installed JumpKick match the tree (the
  reinstall without tests, seconds when the build is cached): narrow end-to-end runs use the
  installed JumpKick's workers and fail falsely against an older install. Read each class's counts in
  the test reports, not only the exit code. Start the long run once those pass; a long run that
  fails on what a one-minute run would have caught has held the host for nothing.

| Command | What runs | When |
|---------|-----------|------|
| `jk guard` | Formats every changed source, then every house-rule lane, the tree lane and the fixture proofs included | Every commit that touches code, and every local merge |
| `jk guard docs` | The text rules that read Markdown, no build | A docs-only commit |
| `jk test -s docs` | The tests that walk the doc tree (`@Tag("docs")`): `-O json` callers, plugin defaults a page quotes | A commit that changes a doc or a command's machine output; every gate tier |
| `jk test -s tree` | The tests that scan every module's production sources (`@Tag("tree")`), kept out of each module's fast tier so an edit re-runs only them | The Branch, Queue and Full tiers |
| `jk install` | Exactly what `jk build` runs (every module compiled and packaged, its fast tier, the model/module/workspace/output lanes), then the reinstall | Every local merge |
| `scripts/queue/branch-tier.sh` | `jk guard`, then the fast tier of the modules the branch changed and their dependents, then the tree suite | Before submitting a branch to the merge queue |
| `GATE_TIER=queue scripts/gate/run.sh <n> <sha>` | The Queue tier: guard, the reinstall without tests, one install, the changed modules' integration classes ranked highest within a budget, the curated lane, the docs suite, the tree suite, the shell fixtures | Every batch the merge queue lands (the push gate) |
| `jk install -g` | Engine/CLI e2e, workers, install, lock: every integration class | The Full tier, back to back behind the queue (`scripts/gate/full-loop.sh`) |
| `jk test -s slow` | Framework and language e2e (Android, Grails, Scala, KSP, Protobuf) | Before submitting a plugin or toolchain change; the Full tier when a plugin changed since `green`; nightly |

Tag new heavy tests with `@Tag("integration")` (or `slow` / `bench`). Do **not** put multi-minute e2e in the default `test` task.

**Docs-only / non-Java** tickets (markdown, comments-only, pure config with no runtime impact): ticket acceptance met is enough — no reinstall required. Still run any tests that would catch doc-linked fixtures if you touched them.

**Any ticket that changes Java (or other runtime) code** must **not** merge into local `main` or move to `done` on the board until all of the following pass:

1. **Tests (required, non-negotiable)** — prove the change did not break the build:
   - **Merge gate:** a clean `jk guard` (it formats what changed, then every house-rule lane, the tree lane included) and a green `jk install` (every module's fast tier, then the reinstall).
   - Mid-ticket, and before every long run, test by module and by class as **Prove it cheaply first** (under Two gates) says; never the full integration suite per merge.
   - Do not merge into local `main` with a red or un-run fast gate. A broken local `main` is a stop-the-line defect: fix it first, then resume tickets.
2. **Reinstall** — done by the merge gate's `jk install`. Never run `jk build` and then `jk install`; the install is the build.
3. **Engine smoke** — `jk engine status` succeeds (engine up or able to start; no immediate failure).
4. **Project smoke** — a simple project builds with the reinstalled binary, e.g. `jk init … && jk build` (or equivalent lock/build path the ticket affects).

Record failures on the ticket (`ka comment <ID> --type progress`, or `ka move <ID> blocked`); do not mark done on green unit tests alone if dist/install/dogfood is broken.

### Landing on `origin/main`

The merge queue on bocabox (`scripts/queue/loop.sh`) is the only writer to `origin/main`. Every
session, an orchestrator's integration branch included, submits a branch instead of pushing `main`:

```bash
scripts/queue/branch-tier.sh     # jk guard + the fast tier of the changed modules and their dependents + the tree suite; records the pass
scripts/queue/submit.sh          # the current branch (or name one) waits in the queue
scripts/queue/status.sh          # waiting, gating in batch <n>, landed as <sha>, or ejected and why
```

A branch that touches a framework or language plugin passes `jk test -s slow` before it is
submitted. The queue applies everything waiting on top of `origin/main`, runs the Queue tier on
that commit and fast-forwards `origin/main` to it; it never force-pushes.

**When `origin/main` moved** under a gate, a green verdict survives a rebase over docs-only commits
and nothing else: the queue lands the batch on the new `origin/main` without gating it again when
everything that came in is docs-only, and applies and gates it again otherwise. A change is
**docs-only** when every file it touches is a doc (under `docs/` or `hosting/`, or Markdown at the
root) that no test reads but those of the docs suite (`jk test -s docs`, the tests that scan the
doc tree). The docs tests read are `scripts/gate/test-reads.txt`, written by
`scripts/gate/docs-only.sh write` from what the suites were seen reading; the Full tier is red while
a read is missing from it. Tests read `docs/user/skill.md`, `machine-output.md`, `why.md` and the
examples under `docs/user/examples/`, so a change to one is not docs-only. A docs-only batch skips
the Queue tier and runs the docs checks alone (`GATE_TIER=docs`: `jk guard`, which holds the link
check, `bench/scoreboard --render --check` and the docs suite); every code batch runs the docs suite
too, and the Full tier still runs everything. A new test that walks a directory of docs is tagged
`@Tag("docs")`.

A branch that conflicts, or that is
red alone, is ejected with the reason and the failing classes, which the queue also writes on its
ticket (the one the branch is named for, `w/<ticket>`): fix it, run the Branch tier again and
resubmit. A flake does not eject: the Queue tier runs failed classes once more and records them. Closing the ticket stays with whoever reviewed it.
Never `git push` to `main` yourself, not even after a green gate of your own
([self-host](docs/contributors/self-host.md#the-merge-queue)).

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

**User-facing output wording**

Errors, warnings, notes, progress, results files and the web UI follow
[docs/contributors/messages.md](docs/contributors/messages.md): one plain line, 78 columns or less,
subject first, no jk-internal jargon, no essay sentences. MCP payloads may say more.

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

**Docs say what is, never what was**

Until 1.0.0 every doc and piece of public copy (README, `docs/`, the skill pages, the template
READMEs, the website, the unreleased What's new entry) explains what JumpKick does now. No
was-named notes ("`usages` (was `callers`)"), no renamed-from or "before 0.60.0" asides, no
`**Breaking:**` bullets, no "used to" or "no longer" narration of a past release: describe the
command, key or behaviour as it is. What's new entries of released versions stay as they were
written. `jk guard docs` holds the common shapes (rule `docs-say-what-is`), and
`scripts/release-notes.sh` holds the unreleased What's new entry to the same rule.

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

## Code formatting (part of `jk guard`)

The root `jk.toml` sets `[format] enforce = true`, so formatting is a step of `jk guard`, not a
command to remember: before it builds, guard formats every source that changed since the formatter
last settled it (Spotless for every language it formats, plus the first-party FQCN shortener on
Java), and under CI it fails naming each file that is not formatted. There is no separate `jk format` step.

- **Run `jk guard` before every commit that touches code** (commit, amend, or any equivalent) and let it
  finish green. A docs-only commit (Markdown, no source) runs `jk guard docs` instead: the text rules
  that read Markdown, in seconds, with no build.
  It is the merge gate's first half too. Unformatted source must not enter git history.
- **Trust what it rewrites.** Long-hand FQCNs and similar agent noise are why it exists.
- **Unrelated files may change. Keep them.** A file someone changed without a guard run is formatted
  on yours. Do not `git restore` those diffs or treat them as a reason to skip the commit. When the
  format set is wider than your change, land your work as one commit and the format-only files as a
  second; do not drop the second.
- `jk format -F` rewrites the whole tree, for a formatter or style change; it is not part of the loop.

## Git workflow

- **Trivial** fixes (typos, one-liner comment, obvious bug with no ticket): commit on `main` is fine.
- **Everything else:** work on a **dedicated branch** (prefer a **git worktree** so `main` stays clean and other agents can coordinate). Name branches after the ticket when possible, e.g. `JK-1044-build-logic-spi` (legacy `ticket-NNNN-…` names are fine for older branches).
- **No pull requests:** merge into local `main` when the work passes the merge gate (see **Done criteria** above; ticket acceptance met) and move the ticket on the board (`ka move`). Batch merges from several worktrees, then **submit** the branch to the merge queue, which lands it on `origin/main` ([Landing on `origin/main`](#landing-on-originmain)); never push `main` yourself. Submit often enough that `origin` does not lag local `main` by more than a working session.
- Do not leave half-finished tickets on `main`. Do not rewrite published history on `main` without an explicit human request.
- Every commit compiles and passes on its own: a file rename lands in the same commit as the class rename it carries, and a signature change lands with its callers. `git bisect` must never stop on a tree that cannot build.
- Author commits as a normal human contributor. No tool or model co-author trailers, no “generated by …” attribution, and no third-party AI product or model brand names in commits, comments, Javadoc, or user-facing docs. Local guard: `jk guard hooks install` writes the `commit-msg` hook that refuses such a trailer (the `no-agent-trailers` rule) and the `pre-commit` hook that protects the guard baseline.
