# Why JumpKick

> **JumpKick is the Agent Workbench for Java: everything a coding agent needs to work in a Java
> codebase, and a live view for the human supervising it.**

**Audience:** anyone building JVM software with coding agents, and Maven or Gradle users
evaluating a switch. How to *use* JumpKick: [skill](skill.md) · [getting started](getting-started.md) ·
[agents](agents.md).

A developer can write Java in a plain text editor and does not, because an IDE knows the code
and runs the build. A coding agent without a workbench is in that text editor: it greps, reads,
guesses, rebuilds and waits. JumpKick gives it the IDE's knowledge as tools it can call, and puts
it **on rails**: one fast, right way to find the callers, rename a method, add a dependency, run
the tests and say the work is done. The common things are cheap, the wrong things are hard, and
the agent finishes more work. That is agent experience (AX), and [it is measured](#agents-reach-green).

An agent's task runs on two clocks. The **turn clock** is how many times the model goes back and
forth; the code graph and `rename` cut it. The **build clock** is how long the build and the tests
take to answer; the build system cuts it, in three places:

- **The agent's loop.** One changed file recompiles what it invalidated and re-runs the test
  suites whose inputs changed. A suite nothing touched is not run again.
- **CI.** Every compile, generate, guard and test step is keyed by the content of its inputs, so a
  runner skips each step an earlier run already proved ([CI](ci.md)).
- **The team.** With a [shared cache](cache.md), what CI built is restored on every developer's
  machine instead of being built again.

The build system is therefore not beside the point of an agent's workbench; on a codebase with a
long test suite it is most of the point. The reason to leave Maven or Gradle is that the
**edit → build → diagnose → fix → rebuild** loop gets shorter. An agent runs that loop hundreds of times a day, and the human watching it
needs to see what happened on every pass. Each feature below either shortens a step of that
loop, removes a retry, or removes a tool you would otherwise install.

```text
  intent ──► mutate project ──► build/test ──► observe ──► repair ──► repeat
     │            │                 │             │           │
  jk skill    TOML +            cache +       jk-results   structured
  / MCP        jk add/deps       right rung    + diagnostics  edits +
  templates    format            warm engine   (not log scrape) format
               toolchain         lockfile law
```

---

## The seven reasons to switch

Every claim links what backs it. Where JumpKick loses, the linked page says so.

### 1. Agent-first

| Surface | What it does for the agent | Reference |
|---|---|---|
| `target/jk-results.md` | One token-cheap report per invocation: what ran, each failure at its `file:line` with a fix hint, what was skipped, what to run next | [Machine output](machine-output.md) |
| MCP in the engine | `build` returns a verdict; `diagnostics`, `deps`, `usages` and `skill` complete the default list of five, because every card costs the agent context on every turn. Two agents on one engine each keep their own project bind | [MCP](mcp.md) |
| `jk skill` | The system prompt for a tool models were not trained on; JumpKick keeps its section of `AGENTS.md` current on every build | [Skill](skill.md) · [Agents](agents.md) |
| `jk graph` | A code graph built in: code, config and docs, with nothing to install; indexed in seconds, with calls and overrides from the compiled classes. Answers name each symbol `Owner.member()` at `file:line` | [Code graph](graph.md) |
| Named test rungs | The cheap unit suite by default, `--guard` before sharing, `--all` nightly; the report discloses what did not run | [Test rungs](#test-rungs) |
| Guards and hooks | House rules as data: each violation names the rule, the sanctioned alternative and why, so the fix is mechanical; git hooks refuse banned commit trailers and hand-edited guard files | [Guards](guards.md) · [Agents](agents.md#hooks-and-protected-files) |

The measure is turns, tokens and wall time to green on broken builds, against Maven and Gradle
given the same agent tooling: [Agents reach green](#agents-reach-green).

<!-- agents-claims:begin -->
We asked 7 coding agents questions like "who calls this method?" With JumpKick's code graph, their answers cost **56–90% less**, $0.037–$0.311 a question instead of $0.112–$1.78 (an estimate at API list prices), and took **56–96% fewer tokens** and **41–78% fewer back-and-forth turns** than without it. Renaming a method everywhere it is used cost **66–79% less**, $0.020–$0.127 a rename instead of $0.082–$0.474, with **58–86% fewer tokens** and **45–77% fewer turns**. The graph pays off even on tiny projects: the smallest we tested, gs-multi-module, has just **83 lines of code**. ([The tables](#asking-about-the-code).)
<!-- agents-claims:end -->

### 2. Never type `clean` again

An incremental build produces exactly what a build from nothing produces, and the next build
runs nothing — or it is a bug.

- Every step is keyed by the content of its inputs, and outputs live in a content-addressed
  store. A key names content and a project-relative path, never the checkout, so a second
  worktree, another agent, or a CI runner at the same commit restores what the first one built
  ([Cache](cache.md)).
- `jk explain` forecasts each step's hit or miss and the ETA before the build spends the time
  ([Explain](explain.md)).
- The [clean-build oracle](../../bench/oracle/README.md) holds that claim on every platform:
  each class of edit — body, signature, constants, rename, case-only rename, package move,
  resources, CRLF, a test that starts reading an undeclared file, a branch switch, a stash, an
  edit landing mid-build — is built incrementally, rebuilt to prove nothing runs, and compared
  byte for byte against a fresh build in a fresh cache. `jk verify` does the same for a
  project's artifacts.

### 3. Fast and frugal

<!-- wall-claims:begin -->
On [spring-petclinic](performance.md), JumpKick is faster than Gradle on every row: clean build (0.44 s against 2.16 s), warm rebuild (0.10 s against 0.41 s), no-op (0.07 s against 0.37 s), one-file edit (0.21 s against 0.45 s) and test run (23.04 s against 23.67 s). It is faster than Maven on every row: clean build (0.44 s against 2.37 s), warm rebuild (0.10 s against 2.36 s), no-op (0.07 s against 1.79 s), one-file edit (0.21 s against 2.38 s) and test run (23.04 s against 23.71 s).

Between builds JumpKick keeps 186 MiB resident, all of it the engine, whose heap is capped at 256 MiB and trimmed back after every job. A warm compiler leaves 10 minutes after its project's last request or edit, or as soon as the host runs short of memory. One engine serves every project on the machine. Gradle keeps 1,712 MiB, 9.2× as much: its daemon, which idles for three hours by default, and its workers. Maven keeps nothing resident and starts a fresh JVM for every build instead (1.79 s for a no-op). Peak whole-tree RSS during a run is lower than Gradle's on warm rebuild, no-op and one-file edit and higher on clean build and test run; it is higher than Maven's on every row.
<!-- wall-claims:end -->

The table behind those sentences — JumpKick, Gradle and Maven on
[spring-petclinic](https://github.com/spring-projects/spring-petclinic) doing the same work —
with how it was measured and what "warm" means per tool, is in [Performance](performance.md):

<!-- wall-table:begin -->
| Scenario | JumpKick median / p90 | Gradle median / p90 | Maven median / p90 |
|---|---:|---:|---:|
| Clean build | 0.44 s / 2.28 s | 2.16 s / 8.01 s | 2.37 s / 2.54 s |
| Warm rebuild | 0.10 s / 0.13 s | 0.41 s / 0.43 s | 2.36 s / 2.40 s |
| No-op | 0.07 s / 0.08 s | 0.37 s / 0.38 s | 1.79 s / 1.83 s |
| One-file edit | 0.21 s / 0.41 s | 0.45 s / 0.49 s | 2.38 s / 2.40 s |
| Test run | 23.04 s / 24.80 s | 23.67 s / 47.61 s | 23.71 s / 28.96 s |

| Scenario | JumpKick peak RSS | Gradle peak RSS | Maven peak RSS |
|---|---:|---:|---:|
| Clean build | 1,349 MiB | 1,336 MiB | 543 MiB |
| Warm rebuild | 859 MiB | 1,343 MiB | 522 MiB |
| No-op | 900 MiB | 1,343 MiB | 440 MiB |
| One-file edit | 976 MiB | 1,497 MiB | 538 MiB |
| Test run | 2,211 MiB | 1,807 MiB | 1,258 MiB |
| Idle, 10 minutes after the test run | 186 MiB | 1,712 MiB | 0 MiB |

Measured on AMD Ryzen 9 7900X 12-Core Processor (24 logical CPUs, 30 GiB RAM), fedora-45, kernel 7.2.15-300.fc45.x86_64; project spring-projects/spring-petclinic; Gradle 9.9.0; JumpKick 0.50.4-SNAPSHOT; Maven 3.10.0; 5 timed runs per cell. Banked 2026-11-09 at JumpKick tree 1ac34fa27. Conditions: JumpKick loadavg 0.4 at start, engine live jobs ?; Gradle loadavg 0.4 at start, engine live jobs ?; Maven loadavg 0.4 at start, engine live jobs ?.
<!-- wall-table:end -->

Agents in several worktrees share that engine and its action cache ([Engine](engine.md)).
Compilers and test JVMs are forked workers that lease memory from one budget, learn the heap
each one really uses, and shard a suite only as wide as its measured class times make useful.

### 4. See what your agent is doing

Most of the builds on a developer's machine are now started by an agent, and the human has no
terminal of their own to read. The [web dashboard](web.md) is the supervisor's view: every run
with its trigger and session (`MCP · claude-code 3f9a`, a shell, the IDE), what changed since
the previous attempt, why it failed and how long it took, following the newest run live.

The terminal shows the same facts: live progress with an honest ETA, terminal hyperlinks, a
desktop notification when a long build finishes ([Config](config.md)). One build model, three
views — terminal, browser, MCP.

### 5. Batteries Included

The daily tools ship in the binary, not as plugins a project has to discover:

| Area | Built in | Reference |
|---|---|---|
| Toolchains | JDK and Node.js install, pin and discovery; `jk activate` shell hooks; `java = 17` cross-compiles on the host JDK | [JDK](jdk.md) · [Node.js](node.md) |
| Code quality | `jk format`; null checking with one `[nullness]` table (JSpecify + NullAway); Checkstyle, PMD, SpotBugs and detekt as cached steps; house rules as data | [Format](format.md) · [Null checking](build.md#null-checking-nullness) · [Lint](lint.md) · [Guards](guards.md) |
| Code generation | OpenAPI, jOOQ, Avro, ANTLR, JAXB, protobuf, any `main` | [Generate](generate.md) |
| Packaging | Fat and R8-minified jars, GraalVM native images, daemonless OCI images, AOT cache training | [Packaging](packaging.md) · [Native](native.md) · [Images](images.md) |
| Supply chain | CycloneDX and SPDX, `jk audit`, `jk deny`, GPG, Sigstore, SLSA, `jk verify` | [Security](security.md) · [Publish](publish.md) |
| Ship | `jk release`, `jk publish` to the Central Portal or any Maven repository | [Release](release.md) |
| Frameworks | Spring Boot, Quarkus, Micronaut; Android, Grails, Scala 3 as contrib batteries | [Frameworks](frameworks.md) · [Plugins](plugins.md) |
| Tools | `jkx` for any JVM tool or JBang script; Flyway and Liquibase | [Tools](tools.md) · [Database](database.md) |

### 6. A modern, declarative core

`jk.toml` is TOML: plugins add tables, never a language ([Projects](projects.md)).
`jk-lock.toml` records every version and checksum, and `jk build` never re-resolves a valid lock
([Lockfile](lockfile.md)). PubGrub builds the lowest version that satisfies every dependency, as
Go and NuGet do, never a release nobody asked for; it enforces a BOM as a platform and explains a
conflict in prose ([Dependencies](dependencies.md#how-a-version-is-chosen)). Git and path dependencies are
SHA-pinned in the lock; a workspace has one root lockfile ([Workspaces](workspaces.md)).
`jk add` and `jk new` write today's newest stable, and `jk outdated` / `jk update` keep it there.

### 7. Switch without a rewrite

`jk mvn` runs the real Maven build and writes the same `jk-results.md`; `jk build` and
`jk test` work in a directory with a `pom.xml` and no `jk.toml`. `jk import` reads the effective
POM — parents, profiles, plugin mapping — and writes a fidelity report; `jk export` goes back.
`jk gradle` and Gradle import cover the other side ([Migration](migration.md)). JumpKick reads
`~/.m2`, installs JDKs where IntelliJ already looks, and honours `settings.xml`
([Comparison](comparison.md#sharing-the-machine)).

---

## The hole in the market

### What the surveys still say

Public surveys of Java developers still put **Maven first** and **Gradle second** — for
years, not as a blip.

| Source | Maven | Gradle | Notes |
|--------|------:|-------:|-------|
| [JetBrains *State of Java 2025*](https://lp.jetbrains.com/the-state-of-java-2025/) | **~67%** | clear #2 | Maven still “most widely used build system.” |
| [JetBrains Dev Ecosystem 2022 — Java](https://www.jetbrains.com/lp/devecosystem-2022/java/) | **~73%** | **~50%** | Multi-select; overlap expected. |
| JetBrains series ~2019–2025 | Maven stays on top | Gradle plateaus as #2 | Stable duopoly, not a Gradle takeover. |

**Segment reality**

| Segment | Typical default | Why |
|---------|-----------------|-----|
| Spring / enterprise services | **Maven** | Parent POMs, BOMs, CI templates, reviewable config |
| Android / much of Kotlin mobile | **Gradle** | Platform tooling assumes it |
| Greenfield “custom everything” | Often **Gradle** (or Bazel-class) | Escape hatch is the product |
| Regulated / long-lived backends | Often **Maven** | Predictable shape over infinite flexibility |

Gradle did not fail commercially — it is an obvious #2 with monopoly power in Android.
It did **not** displace Maven as the Java default, because a large slice of the market
never wanted “a better programming language for builds.” They wanted **a better build
tool.**

### Maven: right shape, wrong surface

Maven’s enduring advantage is philosophical:

- A **standard lifecycle** and a **finite vocabulary**
- The build is **data** (`pom.xml`), not an open-ended program
- New contributors can read the project without learning a DSL

The 2026 tax: verbose XML, weak CLI next to Cargo/uv, lockfiles as bolt-ons, no default
“skip what you can prove is done,” and **nothing agent-native** about failure output.

### Gradle: more power, worse default for agents

Gradle fixed real Maven pain when the build is expertly written. Its product bet —

> Here is a box of Legos. Good luck.

— is hostile to agent edits. The build **is** software (Groovy/Kotlin DSL, plugins,
configuration-cache caveats, convention plugins). Every shop reinvents house style.
Agents thrash on DSL folklore; humans maintain a second product forever.

### What people actually want next

1. **More approachable than Maven** — no XML tax; modern config and CLI  
2. **More constrained than Gradle** — a finite build model, not an open language  
3. **Reproducible by default, and repeat builds that skip proven work** — lockfile as law; cache what you can prove  
4. **Still on Maven Central** — same coordinates and gravity  
5. **Closed-loop for coding agents** — structured observe/repair, not log scraping  
6. **A named, cheap default test rung** — so agents neither run the world every turn nor skip the wiring tests that catch real bugs  

That is JumpKick.

---

## JumpKick’s answer

JumpKick takes Maven’s winning idea (a **declarative, conventional build**) and Gradle’s
valid performance ambitions (skip work, graph awareness), then rejects both tools’ worst
ergonomics — and adds an **agent-closed loop** as a first-class surface.

| Principle | What it means |
|-----------|----------------|
| **Closed loop for agents** | `jk skill`, `jk-results.md`, MCP diagnostics/run — same model as the human CLI |
| **Cheapest test rung first** | Unit on every edit; the named `--guard` rung before share; e2e / `--all` on purpose. Suites are scope; tags are cost. |
| **Data, not a program** | `jk.toml` is TOML — readable, editable, reviewable |
| **Finite shape** | Convention-over-configuration; plugins extend a known model |
| **Lockfile is law** | `jk-lock.toml` at the workspace root; `jk build` does not re-resolve when valid |
| **Correct resolve you can read** | PubGrub; `jk why` / conflict prose |
| **Cargo / uv ergonomics** | Native CLI, `add` / `remove` / `update` / `tree` / `outdated` |
| **Maven Central native** | Same GAV gravity; import / `jk mvn` / `jk gradle` when not ready to rewrite |
| **Power outside the manifest** | Advanced behavior in plugins and hatches — never “the build file became an app” |

```toml
# jk.toml — the whole default build story
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
jnats = "latest"

[platform-dependencies]
spring-boot-dependencies = "4.1.0"
```

That is the upgrade path people meant when they said “there has to be something better
than a POM” — **without** answering “so write Kotlin to compile Java,” and **with** a
surface coding agents can drive.

## Test rungs

Maven and Gradle can *run* tests. They cannot *tell an agent which tests to run this
turn.* Surefire vs Failsafe is folklore. Gradle `sourceSets` is a comment in a
convention plugin the next model has never seen. Agents then do one of two expensive
things: run everything (`--all`, Playwright, Testcontainers, Maven Central) on every
assertion fix, or never leave the default suite and discover wiring bugs in CI.

JumpKick’s bet is a **pyramid for writing tests** and a **named ladder for running them.**

| Rung | Where it lives | What is real | Who runs it |
|------|----------------|--------------|-------------|
| **Unit** | `src/test/…` (or `test/src/`) | The class under test | Every inner-loop turn. `jk test`. |
| **Integration** | `src/integration/…` | One module + nearby collaborators (one Testcontainer is fine) | Before share. Named **`--guard`**. |
| **E2E** | `src/e2e/…` | The product as a user/CI would: Playwright, compose, full fixtures | CI / nightly. Locally a judgment call. `jk test -A` is not a habit. |

Cost is a **tag**, not a fourth directory: `@Tag("slow")`, `@Tag("network")`. A 90-second
Kafka test is still integration *scope*; it is the wrong *budget* for `--guard`.

House-rule / check scripts (the things JumpKick itself runs from `.jk/scripts/` today on every
build) belong on that same named bar: runnable **with** `--guard`, **alone**
(`--scripts-only`), or **skipped** (`--no-scripts`). They are not a surprise tax on
`jk test` while you fix `assertEquals`.

The observe step has to match: `target/jk-results.md` (and MCP `build`) must
disclose **what ran, what did not, how to replay, what to run next.** A green unit
run that silently skipped integration is how wiring bugs escape.

Commands and layout: [Test](test.md). Default `jk test` is the unit suite.
`--guard` is the share-the-commit bar; `--scripts-only` runs the guard scripts alone;
`--no-scripts` skips them.

---

## Positioning

| | Maven | Gradle | JumpKick |
|---|---|---|---|
| Build is… | Data (XML) | Code (Groovy/Kotlin) | Data (TOML) |
| Shape | Finite lifecycle | Open-ended graph | Finite + conventions |
| Agent I/O | Log scrape | Log scrape | Results + MCP first-class |
| Tests | Surefire vs Failsafe folklore (`*IT.java`) | `sourceSets` + house style | **Named rungs** + results that disclose the skip |
| Everyday ergonomics | Weak CLI, heavy files | Powerful, high ceremony | Terse CLI + ETA |
| Flexibility | Plugins, limited | Near-unlimited | Plugins + hatch outside TOML |
| Reproducibility | Possible | Possible | **Default** (lockfile law) |
| Market fit today | **#1 Java overall** | **#2; #1 Android** | Declarative successor + agent loop |
| Migration story | — | Foreign to many Maven shops | Import from Maven/Gradle |

**One line:** Maven proved the market wants a **predictable build**. Gradle proved some
teams need **power when the default isn’t enough**. Coding agents proved the next bottleneck
is **closed-loop diagnose and repair** — including *which tests to run this turn*. JumpKick
aims at all four.

---

## Agents reach green

The metric the agent-first pillar answers to: for a fixed set of "an agent broke the build"
scenarios, the median turns, tokens and wall time to green on JumpKick, Maven and Gradle.

Measured 2026-09-28/29 with JumpKick at `3f03d9180` (0.14.0 plus the agent report and the five-tool
MCP surface), Maven 3.9.16 and Gradle 9.8.0: 165 scenarios over 19 Spring guides and the JUnit
5 starter, each agent driving all three tools with the same prompt and a 16-turn budget, on one
host. Maven and Gradle run through the wrappers in `bench/agent-loop` — the same results file
and MCP tools JumpKick offers, built deliberately well — so the comparison is the output and the
hints, not the presence of an agent surface. Five agents from three vendors, each at one model
and reasoning effort ([results](../../bench/agent-loop/results/); the fast agent's subset and the
second table below were measured later the same day with JumpKick at `f8dab1711`):

| Median per scenario, JumpKick / Maven / Gradle | Fixed | Turns | Input + cache tokens | Output tokens | Wall to green |
|---|---|---|---|---|---|
| Vendor A, frontier | 100 / 100 / 100% | 5 / 5 / 6 | 23k / 25k / 31k | 0.6k / 0.5k / 0.5k | 11 / 14 / 15 s |
| Vendor A, mid-tier | 100 / 97 / 100% | 5 / 6 / 6 | 34k / 44k / 43k | 0.7k / 0.7k / 0.6k | 12 / 15 / 14 s |
| Vendor B, frontier | 100 / 100 / 100% | 6 / 8 / 7 | 46k / 69k / 62k | 1.5k / 2.6k / 2.2k | 19 / 32 / 28 s |
| Vendor B, budget | 100 / 100 / 100% | 6 / 7 / 7 | 39k / 46k / 52k | 0.9k / 1.2k / 1.2k | 14 / 22 / 22 s |
| Vendor C, fast (24-run subset) | 88 / 75 / 62% | 11 / 11.5 / 11 | 115k / 125k / 115k | 3.6k / 3.0k / 2.3k | 38 / 39 / 36 s |

JumpKick has the shortest wall on four of the five agents and the fewest input tokens on four, and
ties or beats the incumbents on turns. Output tokens are not a win yet: Vendor A's and Vendor
C's agents write more on JumpKick than on the wrappers. The fast agent runs out of its turn budget on
every tool, so its row measures the agent more than the build.

Beyond the Gradle guides: eight more repos, Maven-first (JumpKick imports the `pom.xml`: the JUnit Maven
starter, commons-cli, spring-petclinic, and gs-multi-module with two modules) and Kotlin (the JUnit
Maven and Gradle Kotlin starters, spring-petclinic-kotlin, tut-spring-boot-kotlin), 27 scenarios.
Each repo builds with Maven or Gradle, not both, so JumpKick is compared with each on the scenarios they
share (15 each), same date, commit and budget:

| Median, JumpKick / other on the same scenarios | vs | Fixed | Turns | Input + cache | Output | Wall |
|---|---|---|---|---|---|---|
| Vendor A, frontier | Maven | 15 / 15 | 5 / 6 | 24k / 26k | 0.6k / 0.6k | 14 / 19 s |
| Vendor A, frontier | Gradle | 15 / 15 | 7 / 6 | 36k / 31k | 1.0k / 0.6k | 15 / 19 s |
| Vendor A, mid-tier | Maven | 14 / 14 | 5 / 5 | 35k / 42k | 0.7k / 0.7k | 11 / 16 s |
| Vendor A, mid-tier | Gradle | 13 / 14 | 8 / 6.5 | 61k / 52k | 1.2k / 0.6k | 16 / 18 s |
| Vendor B, frontier | Maven | 15 / 12 | 6 / 8 | 74k / 100k | 3.7k / 3.7k | 35 / 52 s |
| Vendor B, frontier | Gradle | 15 / 15 | 7 / 8 | 101k / 119k | 4.5k / 5.5k | 51 / 56 s |
| Vendor B, budget | Maven | 15 / 12 | 6 / 7.5 | 42k / 56k | 1.1k / 1.3k | 18 / 43 s |
| Vendor B, budget | Gradle | 13 / 14 | 7 / 7 | 56k / 63k | 2.1k / 1.4k | 28 / 34 s |

Against Maven JumpKick wins or ties every column on every agent. Against Gradle on the Kotlin repos JumpKick
is faster ([the second table](#agents-reach-green)) but not yet cheaper: Vendor A's
agents take a turn or two more and more tokens, most of it in one scenario where the agent
guessed a dependency's group from its Kotlin package and JumpKick's replies did not steer it to the
real coordinate.

On JumpKick 0.14.0, before the agent report, the same matrix fixed 65% within 8 turns at 85k input
tokens. The table is re-run each release ([Releases](../contributors/releases.md#benchmarks-each-release)).

### Asking about the code

Before an agent changes a method it asks who calls it and what can end up running it. Nine such
questions, on spring-petclinic, commons-lang and JumpKick itself, two of them through dynamic
dispatch and one about what an older version of a dependency would break, are graded against the
compiler's answer: correct means an F1 of at least 0.9. Each agent ran JumpKick as installed,
whose default MCP list carries `usages`, and the same JumpKick with every graph tool removed
([change tasks](../../bench/agent-loop/README.md#change-tasks), one run per question and
arm). Turns, tokens (input, output, cache read and write) and wall are the per-question medians
summed over the questions each agent ran both ways:

<!-- agents-questions:begin -->
| Agent (questions) | Correct, JumpKick / without the graph | Turns | Tokens | Wall |
|---|---|---|---|---|
| Vendor A, frontier (9) | 9 of 9 / 9 of 9 | 40 / 167 | 327k / 3.9M | 157 / 594 s |
| Vendor B, frontier (9) | 9 of 9 / 6 of 9 | 73 / 331 | 2.1M / 23.6M | 1,017 / 3,128 s |
| Vendor C, frontier (9) | 8 of 9 / 8 of 9 | 55 / 149 | 791k / 19.0M | 536 / 1,766 s |
| Vendor D (9) | 9 of 9 / 8 of 9 | 46 / 161 | 1.1M / 8.3M | 431 / 688 s |
| Vendor E (9) | 8 of 9 / 7 of 9 | 171 / 310 | 9.0M / 33.4M | 1,686 / 2,038 s |
| Vendor F (9) | 9 of 9 / 9 of 9 | 41 / 69 | 1.2M / 2.7M | 228 / 354 s |
| Vendor G (9) | 7 of 9 / 7 of 9 | 75 / 226 | 2.4M / 10.8M | 740 / 2,540 s |

JumpKick is each agent as `jk agents` sets it up (Vendor C and D with JumpKick's rules file); without the graph, the graph hidden from MCP and the shell alike. One run per task on each, on the current build. Medians per task, summed over the tasks each agent ran on both arms; results: `2026-11-23-change-tasks`.
<!-- agents-questions:end -->

The answer is `usages` (`transitive=true` for everything that reaches the method): complete from
the compiled classes, where grep finds names one file at a time and an agent then reads each file
to rule out the overloads, the other types with a method of that name, and the calls through an
interface. Java and Kotlin make that search long: dispatch, overloads, lambdas and method
references hide the call from a text search.

### Changing code

Renaming a method is the edit where the search costs most: every override, every call and every
doc link, in main and test sources, across modules. `rename` does it in one call from the compiled
classes and says which modules to test; without it the agent greps, reads and edits file by file,
and rebuilds until the compiler stops naming a site. Hidden acceptance tests check the result:

<!-- agents-renames:begin -->
| Agent (renames) | Correct, JumpKick / without the graph | Turns | Tokens | Wall |
|---|---|---|---|---|
| Vendor A, frontier (8) | 8 of 8 / 8 of 8 | 31 / 134 | 184k / 896k | 495 / 676 s |
| Vendor B, frontier (8) | 8 of 8 / 8 of 8 | 37 / 147 | 564k / 4.0M | 455 / 1,293 s |
| Vendor C, frontier (8) | 8 of 8 / 8 of 8 | 31 / 84 | 245k / 1.5M | 488 / 1,460 s |
| Vendor D (8) | 8 of 8 / 8 of 8 | 23 / 94 | 566k / 2.6M | 766 / 391 s |
| Vendor E (8) | 8 of 8 / 8 of 8 | 74 / 259 | 3.1M / 19.0M | 953 / 822 s |
| Vendor F (8) | 8 of 8 / 8 of 8 | 22 / 60 | 710k / 3.1M | 183 / 1,057 s |
| Vendor G (8) | 8 of 8 / 8 of 8 | 36 / 66 | 774k / 1.8M | 289 / 561 s |

JumpKick is each agent as `jk agents` sets it up (Vendor C and D with JumpKick's rules file); without the graph, the graph hidden from MCP and the shell alike. One run per task on each, on the current build. Medians per task, summed over the tasks each agent ran on both arms; results: `2026-11-23-change-tasks`.
<!-- agents-renames:end -->

### What it costs

The same questions and renames in dollars:

<!-- agents-dollars:begin -->
With JumpKick's code graph a question cost the agents **$0.037–$0.311** per task, against **$0.112–$1.78** without it. A rename cost **$0.020–$0.127** per task, against **$0.082–$0.474**.

| Agent | Per question, JumpKick / without the graph | Per rename |
|---|---|---|
| Vendor A, frontier | $0.067 / $0.475 | $0.039 / $0.188 |
| Vendor B, frontier | $0.239 / $1.78 | $0.111 / $0.459 |
| Vendor C, frontier | $0.126 / $1.28 | $0.059 / $0.206 |
| Vendor D | $0.037 / $0.240 | $0.020 / $0.082 |
| Vendor E | $0.311 / $0.815 | $0.127 / $0.474 |
| Vendor F | $0.049 / $0.112 | $0.032 / $0.092 |

An API list-price estimate, not a bill: the tokens each agent reported in the runs above, priced at its vendor's published API list price for its model, read 2026-11-17 and 2026-11-22 ([prices](../../shared/client-io/src/main/resources/cc/jumpkick/prices/prices.toml)). An agent on a subscription pays its plan instead. Each kind of token (input, output, cache read, cache write) is the median per task, summed over the tasks the agent ran both ways; the sum is priced, then divided by the number of tasks. Left out, with no price row for its model: Vendor G.
<!-- agents-dollars:end -->

### How small a repository

On a small repository grep is cheap and an agent reads every file anyway, so the graph's per-turn
cost could outweigh what it saves. The same rename on repositories from a two-module Spring guide
to JumpKick itself, each with and without the graph (one run each):

<!-- agents-curve:begin -->
| Repository | Lines | Rename | Vendor A, frontier: turns · tokens, JumpKick / without | Vendor D: turns · tokens, JumpKick / without |
|---|---:|---|---|---|
| gs-multi-module | 83 | `MyService.message` | 2 / 12 · 12k / 48k | 1 / 6 · 23k / 97k |
| gs-uploading-files | 416 | `StorageService.loadAsResource` | 2 / 13 · 12k / 40k | 1 / 7 · 23k / 120k |
| spring-petclinic | 3,635 | `Owner.addVisit` | 2 / 10 · 13k / 46k | 1 / 10 · 23k / 146k |
| spring-petclinic | 3,635 | `telephone` | 8 / 21 · 49k / 129k | 3 / 12 · 49k / 242k |
| commons-cli | 17,862 | `Util.stripLeadingHyphens` | 2 / 17 · 13k / 55k | 2 / 5 · 35k / 77k |
| commons-lang | 188,332 | `CharSequenceUtils.indexOf` | 2 / 18 · 13k / 138k | 1 / 20 · 23k / 431k |
| commons-lang | 188,332 | `Range.contains` | 2 / 25 · 13k / 221k | 2 / 23 · 35k / 1.0M |
| JumpKick | 994,905 | `CodeGraph.between` | 11 / 18 · 60k / 219k | 12 / 11 · 353k / 445k |
<!-- agents-curve:end -->

## Where it is going

The workbench today is the graph with `usages` and `rename`, the build and test loop, guards, the
dashboard and the batteries above. Next, in the order planned: more compiler-proved refactorings
(change signature, move member, safe delete); lookup of a library's real API at the locked
version, so an agent does not invent one; test selection per class from a change's cone; a ledger
of the time, turns and tokens JumpKick saved; starting, watching and stopping the application
over MCP; and JumpKick Dojo, a shared cache service for teams. None of these is described
elsewhere in these pages until it ships.

## What is still open

The loop's surfaces are real: `jk skill`, results, MCP, TOML edits, the lockfile, the cache,
directory suites, `--guard` / `--scripts-only` / `--no-scripts`, effective-POM import with
profiles and plugin mapping, `jk mvn` writing `target/jk-results.md`, a build over an unmodified
`pom.xml`, and the IntelliJ external-system model. What is not, in
[the 1.0 plan](../contributors/plan-1.0.md):

- **Turns-to-green is one host.** On Kotlin repos built with Gradle, JumpKick's agents spend more
  turns and tokens than Gradle's ([the second table](#agents-reach-green)).
- **The cache claim has no comparison yet.** The oracle proves JumpKick against a fresh build; the
  same edit classes against Gradle and Maven, and the hit rate across checkouts, are not
  measured.
- **IntelliJ and VS Code are not on their marketplaces.** The IntelliJ plugin is an external
  system packaged from this repository.
- **The plugin SDK is not on Maven Central.** `jk publish -c` can publish it; a release has not
  put the coordinate there.
- **Maven top-20 corpus:** 13 of 20 build and 2 of 20 pass their tests (run 16, main
  `30c21270b`, 2026-09-18). The table is in
  [Migration](migration.md#where-import-stands-on-real-repositories).
- **Speed and memory are measured on one single-module project and one host**
  ([Performance](performance.md)).

---

## Sources

- JetBrains, [*The State of Java 2025*](https://lp.jetbrains.com/the-state-of-java-2025/)
- JetBrains, [*State of Developer Ecosystem 2022 — Java*](https://www.jetbrains.com/lp/devecosystem-2022/java/)

Survey percentages are self-reported and multi-select; treat them as order-of-magnitude market
signal. The signal is consistent: declarative still wins headcount, and Gradle did not become
the universal Maven replacement.

## Related

- [Comparison](comparison.md) — the feature matrix against Maven and Gradle, losses included
- [Performance](performance.md) — the measured table and how it is run
- [The 1.0 plan](../contributors/plan-1.0.md)
- [Getting started](getting-started.md) · [Skill](skill.md) · [Agents](agents.md) · [MCP](mcp.md)
- [Test](test.md) · [Guards](guards.md) · [Concepts](concepts.md)
- [README](../../README.md)
