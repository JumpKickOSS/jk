# The 1.0 plan: the inner loop first

**Audience:** maintainers and agents deciding what to work on next. **Status:** the priority order
for everything between now and 1.0. Tickets live in KanArtist project `jk` under the label
`inner-loop-1.0`; one umbrella epic lists the six epics below. Product framing:
[Why JumpKick](../user/why.md).

## The one sentence

JumpKick wins or loses on a developer's daily **edit → build → diagnose → fix** cycle, with an
agent doing the edits and a human watching. Every 1.0 priority shortens that loop, proves that it
is shorter, or removes a reason a Maven shop cannot try it. Outer-loop work (CI lanes, nightly
profiles, release automation, remote caches) stays valuable and stays on the board; it does not
outrank anything here.

## Where the review left us

An adversarial product review on 2026-09-15 measured the tree against Maven, Gradle, Mill and
Bazel. The findings that matter for the plan:

| Finding | Consequence |
|---|---|
| The agent-loop claim has no measurement behind it | Build the benchmark before polishing the pitch |
| Maven import keeps only the compiler plugin; profiles, parents and resource filtering are dropped | A Maven shop cannot try jk on its real project |
| IDE support is generated `.iml` files and an unpublished five-action plugin | Most evaluations end in the first ten seconds |
| Daily-loop batteries (coverage in results, javadoc jar, Central Portal, codegen, lint-as-step) are thinner than the ship-path ones | Batteries-included is the right strategy with the wrong first picks |
| Speed and memory claims have no comparator and RSS is engine-only | The README promises what the harness has not shown |
| The web dashboard is framed as a dashboard | It is the supervisor's view of agent-driven builds, and that framing is the differentiator |

## The six epics, in priority order

### 1. Existing Maven projects work under jk (P0)

The uv lesson: get into existing repos first, convert the manifest later.

- **Effective POM.** Import consumes Maven's own effective model (parents flattened, dependency
  management merged, properties interpolated) instead of walking raw XML.
- **Structured results for `jk mvn`.** A Maven event spy writes `target/jk-results.md` and feeds
  MCP diagnostics in a repo that has no `jk.toml`. An agent gets the loop on day one.
- **Coexistence mode.** `jk build` and `jk test` run on an unmodified `pom.xml` with the effective
  POM as the project model: jk's engine, cache, incremental compiler and results, no migration.
- **Plugin-aware mapping** for the twenty most-used Maven plugins, ranked by usage and graded
  per plugin in the fidelity report: source-tree plugins (resources filtering, build-helper,
  jar manifest, sources and javadoc), test plugins (surefire and failsafe into suites and tags,
  jacoco into coverage), packaging plugins (shade and assembly, Boot repackage, jib, native).
- **Profiles** get a designed landing place or a crisp per-activation-kind reason.
- **A fidelity corpus:** the fifty most-starred Spring Boot and Quarkus repositories imported and
  tested, pass rate published in [Migration](../user/migration.md) against a named commit.
- Gradle import through the Tooling API follows at P2; multi-module Gradle builds import instead
  of erroring.

### 2. The agent loop is measured (P0)

The metric in [Why JumpKick](../user/why.md) is median agent turns, tokens and wall time to
green on a fixed set of broken-build scenarios. The comparator is **wrapped** Maven and Gradle: a
results file and MCP tools over their existing output, built deliberately well, because that is
what an incumbent would ship. If jk does not beat the wrapper by a wide margin, the wrapper is the
product and epic 1 absorbs it.

- A scenario corpus: twenty public repositories with injected compile, test, dependency and
  resource failures, replayable from one command.
- The wrappers.
- The harness: two agents, every scenario, three tools, one table.
- Results-file gaps the review found: compiler diagnostics gain a repair hint the way guard
  violations have one; the file reports its own token estimate.
- The numbers land in why.md against a named commit and are refreshed each release.

### 3. IntelliJ just works (P0)

- A **live project model** through the engine (IntelliJ's external-system API over the ide
  model), retiring generated `.iml` files.
- The JUnit gutter compiles to IntelliJ's own output directory, never into `target/classes`.
- One-click debug: `jk run --debug` and `jk test --debug` publish a JDWP address the plugin
  attaches to.
