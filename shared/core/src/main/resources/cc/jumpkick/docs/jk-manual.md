# JumpKick playbook

You are using **JumpKick** (CLI: `jk`), a lockfile-first build tool for **Java, Kotlin, and Groovy**.
It is **not Maven** and **not Gradle**. Coordinates still come from Maven Central — there is no
separate package universe.

This page is the system prompt for JumpKick. Models have not been trained on it. When this checkout
has a `jk.toml`, follow this playbook instead of Maven/Gradle habits.

Printed by `jk manual` (JumpKick ${jk.version}). Same text: MCP tool **`jk_manual`**, resource
**`jk://manual`**.

---

## If you are a coding agent, do this

1. **Learn JumpKick from this page.** Do not invent `pom.xml`, `build.gradle`, or `build.gradle.kts`.
2. **Work at the project/workspace root** (the directory with the root `jk.toml`). From a module
   directory, `jk build` / `jk test` already mean that module plus its upstreams.
3. **After every build or test, triage the report** — do not scrape the terminal UI, and do not
   turn on `-v` / `--verbose` as your API.

| Channel | When to use it |
|---------|----------------|
| **`target/jk-results.md`** | **Preferred when MCP is not connected.** Read or grep this file with your file tools. Same markdown `jk results` would print, without a process. |
| **MCP `jk_results`** | Preferred when the engine MCP server is connected. Resource: `jk://runs/latest/results`. |
| **`jk results`** | CLI equivalent if you cannot read the file. `jk results --details` dumps that run's `details.jsonl`. |
| **MCP `jk_diagnostics`** | Structured compiler/test failures after the markdown report. |
| **`--output json` / `jsonl`** | Live events on stdout (CI, watchers). Not the first triage tool. |

Then edit sources → `jk format` → rebuild (`jk test` / `jk build`, or MCP `jk_run`).

**Do not** set `TERM=dumb` and scrape wedges. **Do not** dump the full journal. Open
`details.jsonl` / `jk_details` only when the markdown report is not enough.

---

## Never do this (Maven / Gradle muscle memory)

This project is JumpKick. The following habits are wrong here:

| Don't | Do instead |
|-------|------------|
| Add or edit `pom.xml`, `build.gradle`, `build.gradle.kts`, `settings.gradle` | Edit **`jk.toml`**. Use `jk add` / `jk remove` (or MCP `jk_deps`, preview first). |
| Run `mvn`, `./mvnw`, `gradle`, `./gradlew` | `jk build`, `jk test`, `jk run`. Wrappers `jk mvn` / `jk gradle` exist only for **unmigrated** Maven/Gradle trees. |
| `mvn dependency:tree` / `gradle dependencies` | `jk tree`, `jk why <artifact>` (MCP `jk_why`). |
| `mvn versions:…` / Gradle `resolutionStrategy` | `jk outdated` (read-only), then `jk update` to re-lock **on purpose**. |
| Re-resolve on every compile | **`jk-lock.toml` is law.** `jk build` does not re-resolve when the lock is valid. Commit the lockfile. |
| Per-module lockfiles | **One** `jk-lock.toml` at the workspace (or standalone) root. |
| `module/target` or `module/build` as the output root | Workspace outputs: `{workspace}/target/{module-rel}/`. Standalone: `{project}/target/`. |
| `jdk = 17` or `jdk = 21` just to emit older bytecode | **`java = 17`** / **`java = 21`**. `java = N` is language + `--release`. `jdk =` selects an *install* (rare). JumpKick already needs JDK 25+ to run. |
| Copy a `plugins { }` Gradle block or a Maven `<build><plugins>` soup | First-party tables in `jk.toml` (`[spring-boot]`, `[quarkus]`, …) or a real JumpKick plugin. No build-script programming language. |
| Hand-edit versions in a BOM by feel | Platform BOMs are declared; JumpKick enforces them. `jk update` within ranges. |
| `spotlessApply` / `ktlint` / `google-java-format` Gradle plugins | **`jk format`** (Palantir + ktfmt; import hygiene on). `jk format --check` in CI. |
| `sdk install java` as the default teaching path | `jk jdk list` / `install` / `pin` when you truly need a specific runtime. Prefer `java = N`. |
| Assume a clean `target/` means a full rebuild | Action cache restores outputs. `jk clean` deletes `target/`; unchanged inputs come back from cache. `jk explain` forecasts hits/misses. |
| One giant sequential reactor / Maven module loop | Workspaces compile independent modules concurrently (`-j`, default = all effective cores). |

