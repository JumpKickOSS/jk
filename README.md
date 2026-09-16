<img width="1760" height="576" alt="jumpkick-banner" src="docs/assets/jumpkick-banner.png" />

# JumpKick — the JVM build tool coding agents can drive

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![GraalVM](https://img.shields.io/badge/native--image-GraalVM%2025-yellow.svg)](https://www.graalvm.org/)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](docs/user/README.md)

**JumpKick** (CLI: **`jk`**) shortens the edit → build → diagnose → fix loop for **AI coding
agents** and for the humans who supervise them. Java, Kotlin, Groovy, and Scala. One TOML
manifest. A real lockfile. Structured results agents can read without scraping a TTY. A warm
engine that stays small. Maven Central — not a new package universe.

Wall-clock parity with a tuned Gradle 9.x build is table stakes. The conversion claim is
**fewer failed cycles and less agent thrash** — so Grok, Claude, Codex, and friends finish
green in fewer turns.

> Import your Maven or Gradle project when you are ready. Keep shipping with `jk mvn` /
> `jk gradle` until the JumpKick path owns the loop.

```bash
jk new my-app && cd my-app
jk add jackson3-databind
jk build
# agents: read target/jk-results.md  (or MCP jk_results) — do not scrape the TTY
```

```toml
# jk.toml — the whole build, no scripting language required
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
jackson3-databind = "3.0.0"    # exact pin; ^ ~ ranges and "latest" are opt-in

[platform-dependencies]   # BOMs — enforced platforms (Maven depMgmt contract)
spring-boot-dependencies = "4.1.0"
```

That is it. No `build.gradle.kts` that is itself a software project. No 200-line POM.
`jk add` / `jk remove` edit this file for you — and so can MCP.

---

## The loop (the product)

```text
  intent ──► mutate ──► build/test ──► observe ──► repair ──► repeat
     │          │            │             │          │
  jk manual  TOML +       cache +       jk-results  structured
  / MCP      jk add       right rung    + diagnostics edits +
  templates  format       lockfile law  (not log scrape)  format
```

| Step | What JumpKick optimizes |
|------|-------------------------|
| **Intent** | `jk manual` / MCP `jk_manual` — the system prompt for a tool models were not trained on |
| **Mutate** | Declarative `jk.toml`; surgical `jk add` / `remove`; MCP preview-before-apply |
| **Execute** | Lockfile is law; action cache + CAS; slim resident engine (~256 MiB) |
| **Test rungs** | Default `jk test` is unit (inner loop). `--guard` is the named share-the-commit bar. `--all` is nightly, not a habit. |
| **Observe** | `target/jk-results.md`, MCP diagnostics, JSONL — same facts as the human CLI |
| **Repair** | Readable PubGrub conflicts, `jk why` / `jk explain`, format after edits |

Full product bet and feature ranking: **[Why JumpKick](docs/user/why.md)**.  
Agent playbook: **[Agents](docs/user/agents.md)** · **[MCP](docs/user/mcp.md)**.

### Thirty-second proof

```bash
jk new payments-api && cd payments-api
jk add org.springframework.boot:spring-boot-starter-web
jk test
# open target/jk-results.md  — token-cheap, whole-invocation report
jk engine status   # MCP URL + token when the engine HTTP listener is up
```

Humans get a terse visual CLI with honest ETA. Agents get files and tools. **One build model,
three skins** (TTY / browser / MCP) — never scrape wedges.

---

## Why leave Maven or Gradle

| You want… | JumpKick gives you… |
|---|---|
| **An agent-closed loop** | `jk-results.md`, MCP tools, `jk manual` — diagnose without log archaeology |
| **Named test rungs** | Cheap unit inner loop; `--guard` before share; e2e / `--all` on purpose — not Surefire folklore |
| **Ergonomics of Cargo / uv** | `jk init` `add` `lock` `build` `test` `tree` `why` — native binary, sub-50 ms cold start |
| **Data, not a second app** | TOML manifest; plugins extend a finite model; no Kotlin/Groovy DSL as the build |
| **Reproducible by default** | `jk-lock.toml` is law; `jk build` does not re-resolve |
| **Correct resolution you can read** | PubGrub; highest-wins without a BOM; enforced platform when a BOM is present |
| **Warm speed without a fat daemon** | Content-addressed action cache; engine hard-capped (~256 MiB; 512 MiB when `CI=1`) |
| **Always current by design** | Scaffolds and `jk add` pin today's stable; `jk update` bumps the pins and relocks |
| **Maven Central, not a new ecosystem** | Same coordinates, scopes, BOMs; `~/.m2`-friendly cache |
| **Adoption without a rewrite** | `jk mvn` / `jk gradle` run your *real* build; `import` / `export` when ready |
| **Batteries included** | JDK + shell activate, format, audit/SBOM, OCI images, `jkx`, web UI, git deps |

**Coming from Maven:** Cargo-shaped UX on Central — same declarative philosophy, modern
surface, real lockfile, agent-readable outcomes.

**Coming from Gradle:** keep warm/incremental ambition without “your build is a second
program.” Agents should not write Kotlin DSL to add Jackson.

**Speed (honest):** competitive with modern Gradle on warm builds; the jk / Gradle / Maven
table — walls and peak memory on one public Spring Boot project — is in
[docs/user/performance.md](docs/user/performance.md). Lead with *repeated* local and agent cycles
(RSS + cache + structured retries), not a one-shot CI bake-off.

### Stay on the newest versions

JumpKick is **biased toward the latest stable** of libraries, language features, and
tooling. `jk` itself **requires JDK 25+** (it will install one if needed).

| You write | What happens |
|-----------|----------------|
| `java = 25` (default) | Language/bytecode 25 on the host LTS |
| `java = 21` or `17` | Still use JDK 25; emit older bytecode (`--release`) — **no** old JDK download |
| `java = 26` (above LTS) | May provision a 26 toolchain |
| `jdk = "…"` | Only if you **must** pin a specific install (rare) |

Do **not** habitually set `jdk = 17` / `jdk = 21` — that forces obsolete runtime installs.
Prefer `java = N` for language level.

```bash
jk outdated     # Current / Compatible / Latest, read-only
jk update       # bump the pins in jk.toml (same major; --major to cross), relock
jk build        # still fully reproducible from that lock
```

---

## Feature set (by what converts)

### Lead — agent loop + declarative core

- **`target/jk-results.md`** · MCP `jk_results` / `jk_diagnostics` · `jk manual`
- **Named test rungs** — `jk test` (unit) · `--guard` (share the commit) · `--all` (nightly)
- **`jk.toml`** + `jk add` / `remove` · MCP preview/apply for deps and manifest keys
- Canonical **`jk-lock.toml`** (commit it); PubGrub with **English conflict diagnostics**
- `jk why` · `jk explain` · `jk tree` · `jk outdated` / `jk update`

### Prove in ten minutes — cycle-time physics

- Content-addressed store + **action cache** (restore, don't recompute)
- Slim resident engine + native CLI; accurate **ETA** / progress as first-class facts
- **`jk format`** after agent edits; **`jk new -t`** templates (Giter8 + local `.g8`)
- Java + Kotlin (K2, KSP) + Groovy + Scala 3; workspaces with **one root lockfile**

### Switch without a rewrite — adoption

- `jk import` Maven (primary) / Gradle (best-effort) · `jk export` · IDE files
- `jk mvn …` / `jk gradle …` — real tools, wrapper-aware
- **`~/.m2`-friendly** cache · Central / Google / JumpKick remotes
- **JDK install / pin / discover** + `jk activate` shell hooks (SDKMAN/jenv-shaped)
- Ephemeral tools: **`jkx`** / `jk tool run` (uvx for the JVM; JBang-compatible scripts)

### Batteries — retain power users

- Web dashboard (same facts as MCP/CLI)
- `jk audit` · `jk deny` · Sigstore / SLSA · CycloneDX/SPDX SBOM · `jk verify`
- `jk image` (Jib-core) · `jk native` (GraalVM) · Spring Boot / Quarkus / Micronaut / protobuf; Android and Grails as contrib batteries ([tiers](docs/user/plugins.md#batteries-and-their-tiers))
- Git and path dependencies (SHA-pinned in the lock)

More detail: **[User docs](docs/user/README.md)** · **[Manual](docs/user/manual.md)** ·
**[Architecture](docs/contributors/architecture.md)**

---

## Concepts if you're coming from Maven or Gradle

These ideas are normal in Cargo/uv/Bazel. On the JVM they often feel new. They're why
JumpKick can be both **fast** and **trustworthy** — for humans *and* agents.

### Lockfile is law

Declaring `jackson-databind:2.18.2` in Maven or Gradle does **not** freeze your build.
Transitives can publish overnight; the next CI run can pick different jars without you
changing a line. Maven has no lockfile. Gradle verification is optional homework.

JumpKick writes every resolved version **and checksum** to `jk-lock.toml` and treats it as law:

```bash
jk lock          # resolve → write jk-lock.toml (commit this)
jk build         # uses the lock; does not re-resolve
jk outdated      # read-only: which deps have newer versions than the lock
jk update        # bump declared pins to the newest stable on the same major, relock
jk sync --offline-prepare   # download everything for offline/CI
```

### PubGrub: resolution you can understand

Maven's **nearest-wins** silently picks different versions depending on tree shape.
Gradle often dumps a huge tree when things conflict. JumpKick uses **PubGrub** (same family
as Dart's `pub` and `uv`): highest-version-wins when no platform BOM is present; **enforced
platform** pins under a BOM; failures explain *why* in prose.

```bash
jk why com.google.guava:guava
jk tree -t
```

### Action cache + CAS: skip work you can prove is done

Every compile/test/package step is keyed by a hash of its **inputs**. Outputs live in a
**content-addressed store**. Unchanged inputs restore; one file change re-runs only what
must. `jk explain` forecasts the work before you spend the time.

### A small engine, not a multi-gigabyte daemon

Build work runs in a **resident engine** (plain JVM, default **256 MiB** heap) so concurrent
`jk` commands share one memory plan. Compilers and tests are **forked workers**. The CLI is
a **native** `jk` binary — TUI, shell, JDK prompts — with a cold start measured in
milliseconds. The same engine hosts the **web UI** and **MCP** server.

```bash
jk engine status
jk engine stop
```

→ [Architecture: client + engine](docs/contributors/architecture.md)

---

## Examples

### Day one

```bash
jk new payments-api && cd payments-api

jk add org.springframework.boot:spring-boot-starter-web:3.4.0
jk add --test org.springframework.boot:spring-boot-starter-test:3.4.0

jk build
jk test
jk run
```

Or declare a BOM and versionless deps:

```toml
group = "com.acme"
name = "payments-api"
version = "0.1.0"
java = 25

[platform-dependencies]
boot = "org.springframework.boot:spring-boot-dependencies:3.4.0"

[dependencies]
web = "org.springframework.boot:spring-boot-starter-web"     # versionless: the BOM manages it
```

### Workspace (monorepo)

```toml
# root jk.toml
[workspace]
modules = ["libs/*", "services/*"]

[workspace.dependencies]
jackson-databind = "com.fasterxml.jackson.core:jackson-databind:2.18.2"
```

```toml
# services/api/jk.toml
name = "api"

[dependencies]
jackson-databind.workspace = true
core.workspace = true
```

```bash
jk new libs/core
jk build          # one lock at the root; whole workspace
```

### Agent triage (no TTY scraping)

```bash
jk test
jk results                    # same markdown as target/jk-results.md
jk results --details          # details.jsonl when you need the raw event stream
# or: MCP jk_bind → jk_diagnostics → jk_run wait=true
```

### Keep shipping with Maven while you try JumpKick

```bash
jk mvn package              # real Maven, wrapper-aware
jk import pom.xml           # generate jk.toml + fidelity report
jk export maven             # round-trip POM for Central
```

### Tools, JDK, ship

```bash
jkx com.diffplug.spotless:spotless-cli:2.45.0 -- check
jk jdk install temurin-25 && jk jdk pin temurin-25
eval "$("$HOME/.jk/bin/jk" activate bash)"

jk publish --sign --sigstore --slsa --sbom
jk audit
jk verify
```

---

## Quick start (install)

From the hosted release:

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
jk --help
```

Or from this repo, with that release building it:

```bash
jk build --skip-tests && jk install --skip-tests
```

Developer setup: [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
jk new hello && cd hello
jk build
```

---

## How it compares (at a glance)

| | **JumpKick** (`jk`) | **Maven** | **Gradle** | **Cargo** / **uv** |
|---|---|---|---|---|
| Config | TOML data | XML | Kotlin/Groovy DSL | TOML |
| Lockfile | Required | None | Optional verification | Required |
| Agent I/O | Results + MCP first-class | Log scrape | Log scrape | Varies |
| Conflict policy | Highest-wins + prose | Nearest-wins | Highest-wins + trees | Highest-wins + prose |
| Default speed model | Action cache + CAS | Always recompute phases | Optional build cache | Incremental / cache |
| CLI ergonomics | `add` / `lock` / `why` | Plugins + XML edits | Plugins + DSL edits | Native verbs |
| Migration | `jk mvn` / `import` | — | — | — |

JumpKick is intentionally **Cargo + uv shaped**, on **Maven Central**, with **agent-closed
loops** and Gradle-grade multi-module power — without making your build a second programming
language.

---

## Documentation

Product docs (will be published at [jumpkick.build/documentation](https://jumpkick.build/documentation)):

| Doc | For |
|---|---|
| [**Why JumpKick**](docs/user/why.md) | Product bet, feature ranking, agentic north star |
| [**User documentation**](docs/user/README.md) | People and coding agents *using* JumpKick |
| [**Manual**](docs/user/manual.md) | `jk manual` playbook + website map into every topic |
| [**Agents**](docs/user/agents.md) / [**MCP**](docs/user/mcp.md) | How agents should talk to `jk` |
| [**Security**](docs/user/security.md) | Report a vulnerability; trust boundaries |
| [**Contributor documentation**](docs/contributors/README.md) | People changing JumpKick |
| [**Contributing**](CONTRIBUTING.md) | Building this repository |

---

## Status

**Alpha (pre-1.0).** Core resolve/build/test is real and dogfooded: jk builds, tests,
guards, installs and releases jk (`jk build` writes the ship layout under `target/dist/`).
APIs and lock schema may still change until 1.0. Protocol/schema version numbers stay
at **1** until 1.0.

What 1.0 means and in what order it lands: **[the 1.0 plan](docs/contributors/plan-1.0.md)** —
the developer's inner loop first (Maven projects as they are, a measured agent loop, IntelliJ
that just works, daily-loop batteries, the dashboard as the supervisor's view, checkable
performance numbers).

Supported project JDKs: **17+** (no Java 8 or 11).

## License

[Apache 2.0](LICENSE).
