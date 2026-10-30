# Why JumpKick

> **JumpKick is the JVM build system built for coding agents — and for the humans supervising
> them.**

**Audience:** Maven or Gradle users evaluating a switch, and anyone building JVM software with
coding agents. How to *use* JumpKick: [skill](skill.md) · [getting started](getting-started.md) ·
[agents](agents.md).

The reason to leave Maven or Gradle is that the **edit → build → diagnose → fix → rebuild**
loop gets shorter. An agent runs that loop hundreds of times a day, and the human watching it
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
| MCP in the engine | `run` returns a verdict; `diagnostics`, `deps` and `skill` complete the default list of four, because every card costs the agent context on every turn. Two agents on one engine each keep their own project bind | [MCP](mcp.md) |
| `jk skill` | The system prompt for a tool models were not trained on; jk keeps its section of `AGENTS.md` current on every build | [Skill](skill.md) · [Agents](agents.md) |
| `jk graph` | A code graph built in: code, config and docs, with nothing to install; indexed in seconds, with calls and overrides from the compiled classes. Answers name each symbol `Owner.member()` at `file:line` | [Code graph](graph.md) |
| Named test rungs | The cheap unit suite by default, `--guard` before sharing, `--all` nightly; the report discloses what did not run | [Test rungs](#test-rungs) |
| Guards and hooks | House rules as data: each violation names the rule, the sanctioned alternative and why, so the fix is mechanical; git hooks refuse banned commit trailers and hand-edited guard files | [Guards](guards.md) · [Agents](agents.md#hooks-and-protected-files) |

The measure is turns, tokens and wall time to green on broken builds, against Maven and Gradle
given the same agent tooling: [Agents reach green](#agents-reach-green).

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
On [spring-petclinic](performance.md), jk is faster than Gradle on every row: clean build (0.37 s against 2.69 s), warm rebuild (0.09 s against 0.57 s), no-op (0.05 s against 0.53 s), one-file edit (0.13 s against 0.61 s) and test run (8.54 s against 9.97 s). It is faster than Maven on every row: clean build (0.37 s against 3.16 s), warm rebuild (0.09 s against 3.16 s), no-op (0.05 s against 2.37 s), one-file edit (0.13 s against 3.14 s) and test run (8.54 s against 10.10 s).

Between builds jk keeps 863 MiB resident: 171 MiB for the engine, whose heap is capped at 256 MiB and trimmed back after every job, and the rest for warm compilers that exit after 30 idle minutes or as soon as the host runs short of memory. One engine serves every project on the machine. Gradle keeps 1,734 MiB, 2.0× as much: its daemon, which idles for three hours by default, and its workers. Maven keeps nothing resident and starts a fresh JVM for every build instead (2.37 s for a no-op). Peak whole-tree RSS during a run is lower than Gradle's on clean build, warm rebuild, no-op and one-file edit and higher on test run; it is higher than Maven's on every row.
<!-- wall-claims:end -->

The table behind those sentences — jk, Gradle and Maven on
[spring-petclinic](https://github.com/spring-projects/spring-petclinic) doing the same work —
with how it was measured and what "warm" means per tool, is in [Performance](performance.md):

<!-- wall-table:begin -->
| Scenario | jk median / p90 | Gradle median / p90 | Maven median / p90 |
|---|---:|---:|---:|
| Clean build | 0.37 s / 3.37 s | 2.69 s / 10.02 s | 3.16 s / 3.23 s |
| Warm rebuild | 0.09 s / 0.12 s | 0.57 s / 0.60 s | 3.16 s / 3.20 s |
| No-op | 0.05 s / 0.05 s | 0.53 s / 0.56 s | 2.37 s / 2.42 s |
| One-file edit | 0.13 s / 0.21 s | 0.61 s / 0.65 s | 3.14 s / 3.23 s |
| Test run | 8.54 s / 8.84 s | 9.97 s / 18.27 s | 10.10 s / 11.42 s |

| Scenario | jk peak RSS | Gradle peak RSS | Maven peak RSS |
|---|---:|---:|---:|
| Clean build | 593 MiB | 1,464 MiB | 546 MiB |
| Warm rebuild | 544 MiB | 1,521 MiB | 540 MiB |
| No-op | 573 MiB | 1,526 MiB | 452 MiB |
| One-file edit | 620 MiB | 1,611 MiB | 535 MiB |
| Test run | 1,993 MiB | 1,847 MiB | 1,189 MiB |
| Idle, a minute after the test run | 863 MiB | 1,734 MiB | 0 MiB |

Measured on 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 12 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL (host `c9ab742f7b18`); project spring-projects/spring-petclinic; Gradle 9.8.2; Maven 3.10.0; jk 0.41.1; 5 timed runs per cell. Banked 2026-10-26 at jk tree 1880fb554. Conditions: jk loadavg 1.4 at start, engine live jobs ?; Gradle loadavg 1.4 at start, engine live jobs ?; Maven loadavg 1.4 at start, engine live jobs ?.
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
([Lockfile](lockfile.md)). PubGrub resolves highest-wins, enforces a BOM as a platform, and
explains a conflict in prose ([Dependencies](dependencies.md)). Git and path dependencies are
SHA-pinned in the lock; a workspace has one root lockfile ([Workspaces](workspaces.md)).
`jk add` and `jk new` write today's newest stable, and `jk outdated` / `jk update` keep it there.

### 7. Switch without a rewrite

`jk mvn` runs the real Maven build and writes the same `jk-results.md`; `jk build` and
`jk test` work in a directory with a `pom.xml` and no `jk.toml`. `jk import` reads the effective
POM — parents, profiles, plugin mapping — and writes a fidelity report; `jk export` goes back.
`jk gradle` and Gradle import cover the other side ([Migration](migration.md)). jk shares
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

House-rule / check scripts (the things JumpKick itself runs from `.jk/` today on every
build) belong on that same named bar: runnable **with** `--guard`, **alone**
(`--scripts-only`), or **skipped** (`--no-scripts`). They are not a surprise tax on
`jk test` while you fix `assertEquals`.

The observe step has to match: `target/jk-results.md` (and MCP `run`) must
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

Measured 2026-09-28/29 with jk at `3f03d9180` (0.14.0 plus the agent report and the five-tool
MCP surface), Maven 3.9.16 and Gradle 9.8.0: 165 scenarios over 19 Spring guides and the JUnit
5 starter, each agent driving all three tools with the same prompt and a 16-turn budget, on one
host. Maven and Gradle run through the wrappers in `bench/agent-loop` — the same results file
and MCP tools jk offers, built deliberately well — so the comparison is the output and the
hints, not the presence of an agent surface. Five agents from three vendors, each at one model
and reasoning effort ([results](../../bench/agent-loop/results/); the fast agent's subset and the
second table below were measured later the same day with jk at `f8dab1711`):

| Median per scenario, jk / Maven / Gradle | Fixed | Turns | Input + cache tokens | Output tokens | Wall to green |
|---|---|---|---|---|---|
| Vendor A, frontier | 100 / 100 / 100% | 5 / 5 / 6 | 23k / 25k / 31k | 0.6k / 0.5k / 0.5k | 11 / 14 / 15 s |
| Vendor A, mid-tier | 100 / 97 / 100% | 5 / 6 / 6 | 34k / 44k / 43k | 0.7k / 0.7k / 0.6k | 12 / 15 / 14 s |
| Vendor B, frontier | 100 / 100 / 100% | 6 / 8 / 7 | 46k / 69k / 62k | 1.5k / 2.6k / 2.2k | 19 / 32 / 28 s |
| Vendor B, budget | 100 / 100 / 100% | 6 / 7 / 7 | 39k / 46k / 52k | 0.9k / 1.2k / 1.2k | 14 / 22 / 22 s |
| Vendor C, fast (24-run subset) | 88 / 75 / 62% | 11 / 11.5 / 11 | 115k / 125k / 115k | 3.6k / 3.0k / 2.3k | 38 / 39 / 36 s |

jk has the shortest wall on four of the five agents and the fewest input tokens on four, and
ties or beats the incumbents on turns. Output tokens are not a win yet: Vendor A's and Vendor
C's agents write more on jk than on the wrappers. The fast agent runs out of its turn budget on
every tool, so its row measures the agent more than the build.

Beyond the Gradle guides: eight more repos, Maven-first (jk imports the `pom.xml`: the JUnit Maven
starter, commons-cli, spring-petclinic, and gs-multi-module with two modules) and Kotlin (the JUnit
Maven and Gradle Kotlin starters, spring-petclinic-kotlin, tut-spring-boot-kotlin), 27 scenarios.
Each repo builds with Maven or Gradle, not both, so jk is compared with each on the scenarios they
share (15 each), same date, commit and budget:

| Median, jk / other on the same scenarios | vs | Fixed | Turns | Input + cache | Output | Wall |
|---|---|---|---|---|---|---|
| Vendor A, frontier | Maven | 15 / 15 | 5 / 6 | 24k / 26k | 0.6k / 0.6k | 14 / 19 s |
| Vendor A, frontier | Gradle | 15 / 15 | 7 / 6 | 36k / 31k | 1.0k / 0.6k | 15 / 19 s |
| Vendor A, mid-tier | Maven | 14 / 14 | 5 / 5 | 35k / 42k | 0.7k / 0.7k | 11 / 16 s |
| Vendor A, mid-tier | Gradle | 13 / 14 | 8 / 6.5 | 61k / 52k | 1.2k / 0.6k | 16 / 18 s |
| Vendor B, frontier | Maven | 15 / 12 | 6 / 8 | 74k / 100k | 3.7k / 3.7k | 35 / 52 s |
| Vendor B, frontier | Gradle | 15 / 15 | 7 / 8 | 101k / 119k | 4.5k / 5.5k | 51 / 56 s |
| Vendor B, budget | Maven | 15 / 12 | 6 / 7.5 | 42k / 56k | 1.1k / 1.3k | 18 / 43 s |
| Vendor B, budget | Gradle | 13 / 14 | 7 / 7 | 56k / 63k | 2.1k / 1.4k | 28 / 34 s |

Against Maven jk wins or ties every column on every agent. Against Gradle on the Kotlin repos jk
is faster ([the second table](#agents-reach-green)) but not yet cheaper: Vendor A's
agents take a turn or two more and more tokens, most of it in one scenario where the agent
guessed a dependency's group from its Kotlin package and jk's replies did not steer it to the
real coordinate.

On jk 0.14.0, before the agent report, the same matrix fixed 65% within 8 turns at 85k input
tokens. The table is re-run each release ([Releases](../contributors/releases.md#benchmarks-each-release)).

## What is still open

The loop's surfaces are real: `jk skill`, results, MCP, TOML edits, the lockfile, the cache,
directory suites, `--guard` / `--scripts-only` / `--no-scripts`, effective-POM import with
profiles and plugin mapping, `jk mvn` writing `target/jk-results.md`, a build over an unmodified
`pom.xml`, and the IntelliJ external-system model. What is not, in
[the 1.0 plan](../contributors/plan-1.0.md):

- **Turns-to-green is one host.** On Kotlin repos built with Gradle, jk's agents spend more
  turns and tokens than Gradle's ([the second table](#agents-reach-green)).
- **The cache claim has no comparison yet.** The oracle proves jk against a fresh build; the
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