- Marketplace listings for IntelliJ and VS Code, published by the release script.
- BSP advertises Scala so Metals imports mixed Java/Scala modules.

### 4. Daily-loop batteries (P1)

Batteries-included is the strategy. The rule for picking the next battery is **how often a
Spring or Kotlin service touches the step in its daily loop**, not how impressive it is on a
feature matrix.

| Battery | Status today | Target |
|---|---|---|
| Plugin SDK on Maven Central | publishable: `jk publish --central` from `shared/host` and `shared/plugin-sdk` is a release step; the sample plugin compiles against the coordinate, first-party plugins keep the workspace edge so a release never needs a prior one | published with every release |
| Coverage | XML per module | summary in results, HTML report, guard floor |
| Sources and javadoc jars | both from `jk package`; javadoc for Java, Dokka (`[dokka]`) for a Kotlin or mixed module | done |
| Central Portal publish | `jk publish --central`: signed bundle, Portal upload, status poll, Publish block in results | done |
| Code generation | protobuf, annotation processors, KSP | a generator plugin; OpenAPI first, then jOOQ, Avro, ANTLR, JAXB |
| Lint as a cached step | Error Prone and NullAway via `[javac]`; Checkstyle as a recipe | Checkstyle, PMD, SpotBugs with findings in results |
| TestNG | none | `testng-engine` on the launcher path |
| Database migrations | none | one documented Flyway / Liquibase shape |

**Tiers.** Core batteries ship with every release and gate; contrib batteries are labelled
best-effort in every doc and template. Android (not AGP parity, by the docs' own statement) and
Grails (pinned to a milestone) are contrib. Scala 3 compiles in mixed modules through Zinc and
is described exactly that way; cross-building and Scala.js/Native are not goals. Nothing is
removed; the promise is sized to what one release cadence can keep.

### 5. The web dashboard is the supervisor's view (P1)

The buyer is the human supervising an agent; the beneficiary of the structured loop is the agent.
The dashboard is where that human sees what the agent ran, why it failed, what changed between
attempts and how long each took.

- Every run shows its trigger (CLI, MCP, web, BSP) and the session that asked, grouped by session.
- A per-iteration view: files changed between consecutive runs, diagnostics that appeared or
  disappeared, tests that flipped, wall time per attempt.
- An MCP-triggered run returns its dashboard URL; the project page follows the newest job.

Two cautions travel with this epic: demos are not retention, and a web front end is a second
dependency treadmill on a network surface that is on by default. The same-facts rule with
`jk-results.md` stays strict; loopback plus bearer token stays the posture.

### 6. Performance evidence a reader can check (P1)

- The wall harness runs Gradle (configuration cache and build cache on) and Maven beside jk on a
  fixed public Spring Boot project for clean, warm rebuild, no-op and one-file edit, and banks
  the numbers.
- Total memory is engine plus peak worker set, published beside the Gradle daemon; a worker heap
  ceiling if the measurement says workers run away on large hosts.
- README and why.md speed and memory sentences cite the table, and a guard fails a claim that does
  not.

## Cross-cutting

- **Compatibility policy.** What a minor release may change in `jk.toml`, `jk-lock.toml`, the
  results shape, MCP tools and the plugin SPI, with a deprecation window:
  [Compatibility](compatibility.md).
- **Ordering rule on the board.** `ka next` sorts by priority then id. Everything under
  `inner-loop-1.0` at P0 is "start now"; P1 is "next"; a P2 waits for the P1s of its own epic.
  Outer-loop epics of the same age sit at P2.

## What "done" means for 1.0

- The turns-to-green table names a commit, three tools and two agents, and jk wins.
- The fifty-repo Maven corpus pass rate is published and every Tier-3 reason has a ticket or a
  documented non-goal.
- A fresh IntelliJ with the Marketplace plugin opens a `jk.toml` workspace with no generated file.
- The batteries table marks every first-party plugin core or contrib, and the SDK is on Central.
- The wall table has Gradle and Maven columns and a peak-RSS column.

## Related

- [Why JumpKick](../user/why.md) — the product bet this plan serves
- [Migration](../user/migration.md) — where import stands today
- [IDE and BSP](../user/ide.md) — the IDE surface this plan replaces
- [Plugins](../user/plugins.md) — the batteries and their tiers
- [Self-host](self-host.md) — the gate every child must pass
