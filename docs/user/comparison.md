# JumpKick, Maven, and Gradle

What an evaluator can hold JumpKick against, and the scoreboard for closing the gaps. The
product bet is in [Why JumpKick](why.md). The one measured speed and memory table is in
[Performance](performance.md).

JumpKick is **pre-1.0**. A cell describes current behavior. Where a number is cited, it is the
spring-petclinic run in [Performance](performance.md), not a general claim. A **loss** in the
[scoreboard](#scoreboard) stays a loss until the JumpKick cell is true. A **held** loss is the
product bet or an explicit non-goal, and is not waiting on a fix.

## Where JumpKick wins

These are the rows that are true of JumpKick by default and not of stock Maven or stock Gradle.

1. **The machine already has a Maven repository and an IntelliJ JDK directory, and JumpKick uses both.** Fetched jars are written through to `~/.m2/repository`. JDKs JumpKick installs go in IntelliJ's shared root (`~/.jdks`, or `~/Library/Java/JavaVirtualMachines` on macOS). A download happens only after the probes below have missed. That probe list is not yet a superset of Gradle's — see the scoreboard.
2. **`java = 17` does not download a JDK 17.** The host JDK cross-compiles with `--release`. A `jdk =` pin is the rare case that selects an install.
3. **The lockfile is law.** `jk build` does not re-resolve a valid `jk-lock.toml`. Every row carries a checksum.
4. **The manifest is data.** `jk.toml` is TOML. Plugins add tables. They do not add a programming language to the build file.
5. **Tests have named rungs.** `jk test` is the unit suite. `--guard` is the share-the-commit bar. `--all` is nightly. The report says what was not run.
6. **An agent gets a file, not a log.** `target/jk-results.md`, MCP, and the web dashboard are the same model. `jk mvn` and `jk gradle` run the real tool and, for Maven, fold its events into that same report.
7. **The action cache is on.** Content-addressed, shared across checkouts of one commit, and not an opt-in.
8. **The daily batteries ship in the binary.** Format, audit and deny, CycloneDX and SPDX, signing, OCI images, GraalVM native-image, and `jkx` are commands, not plugins a project has to discover.

The matrices below keep the ties and the losses. Open losses are the [scoreboard](#scoreboard).

## Sharing the machine

JumpKick keeps its own home (`~/.jk`: client, engine, action cache, artifact store) and treats two
directories it does not own as shared infrastructure.

| | JumpKick | Maven | Gradle |
|---|---|---|---|
| **Third-party jars** | Own store under `~/.jk/store`, **and** `~/.m2/repository` by default. A digest-matching file already in the Maven local repository is copied in instead of downloaded. A fetch is written back through. `[m2] integration = false` turns the sharing off. | `~/.m2/repository` is Maven's local repository. | Dependencies live in the Gradle user home (`~/.gradle/caches`). `mavenLocal()` is a repository a build can add. Gradle does not read or fill `~/.m2` unless the build asks. |
| **Where a JDK JumpKick (or the tool) installs lands** | IntelliJ's shared root: `~/.jdks`, or `~/Library/Java/JavaVirtualMachines` on macOS. Inventory and fingerprints stay in `~/.jk/state`, because the directory is shared. | Maven does not install JDKs. The toolchain plugin points at JDKs listed in `toolchains.xml`. | Detects an IntelliJ-installed JDK, then **installs its own** under the Gradle user home (`<gradle-user-home>/jdks`) when a toolchain resolver is configured. |
| **Looks for a JDK before downloading** | **Loss.** A miss still installs into the shared root. Probes today: `JAVA_HOME`, JumpKick's own, IntelliJ, Gradle's provisioned JDKs, SDKMAN, JBang, mise, asdf, jenv, Homebrew, Linux `/usr/lib/jvm` and `/usr/java`, macOS `/Library/Java/JavaVirtualMachines`. Not probed: Maven `~/.m2/toolchains.xml`, Jabba, the Windows registry, macOS `/usr/libexec/java_home`, Linux `/usr/lib64/jvm`, `/usr/local/java`, and `/opt/java`, the Coursier JVM cache, and homes named by Gradle's `org.gradle.java.installations.paths` and `fromEnv`. On Windows the system probe has no roots at all. | The JDK that launched Maven, plus every `jdkHome` in `toolchains.xml`. No download. | OS locations, `JAVA_HOME`, asdf, Jabba (`JABBA_HOME`), SDKMAN, Maven `toolchains.xml`, IntelliJ's JDK directory, and any path or env var named in Gradle properties. A miss provisions into the Gradle user home. |
| **Older bytecode** | `java = 17` or `21` compiles with the host JDK 25 via `--release`. No second JDK. | `--release` on whatever JDK is running Maven. | A toolchain `languageVersion` of 17 selects or downloads a JDK 17. `--release` is a separate setting. |
| **What a wipe will not touch** | `jk storage nuke` does not delete `~/.m2`. OS-packaged and IntelliJ-registered JDKs are refused by `jk jdk uninstall`. SDKMAN, mise, JBang, jenv, asdf, and Homebrew go through that tool first. **Loss:** a version match whose hit is `JAVA_HOME` or a JDK Gradle provisioned is not refused, and the fallback delete removes that directory wherever it sits. | Maven owns `~/.m2`. | Deleting the Gradle user home does not delete `~/.m2` or `~/.jdks`. |
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
| **Driving the tool** | MCP on the engine's loopback HTTP listener: run, results, diagnostics, manifest edits. `jk manual` is the playbook. | No first-party agent surface. | Tooling API for IDEs. No first-party MCP server. |
| **Watching an agent** | The web dashboard shows the run, the failure, and the change between attempts. Same facts as the CLI. | — | Build scans, when a scan server is in play. |
| **Before the rewrite** | `jk mvn` / `jk gradle` run the real build, wrapper-aware. `jk mvn` writes the same results file from Maven's events, Surefire and Failsafe XML, and compiler diagnostics. `jk build` can run an unmodified `pom.xml` from the effective POM. | — | — |
| **Import** | `jk import` writes `jk.toml` and a fidelity report graded per plugin. Common compiler, jar, Surefire, Boot, shade, and Kotlin mappings land. The report names what did not. | — | — |

The agent-loop **surface** is shipped. A published turns-to-green number against Maven and Gradle
is not. Until that table exists, "agents finish faster" is a design claim, not a measurement.
See [Why JumpKick](why.md) and [Agents](agents.md).

## Speed and memory

On [spring-petclinic](https://github.com/spring-projects/spring-petclinic), one module, measured
2026-09-16. Figures, machine, and what each row actually runs: [Performance](performance.md).
Gradle was run with the configuration cache and the build cache on. Maven has neither in stock
form, and the row says so.

| | Against Gradle | Against Maven |
|---|---|---|
| Clean build | Faster (0.90 s vs 2.04 s median) | Faster (vs 2.26 s) |
| Warm rebuild | Faster (0.10 s vs 0.43 s) | Faster (vs 2.40 s). Maven recompiles. |
| No-op | Faster (0.09 s vs 0.44 s) | Faster (vs 1.84 s) |
| One-file edit | Slower (0.64 s vs 0.52 s) | Faster (vs 2.23 s) |
| Test run | Close, slightly slower (26.15 s vs 24.45 s) | Close, slightly slower (vs 23.66 s) |
| Peak RSS, build | Higher (about 2.7 GiB vs 1.7 GiB) | Much higher (vs about 0.4 GiB) |
| Peak RSS, test | Higher (about 7.5 GiB vs 2.9 GiB) | Much higher (vs about 1.1 GiB) |

The engine's own heap defaults to **256 MiB** (512 MiB when `CI=1`). A job that does not fit
queues instead of taking the process down. That cap is the coordinator. The RSS column is the
whole tree: client, engine, and every compiler and test worker. On this project the workers
dominate, and JumpKick's test run shards across forks sized from the host. The cap is real.
"Uses less memory than Maven" is not.

## Batteries

Ships in the JumpKick binary. Maven and Gradle do the same jobs through plugins, which is an
advantage when the plugin you need is obscure and a tax when every repository reinvents the
wiring.

| | JumpKick | Maven and Gradle |
|---|---|---|
| Format | `jk format` | Spotless, or a formatter plugin |
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
| **JDK discovery** | Gradle | The probe list is a superset of Gradle's suppliers, Maven `toolchains.xml`, and Mill's Coursier JVM cache. A JDK any of them would use, JumpKick uses, and does not download again. Bazel's output base is not a probe: those JDKs are per-checkout and hermetic on purpose. |
| **Uninstall of a JDK someone else installed** | — | `jk jdk uninstall` never deletes a home outside the managed JDK root unless the owning tool removed it. `JAVA_HOME`, Gradle's provisioned JDKs, a `toolchains.xml` pointer, a registry entry, and a Coursier cache entry are refused or delegated, not `rm`'d. |
| **Open the project in an IDE** | Maven and Gradle | Opening a `jk.toml` workspace in IntelliJ or VS Code needs no generated project files and no install-from-disk step. Today the plugin is an external system (live model, gutter run and debug) packaged from this repository. Without it, `jk ide` writes files. |
| **Plugin ecosystem** | Maven and Gradle | A third-party plugin is as ordinary to add as a dependency. Today the SDK publishes to Central and a plugin is a pinned jar. There is no marketplace. The common server-side batteries are already first-party. |
| **Remote cache** | Gradle, and Maven's Build Cache Extension | A second machine restores an action-cache hit. Local keys are already shaped for that. The remote layer is not a product yet. |
| **Memory of a build** | Maven, then Gradle | Peak RSS of the whole process tree on the [petclinic](performance.md) run is in Maven's range, or at least Gradle's. The 256 MiB figure is the engine heap cap. The measured tree is about 2.7 GiB on a build and about 7.5 GiB on the test run. |
| **One-file edit** | Gradle | Median at or under Gradle's 0.52 s on that run (JumpKick is 0.64 s). |
| **Test wall** | Maven, then Gradle | Median at or under Maven's 23.66 s on that run (JumpKick is 26.15 s), without the 7.5 GiB RSS. |
| **Import fidelity** | — | An imported Maven or Gradle build builds the same artifact without a fidelity-report row for the common plugins. Today Failsafe's `*IT.java` layout, an arbitrary exec, the release plugin, `war`, Tycho, OSGi, and a non-standard filtered resource directory are reports. |
| **Measured agent loop** | Unmeasured | A published turns-to-green table beats Maven and Gradle on the same scenarios. Results, MCP, and `jk mvn` are already real. |

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
| **JDK it looks for first** | The probes in the table above. The Coursier JVM cache is not one of them yet (scoreboard). | Coursier checks `JAVA_HOME`, then a platform default (`java_home` on macOS, `java` on `PATH`), and reads JDKs it installed under its JVM cache | The declared toolchain. Hermetic is the point. Scanning a Bazel output base is not a goal. |

Mill and Bazel are good at not depending on whatever the laptop happens to have installed.
JumpKick's bet is the opposite for these two directories: the laptop already has a Maven
repository and, if IntelliJ is installed, a JDK directory, and a second copy of either is
waste.

## Related

- [Why JumpKick](why.md) — the bet and the feature ranking
- [Performance](performance.md) — the wall and RSS table
- [Migration](migration.md) — `jk mvn`, `jk gradle`, import grades
- [Concepts](concepts.md) — `java =` versus `jdk =`, lockfile, cache
- [Install](install.md) — `~/.jk`, `~/.jdks`, `~/.m2`