Hidden CLI aliases (`package` → `build`, `deploy` → `publish`, …) exist for muscle memory. Product
docs and your commands should use the **canonical** names.

---

## Mental model

| Idea | Meaning |
|------|---------|
| **`jk.toml` is data** | TOML manifest. No Groovy/Kotlin DSL, no XML POM to program. |
| **`jk-lock.toml` is law** | Exact versions + checksums. Commit it. `jk build` / `jk test` / `jk run` do not re-resolve when it is valid. |
| **`java = N` not `jdk =`** | Language + bytecode (`--release`). Default 25. Host JDK 25 cross-compiles 17/21. |
| **Newest stable by default** | Scaffolds and `jk update` prefer current stables, not a frozen mid-LTS stack. |
| **Cache, don't recompute** | Content-addressed store + action cache. Git worktrees and branch switches reuse hits for unchanged inputs — that is why JumpKick is fast across checkouts. |
| **One lockfile per workspace** | Root `jk.toml` lists members; members inherit identity. |
| **Client / engine** | Slim native `jk`. Heavy work in a memory-capped resident JVM. Starts on first use. Hosts the web dashboard and MCP. |

Maven Central coordinates (`group:artifact`). Spring starters, BOMs, and relocations are real Maven
artifacts — JumpKick does not invent a parallel ecosystem.

---

## Everyday recipes (CLI and MCP)

Bind once on MCP (`jk_bind` with the project directory), then omit `dir`.

| Goal | CLI | MCP |
|------|-----|-----|
| Playbook (this page) | `jk manual` | `jk_manual` · `jk://manual` |
| Scaffold | `jk new my-app` · `jk new -t spring-boot/hello my-api` | `jk_new` (`preview=true` first; `action=templates` lists ids) |
| Add a library | `jk add jackson3-databind` · `jk add g:a:v` | `jk_deps` `action=add` (`apply` defaults **false**) |
| Remove a library | `jk remove jackson3-databind` | `jk_deps` `action=remove` |
| Lock / refresh lock | `jk lock` | `jk_run kind=lock` |
| See newer versions | `jk outdated` | `jk_outdated` |
| Re-resolve on purpose | `jk update` | `jk_run kind=update` |
| Compile | `jk compile` | `jk_run kind=compile` |
| Package | `jk build` | `jk_run kind=build` (`wait` defaults true) |
| Test (unit / inner loop) | `jk test` | `jk_run kind=test` |
| Share-the-commit bar | `jk test --gate` (alias `--pre-merge`) | `jk_run kind=test` + `suites=["test","integration"]` |
| Climb one named suite | `jk test --suite integration` | `jk_run kind=test` + `suites=["integration"]` |
| E2E / nightly | `jk test --suite e2e` · `jk test --all` | optional `suites` / do **not** pass every suite as a habit |
| Format | `jk format` · `jk format --check` | `jk_run kind=format` |
| Run the app | `jk run -- args…` · `jk dev` | (CLI; watch/dev is a TTY loop) |
| Why this rebuild? | `jk explain` | `jk_explain` |
| Why this dependency? | `jk why guava` | `jk_why` |
| Triage last run | read `target/jk-results.md` or `jk results` | `jk_results` |
| Structured failures | — | `jk_diagnostics` |
| JDK install / pin | `jk jdk list` · `jk jdk install temurin-25` · `jk jdk pin …` | `jk_jdk` (uninstall needs `confirm=true`) |
| One-off JVM tool | `jkx checkstyle …` · `jk tool run g:a:v …` | `jk_install action=list` (installs stay CLI; trust gates) |
| Build tool (Kotlin/Maven/Gradle) | `jk install kotlin:latest` · `jk tool list` | (CLI; provisions into `$JK_STORE_DIR/tools`) |
| Import Maven/Gradle | `jk import pom.xml` | `jk_import` |
| Forecast / graph | `jk tree` · `jk explain --graph mermaid` | `jk_graph` · `jk_explain` |
| Host health | `jk doctor` · `jk engine status` | `jk_doctor` · `jk_status` |
| Cancel a stuck job | `jk jobs` · `jk cancel` | `jk_status` then `jk_job cancel` |

