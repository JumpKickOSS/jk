<img width="1760" height="576" alt="jumpkick-banner" src="docs/assets/jumpkick-banner.png" />

# JumpKick — the JVM build system built for coding agents

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![GraalVM](https://img.shields.io/badge/native--image-GraalVM%2025-yellow.svg)](https://www.graalvm.org/)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](docs/user/README.md)

**JumpKick** (CLI: **`jk`**) is the JVM build system built for coding agents — and for the
humans supervising them. Java, Kotlin, and Groovy on Maven Central. One TOML manifest, a real
lockfile, one resident engine, and a report an agent reads instead of scraping a terminal.

```bash
curl -fsSL https://jumpkick.build/install.sh | bash     # Windows: irm https://jumpkick.build/install.ps1 | iex

jk new my-app && cd my-app
jk add jackson3-databind
jk test
# agents: read target/jk-results.md, or connect to the MCP server — never scrape the TTY
```

```toml
# jk.toml — the whole build, no scripting language
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
jackson3-databind = "3.0.0"    # an exact pin; jk add writes today's newest stable

[platform-dependencies]
spring-boot-dependencies = "4.1.0"
```

Already on Maven? `cd` into the project and run `jk test` — no `jk.toml` needed.

---

## Why switch

### 1. Agent-first

The edit → build → diagnose → fix loop is the product, and an agent runs it hundreds of times a
day. Every surface is built for that loop:

- **`target/jk-results.md`** — one token-cheap report per invocation: what ran, what failed at
  which `file:line` with a fix hint, what was skipped, and what to run next.
- **MCP built into the engine** — `run` returns a verdict, `diagnostics` the failures, `deps` and
  `manifest` edit the project and relock. Several agents share one engine, each bound to its own
  project.
- **`jk skill`** — the playbook for a tool models were not trained on; jk keeps its section of
  your `AGENTS.md` current.
- **`jk graph`** — a code knowledge graph that answers "where is it, what calls it" within a
  token budget and cites `file:line`, instead of grep-and-read turns.
- **Named test rungs** — `jk test` is the cheap unit suite, `--guard` the bar before sharing,
  `--all` the nightly; the report says what was not run.

On 165 broken-build scenarios, five agents from three vendors reach green on jk in as few or
fewer turns than on Maven or Gradle given the same agent tooling, and four of the five with
fewer input tokens and less wall time. On Gradle-built Kotlin repositories the agents still
spend more turns on jk; that gap is open
([the table](docs/user/why.md#agents-reach-green)).

### 2. Never type `clean` again

An incremental build produces exactly what a build from nothing produces, or it is a bug.

- Every compile, test and package step is keyed by the content of its inputs; outputs live in a
  content-addressed store. Unchanged inputs restore instead of recompute.
- A key names content and a project-relative path, never the checkout: a second worktree,
  another agent, or CI at the same commit restores what the first one built.
- **`jk explain`** forecasts each step's cache hit or miss, and the ETA, before you spend the
  time.
- **The proof runs on every platform:** the [clean-build oracle](bench/oracle/README.md) applies
  every kind of edit — rename, case-only rename, constant change, branch switch, stash, an edit
  landing mid-build, a test reading a file nothing declares — then checks the result byte for
  byte against a from-scratch build and that the next build runs nothing. `jk verify` does the
  same for your artifacts.

### 3. Fast and frugal

<!-- wall-claims:begin -->
On [spring-petclinic](docs/user/performance.md), jk is faster than Gradle on every row: clean build (0.37 s against 2.69 s), warm rebuild (0.09 s against 0.57 s), no-op (0.05 s against 0.53 s), one-file edit (0.13 s against 0.61 s) and test run (8.54 s against 9.97 s). It is faster than Maven on every row: clean build (0.37 s against 3.16 s), warm rebuild (0.09 s against 3.16 s), no-op (0.05 s against 2.37 s), one-file edit (0.13 s against 3.14 s) and test run (8.54 s against 10.10 s).

Between builds jk keeps 863 MiB resident: 171 MiB for the engine, whose heap is capped at 256 MiB and trimmed back after every job, and the rest for warm compilers that exit after 30 idle minutes or as soon as the host runs short of memory. One engine serves every project on the machine. Gradle keeps 1,734 MiB, 2.0× as much: its daemon, which idles for three hours by default, and its workers. Maven keeps nothing resident and starts a fresh JVM for every build instead (2.37 s for a no-op). Peak whole-tree RSS during a run is lower than Gradle's on clean build, warm rebuild, no-op and one-file edit and higher on test run; it is higher than Maven's on every row.
<!-- wall-claims:end -->

Agents in several worktrees share that engine and its action cache. Compilers and test JVMs
are forked workers that lease memory from a single budget, learn the heap they really use, and
shard a suite only as wide as its measured class times make useful
([Engine](docs/user/engine.md)).

### 4. See what your agent is doing

Most developers do not type `jk` at all — their agent does. **`jk web`** opens the supervisor's
view: every run with who asked (`MCP · claude-code 3f9a`, your shell, the IDE), what changed
since the previous attempt, why it failed, and how long it took, following the newest run live.

At the terminal you get the same facts: live progress with an honest ETA, terminal hyperlinks,
and a desktop notification when a long build finishes. **One build model, three views** —
terminal, browser, MCP — never three stories.

### 5. Batteries Included™

No plugin hunt for the things every project needs:

| | Built in |
|---|---|
| **Toolchains** | JDK and Node.js install, pin and discovery; `jk activate` shell hooks; `java = 17` cross-compiles on the host JDK |
| **Code quality** | `jk format` (Java, Kotlin, Groovy, Scala); [null checking](docs/user/build.md#null-checking-nullness) with one `[nullness]` table (JSpecify + NullAway); Checkstyle, PMD, SpotBugs and detekt as cached steps; [guards](docs/user/guards.md) — house rules as data the build enforces |
| **Code generation** | OpenAPI, jOOQ, Avro, ANTLR, JAXB, protobuf, or any `main`, as cached steps |
| **Packaging** | Fat and R8-minified jars, GraalVM native images, daemonless OCI images, AOT cache training |
| **Supply chain** | CycloneDX and SPDX SBOMs, `jk audit`, `jk deny`, GPG, Sigstore and SLSA provenance, `jk verify` |
| **Ship** | `jk release` tags and moves to the next version; `jk publish` to the Central Portal or any Maven repository |
| **Frameworks** | Spring Boot, Quarkus, Micronaut; Android, Grails and Scala 3 as [contrib batteries](docs/user/plugins.md#batteries-and-their-tiers) |
| **Tools** | `jkx` runs any JVM tool or JBang script without installing it; Flyway and Liquibase against a database |

### 6. A modern, declarative core

- **`jk.toml` is data.** Plugins add tables, never a programming language; `jk add` / `jk remove`
  edit it for you, and so can MCP.
- **The lockfile is law.** `jk-lock.toml` records every version and checksum; `jk build` never
  re-resolves a valid lock.
- **PubGrub resolution** with conflicts explained in prose; `jk why` and `jk tree` show the path.
- **Git and path dependencies**, SHA-pinned in the lock. **Workspaces** with one root lockfile.
- **Newest stable by default.** `jk add` and `jk new` write today's version; `jk outdated` and
  `jk update` keep you there.

### 7. Switch without a rewrite

- **`jk mvn`** runs your real Maven build — wrapper-aware — and writes the same
  `jk-results.md`; `jk build` and `jk test` work on an unmodified `pom.xml`.
- **`jk import`** reads the effective POM, profiles and plugins into a `jk.toml` with a fidelity
  report; `jk export` writes a POM back. `jk gradle` and Gradle import cover the other side.
- **A good neighbor:** jk shares `~/.m2`, installs JDKs where IntelliJ already looks, and honours
  your `settings.xml` mirrors and credentials. The IntelliJ plugin and BSP open the project as is.

Full pitch and the feature matrix, losses included: **[Why JumpKick](docs/user/why.md)** ·
**[Comparison](docs/user/comparison.md)**.

---

## At a glance

| | **JumpKick** | **Maven** | **Gradle** |
|---|---|---|---|
| Build file | TOML data | XML | Kotlin / Groovy program |
| Lockfile | Always, with checksums | None | Opt-in verification |
| Agent I/O | Results file, MCP, skill, code graph | Log scrape | Log scrape |
| Work avoidance | Content-addressed action cache, on by default | Recompiles | Build cache, opt-in |
| Between builds | One capped engine for every project | Nothing resident | A daemon per build environment |
| Conflict policy | Highest wins, enforced BOMs, prose diagnostics | Nearest wins | Highest wins, dependency trees |
| Supervision | Web dashboard of every agent run | — | Build scans (hosted) |
| Migration | `jk mvn`, `jk import`, `jk export` | — | — |

---

## Documentation

Product docs (to be published at [jumpkick.build/documentation](https://jumpkick.build/documentation)):

| Doc | For |
|---|---|
| [**Why JumpKick**](docs/user/why.md) | The pitch, with the evidence behind each claim |
| [**Getting started**](docs/user/getting-started.md) | Install, first project, first build |
| [**User documentation**](docs/user/README.md) | People and coding agents *using* JumpKick |
| [**Agents**](docs/user/agents.md) / [**MCP**](docs/user/mcp.md) / [**Skill**](docs/user/skill.md) | How agents talk to `jk` |
| [**Migration**](docs/user/migration.md) | From Maven or Gradle |
| [**Security**](docs/user/security.md) | Report a vulnerability; trust boundaries |
| [**Contributor documentation**](docs/contributors/README.md) · [**Contributing**](CONTRIBUTING.md) | Changing JumpKick itself |

## Status

**Alpha (pre-1.0).** Core resolve, build and test are real and dogfooded: jk builds, tests,
guards, installs and releases jk. APIs and the lock schema may change until 1.0; protocol and
schema versions stay at **1** until then. What 1.0 means: **[the 1.0 plan](docs/contributors/plan-1.0.md)**.

Running `jk` needs JDK 25+, and `jk` installs one if the machine has none. Projects target any
`java =` level from 8 up.

## License

[Apache 2.0](LICENSE).
