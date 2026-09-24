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

## Where things stand

| Area | Now |
|---|---|
| Agent loop | One scenario, one coding-agent driver: jk is behind on turns and tokens. The scripted driver is a plumbing proof. A full matrix is pending. |
| Maven projects | Effective-POM import, profiles, plugin mapping, `jk mvn` results, and coexistence have landed. Of the top-20 corpus, 13 build and 2 pass their tests (run 16). |
| IDE | The IntelliJ external system (live model, gutter, debug) and Scala over BSP have landed. Marketplace listings have not. |
| Batteries | Coverage beyond XML, the generators, lint, TestNG, the database-migration recipe, and a publishable SDK have landed. The SDK is not on Maven Central. |
| Performance | The wall table has Gradle and Maven columns and peak RSS. Workers have no heap ceiling that keeps the tree small. No guard checks a speed or memory sentence against the table. |
| Dashboard | The supervisor's view is live: run origin, and the per-attempt delta. |

## The six epics, in priority order

### 1. Existing Maven projects work under jk (P0)

The uv lesson: get into existing repos first, convert the manifest later.

**Landed**

- **Effective POM.** Import consumes Maven's own effective model (parents flattened, dependency
  management merged, properties interpolated). [Migration](../user/migration.md).
- **Structured results for `jk mvn`.** `JkEventSpy` (`clients/maven-spy`) writes
  `target/jk-results.md` from Maven's events in a repo that has no `jk.toml`.
- **Coexistence.** `jk build`, `jk test` and `jk explain` run on an unmodified `pom.xml`. The
  project model is the effective POM, rendered as a shadow manifest under `target/jk/shadow/`.
  Nothing is written into the repository.