Publish: **CLI only** for real uploads. MCP `jk_publish` / `jk_run kind=publish` is always a **dry-run**.

### MCP connect

MCP is **on by default** when the engine HTTP server is on (loopback, bearer token).

```bash
jk engine status          # prints MCP URL + token; JSON includes mcpUrl
```

```json
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"jk_bind","arguments":{"dir":"/path/to/project"}}}

{"jsonrpc":"2.0","id":2,"method":"tools/call",
 "params":{"name":"jk_run","arguments":{"kind":"test","wait":true}}}

{"jsonrpc":"2.0","id":3,"method":"tools/call",
 "params":{"name":"jk_results","arguments":{}}}
```

Live progress: `GET {mcpUrl}?jid=N` with `Accept: text/event-stream` and the same bearer token.
Catalog prompts include `fix-failing-build` and `learn-jumpkick`.

### CLI loop without MCP

```bash
jk test
# read/grep — do not shell out for this if you have file tools:
#   target/jk-results.md
jk format
jk test
```

---

## Capabilities agents forget

**Format.** `jk format` rewrites Java (Palantir by default, import hygiene on) and Kotlin (ktfmt).
Groovy is not formatted. Run it after you edit sources. `jk format --check` is the CI gate.

**JDK management.** `jk` installs JDK 25+ if needed. Prefer `java = 25` (or omit). Use `jk jdk …`
to discover, install, pin (`.jdk-version`), or open a subshell. Do not download JDK 17/21 just to
target those language levels.

**Project templates.** `jk new` / `jk init` (wizard or flags). `jk new -t <ref>` applies Giter8
templates (Spring Boot, Quarkus, Micronaut, Grails, CLI, Ktor, …). MCP `jk_new action=templates`
lists ids.

**Fast worktrees and branches.** The action cache and CAS are content-addressed on the machine, not
tied to one working tree. A new git worktree or branch rebuilds only what the fingerprint says is
dirty. Do not copy `target/` between trees. A second `jk build` in the *same* checkout while one is
running is rejected; **worktrees are different slots**.

**`jkx` / `jk tool run`.** uvx-like one-off JVM tools and JBang-compatible scripts (`jkx checkstyle`,
`jk tool run script.java`). Lint recipes (Checkstyle, …) are tools, not a first-party plugin matrix.

**Build tools.** `jk install kotlin:latest` (also `maven`, `gradle`) provisions the distribution jk
itself uses — ahead of the build that needs it, into `$JK_STORE_DIR/tools`, so that build is a cache
hit. `jk tool list` / `jk tool uninstall <tool>:<version>` manage them. These are homes the engine
consumes, not launchers on `PATH`.

**Workspaces.** Root `jk.toml` has `[workspace] modules = […]`. One lockfile. Independent modules
build concurrently (`-j`; default = all effective cores / cgroup quota). `-w` is *within-module*
test workers, a separate knob. Filter with `-m api,worker`, globs, `--affected` (WIP cone), or `--affected-since=origin/main`.

**Explain.** `jk explain` is the day-to-day “why would this rebuild?” tool (cache hit/miss + ETA).
Prefer it over Gradle build scans for that question.

