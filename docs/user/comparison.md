# JumpKick, Maven, and Gradle

What an evaluator can hold JumpKick against, and the scoreboard for closing the gaps. The
product bet is in [Why JumpKick](why.md). The one measured speed and memory table is in
[Performance](performance.md).

JumpKick is **pre-1.0**. A cell describes current behavior. Where a number is cited, it is the
spring-petclinic run in [Performance](performance.md), not a general claim. A **loss** in the
[scoreboard](#scoreboard) stays a loss until the JumpKick cell is true. A **held** loss is the
product bet or an explicit non-goal, and is not waiting on a fix.

## Where JumpKick wins

These are true of JumpKick by default and not of stock Maven or stock Gradle, in the order of
the pitch in [Why JumpKick](why.md).

1. **An agent gets a file and a tool, not a log.** `target/jk-results.md`, MCP, `jk skill` and
   `jk graph` are first-party. The code graph needs nothing installed beside jk and stays current
   with the build, so an agent can rely on it in every project. `jk mvn` and `jk gradle` run the real tool and, for Maven, fold its
   events into that same report.
2. **The action cache is on, and shared across checkouts.** Keys name content and a
   project-relative output, so a second worktree or agent at one commit restores the first one's
   work. `jk explain` forecasts it.
3. **One capped engine between builds.** One resident engine serves every project on the machine
   ([Engine](engine.md)); the measured numbers are in [Speed and memory](#speed-and-memory).
4. **The human can watch the agent.** The web dashboard shows every run's trigger, session,
   failure and change since the previous attempt, from the same facts as the CLI and MCP.
5. **The daily batteries ship in the binary.** Format, null checking, lint, guards, code generation, audit and
   deny, CycloneDX and SPDX, signing, OCI images, GraalVM native-image, release and `jkx` are
   commands, not plugins a project has to discover.
6. **The manifest is data and the lockfile is law.** `jk.toml` is TOML; plugins add tables, not
   a language. `jk build` does not re-resolve a valid `jk-lock.toml`, and every row carries a
   checksum. Tests have named rungs, and the report says what was not run.
7. **The machine's Maven repository and IntelliJ JDK directory are shared, not duplicated.**
   Fetched jars are written through to `~/.m2/repository`. JDKs JumpKick installs go in
   IntelliJ's shared root (`~/.jdks`, or `~/Library/Java/JavaVirtualMachines` on macOS), after
   probes that cover what Gradle's auto-detection, Maven's `toolchains.xml`, and Mill's Coursier
   JVM cache would find. `java = 17` does not download a JDK 17.

The matrices below keep the ties and the losses. Open losses are the [scoreboard](#scoreboard).

## Sharing the machine

JumpKick keeps its own home (`~/.jk`: client, engine, action cache, artifact store) and treats two
directories it does not own as shared infrastructure.

| | JumpKick | Maven | Gradle |
|---|---|---|---|
| **Third-party jars** | Own store under `~/.jk/store`, **and** `~/.m2/repository` by default. A digest-matching file already in the Maven local repository is copied in instead of downloaded. A fetch is written back through. `[m2] integration = false` turns the sharing off. | `~/.m2/repository` is Maven's local repository. | Dependencies live in the Gradle user home (`~/.gradle/caches`). `mavenLocal()` is a repository a build can add. Gradle does not read or fill `~/.m2` unless the build asks. |
| **Where a JDK JumpKick (or the tool) installs lands** | IntelliJ's shared root: `~/.jdks`, or `~/Library/Java/JavaVirtualMachines` on macOS. Inventory and fingerprints stay in `~/.jk/state`, because the directory is shared. | Maven does not install JDKs. The toolchain plugin points at JDKs listed in `toolchains.xml`. | Detects an IntelliJ-installed JDK, then **installs its own** under the Gradle user home (`<gradle-user-home>/jdks`) when a toolchain resolver is configured. |
| **Looks for a JDK before downloading** | A miss installs into the shared root. Probes: `JAVA_HOME`, JumpKick's own, IntelliJ, Gradle's provisioned JDKs, SDKMAN, JBang, mise, asdf, jenv, Homebrew, Jabba, the Coursier JVM cache, Linux `/usr/lib/jvm`, `/usr/java`, `/usr/lib64/jvm`, `/usr/local/java`, and `/opt/java`, macOS `/Library/Java/JavaVirtualMachines` and every home `/usr/libexec/java_home -V` lists, the Windows registry's JavaSoft `JavaHome` and Adoptium MSI `Path` entries, every `jdkHome` in Maven's `~/.m2/toolchains.xml`, and homes named by Gradle's `org.gradle.java.installations.paths`, `fromEnv`, and `maven-toolchains-file` (user-home and build-root `gradle.properties`) or by `JK_JDK_PATHS` and `JK_JDK_FROM_ENV`. | The JDK that launched Maven, plus every `jdkHome` in `toolchains.xml`. No download. | OS locations, `JAVA_HOME`, asdf, Jabba (`JABBA_HOME`), SDKMAN, Maven `toolchains.xml`, IntelliJ's JDK directory, and any path or env var named in Gradle properties. A miss provisions into the Gradle user home. |
| **Older bytecode** | `java = 17` or `21` compiles with the host JDK 25 via `--release`. No second JDK. | `--release` on whatever JDK is running Maven. | A toolchain `languageVersion` of 17 selects or downloads a JDK 17. `--release` is a separate setting. |
| **What a wipe will not touch** | `jk storage nuke` does not delete `~/.m2`. OS-packaged and IntelliJ-registered JDKs, Coursier's JVM cache, and homes that `toolchains.xml`, `gradle.properties`, or `JK_JDK_PATHS` only point at, are refused by `jk jdk uninstall`. SDKMAN, mise, JBang, jenv, asdf, Homebrew, and Jabba go through that tool first. jk deletes a JDK directory only under its managed root (`~/.jdks`, the macOS Library root, or `JK_JDKS_DIR`), symlinks resolved. Outside it, `JAVA_HOME`, Gradle's provisioned JDKs, and every other source without an owning tool are refused by name and path, and whatever an owning tool leaves in place stays. | Maven owns `~/.m2`. | Deleting the Gradle user home does not delete `~/.m2` or `~/.jdks`. |
| **Corporate Maven settings** | Mirrors, proxies, and server credentials in `~/.m2/settings.xml` apply. The lock still records Central's own URL. | This is Maven's file. | A Gradle build declares its own repositories. It does not read `settings.xml` as a default. |

The distinction that matters: Gradle already **finds** a JDK IntelliJ installed. JumpKick
**installs into the directory IntelliJ and Gradle both scan**, and it **fills the repository
Maven already builds from**. A jar JumpKick fetched is there for the next `mvn`. A JDK
JumpKick installed is there for IntelliJ's SDK list and for Gradle's toolchain detection.
Mill and Bazel are further down this page.

Details: [Install](install.md), [JDK](jdk.md), [Cache](cache.md), [Repositories](repositories.md).

## The build

| | JumpKick | Maven | Gradle |
|---|---|---|---|
| **Build file** | `jk.toml`. Data. One file per module, one lock at the workspace root. | `pom.xml`. Data, in XML. A reactor of POMs. | `build.gradle` / `build.gradle.kts`. A program. Convention plugins are more program. |
| **Lock** | `jk-lock.toml` is law: versions and checksums. `jk build` does not re-resolve when it is valid. | No lockfile. Reproducibility is a discipline (no ranges, no snapshots, pinned plugins). Maven 4 has not added a lockfile. | [Dependency locking](https://docs.gradle.org/current/userguide/dependency_locking.html) and dependency verification exist. Both are off until the build turns them on. A normal build resolves. |
| **Resolution** | PubGrub. Highest version wins when no platform BOM is present. A BOM is an enforced platform. Conflicts explain themselves in prose (`jk why`). | Nearest-wins mediation. A BOM import manages versions; it does not force a version a nearer node already chose. | Highest version by default, plus rich versions, capabilities, and platforms. `enforcedPlatform` is the enforced-BOM equivalent. Diagnosis is `dependencies` / `dependencyInsight`. |
| **Skip work** | Action cache on by default. Keys name content and a project-relative output, so two checkouts of one commit share hits. `jk explain` forecasts hits and misses. | Recompiles. Incremental compilation is the compiler plugin's, not a cache of the build. The [Build Cache Extension](https://maven.apache.org/extensions/maven-build-cache-extension/) is a separate opt-in, local and remote. | Incremental compilation and build cache are strong. The build cache is off until `org.gradle.caching=true`. The configuration cache is a second opt-in. |
| **Configuration** | Nothing in `jk.toml` executes. Project-local steps live in `jk/` or `.jk/` stem scripts, action-cached, outside the manifest. | Plugin configuration is data. Antrun, exec, and extensions are the escape hatches. | The build file runs. Configuration time is part of every cold build. The configuration cache exists because of that. |
| **Tests** | Named rungs: `jk test` (unit), `--guard` (unit + integration + guard scripts), `--all` (nightly). Cost is a tag (`slow`, `network`). The report lists what was skipped. | Surefire versus Failsafe, and filename patterns (`*Test`, `*IT`). | A `test` task and source sets the build defines. No product-level rung. |
| **House rules** | `jk-guards.toml`: what the project bans and requires, enforced by `jk guard`, readable by an agent. | Maven Enforcer, as a plugin. | A plugin, or a task the build authors. |
| **Staying current** | `jk add` writes today's stable. `jk update` moves declared pins on the same major (`--major` to cross) and relocks. The lock is still exact. | The version you write. Versions Plugin or a bot moves it. | The version you write, often via a catalog. A bot moves it. |

## Agents

| | JumpKick | Maven | Gradle |
|---|---|---|---|
| **Failure output** | `target/jk-results.md`: modules, steps, tests, diagnostics with file and line. `jk results` reprints it. | The log. Surefire XML on disk. | The log. Reports and build scans (Develocity) after the fact. |
| **Driving the tool** | MCP on the engine's loopback HTTP listener: run, results, diagnostics, manifest edits. `jk skill` is the playbook. | No first-party agent surface. | Tooling API for IDEs. No first-party MCP server. |
| **Code graph** | `jk graph` and its MCP tools, built in. Calls, overrides and type references come from the compiled classes; every build refreshes the graph. Writes graphify's `graph.json` format. | None first-party. Add a third-party tool: [graphify](https://github.com/Graphify-Labs/graphify) is a Python 3.10+ package (`pip`, `uv` or `pipx install graphifyy`) that parses source with tree-sitter and matches calls by name and declared type; it stays current through `graphify update`, a file watcher or git hooks; [RepoQL](https://repoql.com) is a separate binary and agent plugin with a free local engine and paid cloud plans. | Same as Maven. |
| **Watching an agent** | The web dashboard shows the run, the failure, and the change between attempts. Same facts as the CLI. | — | Build scans, when a scan server is in play. |
| **Before the rewrite** | `jk mvn` / `jk gradle` run the real build, wrapper-aware. `jk mvn` writes the same results file from Maven's events, Surefire and Failsafe XML, and compiler diagnostics. `jk build` can run an unmodified `pom.xml` from the effective POM. | — | — |
| **Import** | `jk import` writes `jk.toml` and a fidelity report graded per plugin. Common compiler, jar, Surefire, Boot, shade, and Kotlin mappings land. The report names what did not. | — | — |

The agent loop is measured: over 165 broken-build scenarios, five coding agents from three
vendors, the same prompt and budget for each tool. On the four agents that fix every scenario jk
takes 5–6 turns against 5–8, 23–46k input tokens against 25–69k, and 11–19 s against 14–32 s
(medians; one host, Gradle single-module guides — [the table](why.md#agents-reach-green)).
See [Why JumpKick](why.md) and [Agents](agents.md).

## Speed and memory

The sentences below are generated from the banked table in [Performance](performance.md), which
says what each row runs: Gradle with the configuration cache and the build cache on, Maven
without either because it has neither in stock form.

<!-- wall-claims:begin -->
On [spring-petclinic](performance.md), jk is faster than Gradle on every row: clean build (0.37 s against 2.69 s), warm rebuild (0.09 s against 0.57 s), no-op (0.05 s against 0.53 s), one-file edit (0.13 s against 0.61 s) and test run (8.54 s against 9.97 s). It is faster than Maven on every row: clean build (0.37 s against 3.16 s), warm rebuild (0.09 s against 3.16 s), no-op (0.05 s against 2.37 s), one-file edit (0.13 s against 3.14 s) and test run (8.54 s against 10.10 s).

Between builds jk keeps 863 MiB resident: 171 MiB for the engine, whose heap is capped at 256 MiB and trimmed back after every job, and the rest for warm compilers that exit after 30 idle minutes or as soon as the host runs short of memory. One engine serves every project on the machine. Gradle keeps 1,734 MiB, 2.0× as much: its daemon, which idles for three hours by default, and its workers. Maven keeps nothing resident and starts a fresh JVM for every build instead (2.37 s for a no-op). Peak whole-tree RSS during a run is lower than Gradle's on clean build, warm rebuild, no-op and one-file edit and higher on test run; it is higher than Maven's on every row.
<!-- wall-claims:end -->

The engine's own heap defaults to **256 MiB** (512 MiB when `CI=1`), and it hands memory back to
the operating system after each job ([Engine](engine.md#memory-after-a-build)). The RSS columns
are the whole tree: client, engine, and every compiler and test worker. Workers lease their heap
from one engine memory budget, test JVMs are sharded only as wide as the measured class times
make useful, and heaps are learned from what each worker really used.

## Batteries

Ships in the JumpKick binary. Maven and Gradle do the same jobs through plugins, which is an
advantage when the plugin you need is obscure and a tax when every repository reinvents the
wiring.

| | JumpKick | Maven and Gradle |
|---|---|---|
| Format | `jk format` | Spotless, or a formatter plugin |
| Null checking | `[nullness]`: JSpecify and NullAway, wired | Error Prone and NullAway on the processor path, their flags by hand |
| Audit / deny | `jk audit`, `jk deny` | A plugin (OWASP, dependency-check, enforcer bans, …) |
| SBOM, signing, provenance | `jk publish` with CycloneDX, SPDX, GPG, Sigstore, SLSA | A plugin each |
| OCI image | `jk image` | Jib or a Dockerfile task |
| Native image | `jk native` | A GraalVM plugin |
| One-off tools | `jkx` / `jk tool run` | Nothing first-party |
| Spring Boot, Quarkus, Micronaut | First-party tables | First-party or canonical plugins, more complete |
| Android | Contrib. Not AGP parity. `jk gradle` remains the full Android build. | Gradle **is** the Android build |
| Kotlin Multiplatform, Scala.js, full AGP | Out of scope | Gradle |

Core versus contrib, and what the plugin census says is covered: [Plugins](plugins.md).

## Scoreboard

Wins already true are the list at the top of this page, plus `java = N` cross-compilation and
reading Maven `settings.xml`. Everything we still lose, and still mean to win, is **open**.
When a row is won, change its score here and the matching cell above in the same edit.

### Open

| Category | Who leads today | Winning looks like |
|---|---|---|
| **Open the project in an IDE** | Maven and Gradle | Opening a `jk.toml` workspace in IntelliJ or VS Code needs no generated project files and no install-from-disk step. Today the plugin is an external system (live model, gutter run and debug) packaged from this repository. Without it, `jk ide` writes files. |
| **Plugin ecosystem** | Maven and Gradle | A third-party plugin is as ordinary to add as a dependency. The SDK is publishable with `jk publish -c` and is not on Central yet; a plugin is a pinned jar. There is no marketplace. The common server-side batteries are already first-party. |
| **Remote cache** | Gradle, and Maven's Build Cache Extension | A second machine restores an action-cache hit. Local keys are already shaped for that. The remote layer is not a product yet. |
| **Memory during a run** | Maven, and Gradle on the test run | The whole process tree's peak on every [petclinic](performance.md) row at or under both tools'. [Speed and memory](#speed-and-memory) says where it stands today. |
| **Import fidelity** | — | An imported Maven or Gradle build builds the same artifact without a fidelity-report row for the common plugins. Today an arbitrary exec, the release plugin, `war`, Tycho, OSGi, and a non-standard filtered resource directory are reports. |
| **Measured agent loop** | jk ahead on five agents, one host; ahead of Maven on Maven-first repos | jk ahead of Gradle on Kotlin repos in turns and tokens, not only wall. Results, MCP, `jk mvn` and the agent report are already real. |

### Held

These stay losses. They are not backlog.

| Category | Why it stays |
|---|---|
| **The build as a program** | `jk.toml` is data. A stem script in `jk/` or `.jk/` is the hatch. Matching Gradle's open language is the thing this tool refuses. |
| **Android, Kotlin Multiplatform, included builds** | Android ships as a contrib battery and is not AGP parity. Kotlin is JVM Kotlin. There is no composite-build equivalent. |
| **Running JumpKick on JDK 17 or 21** | The tool requires JDK 25 and will install one. `java = 17` emits that bytecode with `--release`. |
| **Adoption** | Maven is the Java default. Gradle is the default for Android and Kotlin. JumpKick is pre-1.0. |

## Mill and Bazel

The matrix above is Maven and Gradle, because that is the switch decision. The shared-directory
claim is sharper against Mill and Bazel than against Gradle.

| | JumpKick | Mill | Bazel |
|---|---|---|---|
| **Dependency cache** | `~/.jk/store`, written through to `~/.m2` by default | [Coursier](https://get-coursier.io/docs/cache)'s cache (`~/.cache/coursier` on Linux). Mill's own docs treat `~/.m2/repository` as a repository you can add and do not recommend. | Isolated, in the Bazel output base. `rules_jvm_external` can consult `~/.m2`; the default is not to. |
| **JDK it installs** | `~/.jdks` (IntelliJ's root) | Coursier's JVM cache (`~/.cache/coursier/jvm` on Linux), via the Coursier JVM index | A hermetic JDK in the Bazel cache |
| **JDK it looks for first** | The probes in the table above, the Coursier JVM cache among them | Coursier checks `JAVA_HOME`, then a platform default (`java_home` on macOS, `java` on `PATH`), and reads JDKs it installed under its JVM cache | The declared toolchain. Hermetic is the point. Scanning a Bazel output base is not a goal. |

Mill and Bazel are good at not depending on whatever the laptop happens to have installed.
JumpKick's bet is the opposite for these two directories: the laptop already has a Maven
repository and, if IntelliJ is installed, a JDK directory, and a second copy of either is
waste.

## Related

- [Why JumpKick](why.md) — the pitch, with the evidence behind each claim
- [Performance](performance.md) — the wall and RSS table
- [Migration](migration.md) — `jk mvn`, `jk gradle`, import grades
- [Concepts](concepts.md) — `java =` versus `jdk =`, lockfile, cache
- [Install](install.md) — `~/.jk`, `~/.jdks`, `~/.m2`