- **Profiles.** Each activation kind has a landing, and `jk import pom.xml -P` activates a
  profile by name.
  [Migration](../user/migration.md#where-maven-profiles-land).
- **Plugin mapping.** The graded table in
  [Migration](../user/migration.md#which-maven-plugins-import-and-how-well): compiler, jar,
  Surefire, Boot, shade, Checkstyle, PMD, SpotBugs, OpenAPI, jOOQ, Avro, ANTLR, JAXB, and the
  other rows on that page.
- **Gradle import.** A forked Gradle runs `jk-import-model.init.gradle` and writes the evaluated
  project model (`GradleModelQuery`). Multi-module builds import.
  [Migration](../user/migration.md#where-gradle-import-stands-on-real-repositories).

**Open**

- Rows still graded `manual` or `none`: Failsafe's `*IT.java` layout, an arbitrary exec, the
  release plugin, `war`, Tycho, OSGi.
- **The fidelity corpus** is the twenty most-starred Maven-built Java repositories with a root
  POM and Java 17 or newer
  ([jk-examples `corpus/maven-top20`](https://github.com/JumpKickOSS/jk-examples/tree/main/corpus/maven-top20)).
  Run 16 (main `03de088c6`, 2026-09-18, jk 0.13.7): import-clean 11/20, lock 18/20, build 13/20,
  tests pass 2/20. The same counts are in
  [Migration](../user/migration.md#where-import-stands-on-real-repositories). Raising them is
  the open work.

### 2. The agent loop is measured (P0)

The metric in [Why JumpKick](../user/why.md) is median agent turns, tokens and wall time to
green on a fixed set of broken-build scenarios. The comparator is **wrapped** Maven and Gradle: a
results file and MCP tools over their existing output, built deliberately well, because that is
what an incumbent would ship. If jk does not beat the wrapper by a wide margin, the wrapper is the
product and epic 1 absorbs it.

**Landed**

- **Scenario corpus:** 19 repositories in `bench/agent-loop/scenarios.toml`, all Gradle
  single-module Java; 13 also ship a `pom.xml`.
  [bench/agent-loop](../../bench/agent-loop/README.md).
- **Wrappers:** `wrappers/mvn-results`, `wrappers/gradle-results`, and `wrappers/results-mcp`.
- **Harness,** three drivers: `scripted` (a deterministic oracle; a plumbing proof, not a
  result), `claude-code`, and `api`.
- **Results file:** a compiler diagnostic carries a repair hint (`JkResultsHints`); the header
  reports `tokens ≈ N` (`JkResultsTokens`).

**Open**

- Publishing the numbers. The only LLM comparison banked is one `claude-code` scenario
  ([`results/2026-09-16/TABLE.md`](../../bench/agent-loop/results/2026-09-16/TABLE.md)): jk
  9 turns / 45,982 tokens, Maven 6 / 23,623, Gradle 6 / 23,807. The `api` driver has a single
  jk run and no Maven or Gradle row. A full matrix is pending.

The published table is the two LLM drivers (`claude-code`, `api`) over the scenario matrix,
three tools, against a named commit, refreshed each release. `scripted` stays the plumbing
proof.

### 3. IntelliJ just works (P0)

**Landed**

- **Live project model.** `clients/intellij` is an external system over the engine ide-model.
  Opening a `jk.toml` workspace writes no `.iml`. [IDE](../user/ide.md).
- **Gutter output.** IntelliJ and JDT compile into `target/jdt/classes/{main,test}`, never
  `target/classes`.
- **Debug.** `jk run --debug-jvm` and `jk test --debug-jvm` publish a JDWP address and the
  plugin attaches. `--debug` is the unique prefix of `--debug-jvm`.
- **BSP** advertises Scala (a `scala` build target and `buildTarget/scalacOptions`) so Metals
  imports a mixed Java/Scala module.

**Open**

- Marketplace listings for IntelliJ and VS Code. Both plugins are packaged from this
  repository (`scripts/package-intellij.sh`, `scripts/package-vscode.sh`) and installed from
  disk.

### 4. Daily-loop batteries (P1)

Batteries-included is the strategy. The rule for picking the next battery is **how often a
Spring or Kotlin service touches the step in its daily loop**, not how impressive it is on a
feature matrix.

| Battery | Status today | Target |
|---|---|---|
| Plugin SDK on Maven Central | publishable with `jk publish --central` from `shared/host` and `shared/plugin-sdk`; not on Central. First-party plugins keep the workspace edge. The sample plugin compiles against a `jk publish`ed SDK | published with every release |
| Coverage | JaCoCo XML + HTML per module, Coverage block and Δ in `jk-results.md`, guard floor on `coverage.line` / `coverage.branch` | done |
| Sources and javadoc jars | both from `jk package`; javadoc for Java, Dokka (`[dokka]`) for a Kotlin or mixed module | done |
| Central Portal publish | `jk publish --central`: signed bundle, Portal upload, status poll, Publish block in results | done |
| Code generation | `[generate]`, protobuf, annotation processors, KSP, OpenAPI, jOOQ, Avro, ANTLR, JAXB | done |
| Lint as a cached step | Checkstyle, PMD, SpotBugs and detekt as cached steps, findings in results; Error Prone and NullAway via `[javac]` | done |
| TestNG | `testng-engine` on the JUnit Platform launcher | done |
| Database migrations | Flyway and Liquibase as a `jk tool` recipe ([Database](../user/database.md)), not a build step | done |

**Tiers.** Core batteries ship with every release and gate; contrib batteries are labelled
best-effort in every doc and template. Android (not AGP parity, by the docs' own statement) and
Grails (pinned to a milestone) are contrib. Scala 3 compiles in mixed modules through Zinc and
is described exactly that way; cross-building and Scala.js/Native are not goals. Nothing is
removed; the promise is sized to what one release cadence can keep.

### 5. The web dashboard is the supervisor's view (P1)

The buyer is the human supervising an agent; the beneficiary of the structured loop is the agent.
The dashboard is where that human sees what the agent ran, why it failed, what changed between
attempts and how long each took.

**Landed.** [Web](../user/web.md).

- Every run shows its trigger (`cli`, `mcp`, `web`, `bsp`) and the session that asked, grouped
  by session.
- The iteration strip is the per-attempt delta: files changed, diagnostics that appeared or
  disappeared, tests that flipped, wall time against the previous run from the same origin.
  The same computation is `## Since the previous run` in `jk-results.md` and MCP `jk_results`
  `delta`.
- An MCP-triggered run returns its dashboard URL; the project page follows the newest job.

Two cautions travel with this epic: demos are not retention, and a web front end is a second
dependency treadmill on a network surface that is on by default. The same-facts rule with
`jk-results.md` stays strict; loopback plus bearer token stays the posture.

### 6. Performance evidence a reader can check (P1)

**Landed**

- The wall harness runs Gradle (configuration cache and build cache on) and Maven beside jk on
  spring-petclinic for clean, warm rebuild, no-op, one-file edit, and a test run, and banks
  wall-clock and peak RSS of the whole process tree. The table is in
  [Performance](../user/performance.md) and [Why JumpKick](../user/why.md). README and why.md
  cite it.

**Open**

- A worker heap ceiling. Compiler workers are sized per module up to what the host can give one
  worker ([Engine](../user/engine.md#compiler-worker-heap)); test JVMs are sized from the host.
  The banked test run peaks at 7,479 MiB. Nothing caps that tree.
- A guard that fails a speed or memory sentence the table does not support.

## Cross-cutting

- **Compatibility policy.** Before 1.0 there is no compatibility contract: version numbers stay
  at 1 and a format changes in place. From 1.0, what a minor may change in `jk.toml`,
  `jk-lock.toml`, the results shape, MCP tools and the plugin SPI, with a deprecation window:
  [Compatibility](compatibility.md).
- **Ordering rule on the board.** `ka next` sorts by priority then id. Everything under
  `inner-loop-1.0` at P0 is "start now"; P1 is "next"; a P2 waits for the P1s of its own epic.
  Outer-loop epics of the same age sit at P2.

## What "done" means for 1.0

- The turns-to-green table names a commit and three tools, and jk wins. The harness drivers are
  `scripted`, `claude-code` and `api`; the published comparison is the two LLM drivers.
- The top-20 Maven corpus (a root POM, Java 17 or newer) has its pass rate published in
  Migration, and every Tier-3 reason is fixed or written down as a non-goal.
- A fresh IntelliJ with the Marketplace plugin opens a `jk.toml` workspace with no generated file.
- The batteries table marks every first-party plugin core or contrib, and the SDK is on Central.
- The wall table has Gradle and Maven columns and a peak-RSS column.

## Related

- [Why JumpKick](../user/why.md) — the product bet this plan serves
- [Migration](../user/migration.md) — where import stands today
- [IDE and BSP](../user/ide.md) — the IDE surface
- [Plugins](../user/plugins.md) — the batteries and their tiers
- [Self-host](self-host.md) — the gate every child must pass