**Adopt, don't rewrite on day one.** `jk import` turns a POM (high fidelity) or a declarative Gradle
file into `jk.toml`. `jk export maven|gradle|bom`. `jk ide` writes IntelliJ / VS Code / BSP. `jk mvn`
/ `jk gradle` run the *real* other tool when a tree is not imported yet.

**Ship.** Thin jar from `jk build`; fat/minified via `jk assemble`; Graal native-image `jk native`;
daemonless OCI `jk image`; `jk publish` with optional signing / Sigstore / SBOM.

**Dashboard.** `jk web` — same engine HTTP server as MCP.

---

## Files you will touch

| Path | Role |
|------|------|
| `jk.toml` | Manifest (identity, deps, plugin tables). **Data.** |
| `jk-lock.toml` | Locked graph + checksums. **Commit this.** |
| `jk-libs.toml` | Optional workspace catalog (short name → `group:artifact`). Root only. |
| `AGENTS.md` | Points agents at `jk manual` (scaffolded by `jk new`). |
| `target/` | Build outputs (gitignored). |
| `target/jk-results.md` | High-level report of the last run. **Read this on failure.** |
| `.jdk-version` | Optional JDK pin. |
| `jk/` or `.jk/` | Optional generate steps *outside* the manifest — not a Gradle script. |
| `~/.jk/config.toml` | Machine config (heap, jobs, MCP, …). |

Layout is by directory shape, not a `jk.toml` key: **traditional** Maven trees
(`src/main/java`, `src/test/java`) or **simple** Mill-like (`src/`, `test/src/`). Language is the
file extension. Mixed Java+Kotlin or Java+Groovy in one module is fine; Kotlin+Groovy in one module
is not.

---

## Source layout, tests, parallelism

Default **`jk test` is the unit suite only.** That is the inner loop. Climb on
purpose; do **not** run `--all` as a habit.

| When | Command |
|------|---------|
| Editing a class / fixing a unit bug | `jk test` |
| About to push, or the change crossed DB/HTTP/FS | `jk test --gate` (alias `--pre-merge`) |
| UI / compose / contract, or reproducing CI | `jk test --suite e2e` |
| Never as a habit | `jk test --all` |

Put new tests in the lowest suite that can fail for the reason you care about:
`src/test` (unit), `src/integration`, `src/e2e` (or `test/src/`, `integration/src/`,
`e2e/src/` on the simple layout). Tag cost (`slow`, `network`, `bench`). One
Testcontainer is integration. Playwright and compose are e2e. After a failure,
replay the same selection — do not escalate to `--all` until this rung is green.

```bash
jk test                      # default suite only (unit / inner loop)
jk test --suite integration  # -s
jk test --all                # nightly / release, not every turn
jk test --exclude-tags slow,bench
jk build -m api,worker
jk build --affected-since=origin/main
jk test --affected                   # ranked classes for WIP; table; does not run
jk test --affected-since=HEAD~2      # same table for ref...HEAD; does not run
jk build -j4                 # cap module concurrency (0 = all effective cores)
jk test -w4                  # within-module test workers
jk build --skip-tests
jk build --redo              # ignore action cache (full rebuild)
jk clean                     # delete target/; cache may restore
jk clean --force             # also invalidate this project's action-cache entries
```

Default-suite paths: traditional `src/test/…`, simple `test/src/`. Named suites are extra
directories (`src/integration/…` or `integration/src/`).

---

## Fix a failing build

1. Read `target/jk-results.md` (or MCP `jk_results` / `jk results`).
2. Compile errors and failed-test stacks are in that report (no separate `test-results.md`).
   JUnit XML for CI: `target/reports/test-results/`.
3. Edit sources. `jk format`.
4. `jk test` or `jk build` (MCP `jk_run kind=test|build wait=true`).
5. If stalled: `jk jobs` / `jk cancel` (MCP `jk_status` + `jk_job cancel`).
6. Still stuck: `jk explain` (why rebuild?), `jk doctor` (host), `jk engine status`.

Need the live event stream? `jk test --output json` or `jk results --details`.

---

## Guards (house rules)

