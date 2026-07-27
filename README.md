<img width="1024" height="274" alt="JumpKick" src="https://github.com/user-attachments/assets/5cf7e056-1eed-43f7-9c6f-67bbb8d1e807" />

# JumpKick — the modern build tool for the JVM

[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/projects/jdk/25/)
[![GraalVM](https://img.shields.io/badge/native--image-GraalVM%2025-yellow.svg)](https://www.graalvm.org/)
[![Status](https://img.shields.io/badge/status-alpha-red.svg)](docs/architecture.md)

**JumpKick** (CLI: **`jk`**) is Cargo for Java and Kotlin. One native binary. One TOML file.
A real lockfile. Conflicts you can read. Builds that skip work they can prove is already done.

> The fastest way to run your existing Maven or Gradle build — and a better tool
> you can switch to when you're ready.

```bash
jk init my-app && cd my-app
jk add com.fasterxml.jackson.core:jackson-databind:2.18.2
jk build && jk test
```

```toml
# jk.toml — the whole build, no scripting language required
[project]
group   = "com.example"
name    = "my-app"
version = "0.1.0"
jdk     = 25

[dependencies]
jackson3-databind = "3.0.0"   # means ^3.0.0 (caret by default)

[test-dependencies]
junit-jupiter = "6.0.0"

[platform-dependencies]       # BOMs — enforced platforms (Maven depMgmt contract)
spring-boot-dependencies = "4.1.0"
```

That's it. No `build.gradle.kts` that is itself a software project. No 200-line POM.
`jk add` / `jk remove` edit this file for you.

---

## The pitch

| You want… | JumpKick gives you… |
|---|---|
| **Ergonomics of Cargo / uv** | `jk init` `add` `lock` `build` `test` `tree` `why` — native binary, sub-50 ms cold start |
| **Maven Central, not a new ecosystem** | Same coordinates, scopes, BOMs, GPG/Sigstore, `~/.m2`-friendly cache |
| **Reproducible builds by default** | `jk.lock` is law; CI doesn't re-resolve unless you say so |
| **Correct resolution** | PubGrub; highest-wins without a platform BOM; enforced platform when a BOM is present |
| **Speed without a bloated daemon** | Content-addressed action cache; slim engine hard-capped at ~256 MiB |
| **Adoption without a rewrite** | `jk mvn` / `jk gradle` run your *real* build; `import` / `export` when ready |
| **Supply chain built in** | `audit` (OSV), `deny`, signing, Sigstore, SLSA, CycloneDX/SPDX SBOM |
| **JDK management included** | `jk jdk install/pin` + shell activation — SDKMAN/jenv/toolchains in one place |

Coming from **Maven**: think “Cargo-shaped UX on top of Central.”  
Coming from **Gradle**: think “declarative TOML + real lockfile, without the configuration graph.”

---

## Feature set

### Dependencies & lockfile
- PubGrub solver with **English conflict diagnostics**
- **Highest-version-wins** for bare edges when no platform BOM; **enforced platform** when one is present (opt-in **floor** via `[resolve] platform = "floor"`)
- Canonical **`jk.lock`** (commit it); `jk build` never re-resolves
- Caret / tilde / exact / range selectors; platform BOMs as dependencyManagement pins
- **`jk export bom`** — freeze a lock scope as a publishable Maven BOM POM
- Separate **main / test / processor** resolution so annotation processors don't force main versions
- Git and path dependencies, SHA-pinned in the lock
- Built-in remotes: JumpKick (exclusive for `cc`/`build.jumpkick`) · Central · Google
- `jk tree` · `jk why` · offline-friendly after `jk sync`

### Build & monorepos
- Java + Kotlin (K2, KSP) + Groovy, workspaces with **one root lockfile**
- Content-addressed store + **action cache** (restore, don't recompute)
- `jk explain` — forecast what will run before it does
- Variants, profiles, features (product / how / optional deps — deliberately separate)
- First-party: Spring Boot, **Quarkus**, **Grails**, Android, protobuf, R8 shrink, tests, format, native-image, OCI images
- `jk new --template` Giter8 short names (`java-cli`, `quarkus`) + local `.g8` paths

### Toolchain & tools
- **JDK install / pin / discover** (Temurin, GraalVM, and neighbors: IntelliJ, SDKMAN, mise, …)
- Directory-aware shell hooks: `eval "$(jk activate bash)"`
- Ephemeral tools: `jk tool run` / `jkx` (uvx for the JVM); JBang-compatible scripts

### Ship & supply chain
- `jk publish` with GPG, Sigstore, SLSA, dual SBOMs
- `jk image` (Jib-core, daemonless) · `jk native` (GraalVM)
- `jk audit` · `jk deny` (source-host denylist); lock rows pin a source repo (namespace binding planned)
- `jk verify` — rebuild in a scratch dir and diff artifact hashes

### Migration
- `jk mvn …` / `jk gradle …` — real Maven/Gradle, wrapper-aware
- `jk import pom.xml` / `build.gradle.kts` · `jk export maven` / IDE files

More detail: **[User guide](docs/guide.md)** · **[Architecture](docs/architecture.md)** · **[Plugins](docs/plugins.md)**

---

## Concepts if you're coming from Maven or Gradle

These ideas are normal in Cargo/uv/Bazel. On the JVM they often feel new. They're the
reason JumpKick can be both **fast** and **trustworthy**.

### What is a lockfile (and why you want one)

Declaring `jackson-databind:2.18.2` in Maven or Gradle does **not** freeze your build.
Transitives can publish overnight; the next CI run can pick different jars without you
changing a line. Maven has no lockfile. Gradle verification is optional homework.

JumpKick writes every resolved version **and checksum** to `jk.lock` and treats it as law:

```bash
jk lock          # resolve → write jk.lock (commit this)
jk build         # uses the lock; does not re-resolve
jk outdated      # read-only: which deps have newer versions than the lock
jk update        # re-resolve on purpose, within your declared ranges
jk sync --offline-prepare   # download everything for offline/CI
```

If it builds on your laptop with that lock, it builds the same on CI — and can build
entirely offline. That is not a nice-to-have; it is how modern package managers work.

### PubGrub: resolution you can understand

Maven's **nearest-wins** silently picks different versions depending on tree shape.
Gradle often dumps a huge tree when things conflict. JumpKick uses **PubGrub** (same family
as Dart's `pub` and `uv`): highest-version-wins when no platform BOM is present; **enforced
platform** pins under a BOM (Maven depMgmt contract); and failures explain *why* in prose —
which constraint blocked which package — so you can fix the declaration instead of guessing.

```bash
jk why com.google.guava:guava
jk tree
```

### Action cache + CAS: skip work you can prove is done

Every compile/test/package step is keyed by a hash of its **inputs** (sources, classpath,
flags, toolchain, plugin code, …). Outputs live in a **content-addressed store** (CAS).

| Situation | What happens |
|---|---|
| Inputs unchanged | Outputs **restored** from cache — not recomputed |
| One file changed | Only affected steps re-run |
| Same output produced twice | Stored once, shared |

Gradle's build cache is opt-in and easy to mistrust. JumpKick makes “cache by input hash”
the default execution model. `jk explain` shows what would run before you spend the time.

### A small engine, not a multi-gigabyte daemon

Build work runs in a **resident engine** (plain JVM, default **256 MiB** heap) so concurrent
`jk build`s share one memory plan. Compilers and tests are **forked workers** that exit with
the build. The CLI you type is still a **native** `jk` binary — TUI, shell, JDK prompts —
with a cold start measured in milliseconds.

```bash
jk engine status
jk engine stop
```

→ [Architecture: client + engine](docs/architecture.md)

---

## Examples

### Day one

```bash
jk init payments-api && cd payments-api

jk add org.springframework.boot:spring-boot-starter-web:3.4.0
jk add --test org.springframework.boot:spring-boot-starter-test:3.4.0

jk build
jk test
jk run
```

Or declare a BOM and versionless deps (managed by the platform):

```toml
[project]
group = "com.acme"
name = "payments-api"
version = "0.1.0"
jdk = 21

[platform-dependencies]
boot = { group = "org.springframework.boot", name = "spring-boot-dependencies", version = "3.4.0" }

[dependencies]
# version supplied by the BOM
web = { group = "org.springframework.boot", name = "spring-boot-starter-web" }
```

```bash
jk lock && jk build
```

### Workspace (monorepo)

```toml
# root jk.toml
[workspace]
modules = ["libs/*", "services/*"]

[workspace.dependencies]
jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.18.2" }
```

```toml
# services/api/jk.toml
[project]
name = "api"

[dependencies]
jackson-databind.workspace = true   # shared external
core.workspace = true               # sibling module
```

```bash
jk new libs/core
jk build          # one lock at the root; whole workspace
```

### Git dependency

```toml
[dependencies]
widgets = { git = "https://github.com/acme/widgets", tag = "v1.4.0" }
```

The lock pins the commit SHA. Force-moved tags fail loudly until `jk update`.

### Keep shipping with Maven while you try JumpKick

```bash
jk mvn package              # real Maven, wrapper-aware
jk import pom.xml           # generate jk.toml + fidelity report
jk export maven             # round-trip POM for Central
```

### Tools and scripts

```bash
jkx com.diffplug.spotless:spotless-cli:2.45.0 -- check   # ephemeral tool
jk tool run script.java                                  # JBang-compatible
jk jdk install temurin-25 && jk jdk pin temurin-25
eval "$(jk activate bash)"                               # JAVA_HOME follows cd
```

### Publish with supply chain

```bash
jk publish --sign --sigstore --slsa --sbom
jk audit
jk verify    # clean rebuild; compare hashes
```

---

## Quick start (install)

From a release binary or installer (see releases for your platform), or from this repo:

```bash
./gradlew dist
./install.sh build/dist/jk
jk --help
```

Developer setup: [CONTRIBUTING.md](CONTRIBUTING.md).

```bash
jk init hello && cd hello
jk build
```

---

## How it compares (at a glance)

| | **JumpKick** (`jk`) | **Maven** | **Gradle** | **Cargo** / **uv** |
|---|---|---|---|---|
| Config | TOML data | XML | Kotlin/Groovy DSL | TOML |
| Lockfile | Required | None | Optional verification | Required |
| Conflict policy | Highest-wins + prose | Nearest-wins | Highest-wins + trees | Highest-wins + prose |
| Default speed model | Action cache + CAS | Always recompute phases | Optional build cache | Incremental / cache |
| CLI ergonomics | `add` / `lock` / `why` | Plugins + XML edits | Plugins + DSL edits | Native verbs |
| Migration | `jk mvn` / `import` | — | — | — |

JumpKick is intentionally **Cargo + uv shaped**, on **Maven Central**, with **Gradle-grade**
multi-module and variant power — without making your build a second programming language.

---

## Documentation

| Doc | For |
|---|---|
| [**User guide**](docs/guide.md) | Manifest, lockfile, workspaces, JDK, migration, full command surface |
| [**Architecture**](docs/architecture.md) | Client/engine split, modules, resolver, caching |
| [**Plugins**](docs/plugins.md) | Writing build plugins (`jk-plugin.toml` + optional worker) |
| [**Contributing**](CONTRIBUTING.md) | Building this repository |

---

## Status

**Alpha (pre-1.0).** Core resolve/build/test is real and dogfooded; the shippable
layout is still assembled with Gradle (`./gradlew dist`) while self-hosting finishes.
APIs and lock schema may still change until 1.0.

Supported project JDKs: **17+** (no Java 8 or 11).

## License

[Apache 2.0](LICENSE).