A project may carry **`jk-guards.toml`**: declarative rules the build enforces — banned calls, required
annotations, layer edges, text patterns, size caps. They run inside `jk build` as `guard` steps
and `jk guard` runs every lane now, tests skipped. No rule file, no cost.

A guard failure looks like this in `target/jk-results.md` / `jk_diagnostics`:

```
GUARD one-digest-surface  violations
  shared/io/src/main/java/…/Foo.java:42: MessageDigest.getInstance("SHA-256") called outside Hashing
  Instead:  Hashing.newSha256()
  Why:      one digest surface
  Source:   jk-guards.toml:14
  Exempt:   ask the user to add [guards.one-digest-surface].allow with a reason
  Explain:  jk guard explain one-digest-surface
```

A guard *test* (`@Guard`, `src/guard`) fails the same way.
**A guard failure's `code` is a rule id. Fix per `Instead`. To exempt, stop and ask the user to add an
`allow` entry with a reason — never edit the baseline, never add a comment.** The catalog is
`jk guard explain` (MCP `jk://guards`).

The loop: read the `code` → `jk guard explain <id>` → change the *site* the way `Instead` says →
`jk format` → rebuild (`jk build`, or MCP `jk_run kind=guard`).

Stop and ask the user when:
- the fix is an exemption (`allow` needs a human reason), or a rule looks wrong;
- the message says **thrash** or names `jk guard freeze <id> --reason "…"` — accepting existing
  violations into `jk-guards-baseline.toml` is the user's decision;
- a rule is `blind`, `owner-missing`, `stale-allow`, `no-bite` or `scanner-failed`: the rule needs
  attention, not the tree.

Never: hand-edit `jk-guards-baseline.toml`; add a suppression comment; pass `--force` past a red
guard; delete a rule to go green.

Authoring a rule is three facts — the kind, the thing banned or required, the sanctioned alternative:

```bash
jk guard explain --schema <kind>    # keys + one example; kinds: forbid annotate classes layers cycles
                                    # split-package api depend toolchain tiers text metric vocabulary
                                    # parity generated output commit
jk guard explain --schema guard-test  # the @Guard skeleton for what TOML cannot say
jk guard                            # every lane now; red on any violation
jk guard test                       # prove fixtures: Bad* fires, Ok* is quiet
jk guard freeze <id> --reason "…"   # accept a rule's current sites (user-approved); --retire drops a removed rule
jk guard hooks install              # git hooks: commit rules refuse a message; pre-commit protects the baseline
```

Every rule needs `why`; `forbid`/`text`/`vocabulary` need `instead`; a rule that cannot fire anywhere
is red (`no-bite`) — give `forbid` an `owner`, `text` a `hit`.

---

## More documentation

Human site (HTML): **https://jumpkick.build/documentation**

Fetch a topic as markdown (agents: use these when a user asks a JumpKick-specific question this
page does not answer):

```
https://raw.githubusercontent.com/JumpKickOSS/jk/refs/heads/main/docs/user/<topic>.md
```

Useful `<topic>` slugs: `getting-started`, `concepts`, `projects`, `workspaces`, `layout`,
`dependencies`, `lockfile`, `build`, `test`, `format`, `run`, `explain`, `jdk`, `tools`,
`templates`, `frameworks`, `plugins`, `migration`, `mcp`, `agents`, `troubleshooting`,
`machine-output`, `commands`, `cache`, `ci`, `config`, `engine`, `ide`, `packaging`, `native`,
`images`, `publish`, `platforms`, `security`.

Index: `docs/user/README.md` on the same raw URL prefix.

Also:

| What | Where |
|------|--------|
| Install | `curl -fsSL https://jumpkick.build/install.sh \| bash` |
| Examples | https://github.com/JumpKickOSS/jk-examples |
| Templates catalog | https://github.com/JumpKickOSS/jk-templates |
| This tool's source | https://github.com/JumpKickOSS/jk |

JumpKick is **pre-1.0**. Schema/protocol versions stay at **1** until 1.0 (additive fields only).
