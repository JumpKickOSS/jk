# Migration from Maven and Gradle

You do not have to rewrite the build on day one.

```bash
jk mvn package                 # real Maven (wrapper-aware), managed by jk
jk gradle build                # real Gradle
jk import pom.xml              # → jk.toml + fidelity report
jk import build.gradle.kts     # declarative only (no script evaluation)
jk export maven                # publishable POM
jk export gradle
jk export idea | vscode
jk export bom                  # freeze lock as a Maven BOM — see Platforms
```

Everything after `jk mvn` / `jk gradle` belongs to the tool, `-Dkey=value` properties included —
`jk mvn -Drevision=1.2.3 -Dtest=FooTest verify` reaches Maven with both, `jk mvn -v` prints Maven's
version, `jk gradle -q build` keeps Gradle quiet, `jk mvn -C install` is Maven's strict-checksums
flag followed by a goal. jk's global flags go before the command name (`jk -q mvn package`,
`jk -C app gradle build`).

The one exception is the command's own four options, which say how jk provisions the tool and
so are matched wherever they appear after the name, spelled out in full: `--tools-dir <dir>`
(where jk installs Maven or Gradle), `--jdks-dir <dir>` (where it finds the JDK it runs them on),
`--no-discover` (skip the look for an installed one) and `--accept-unverified-tool` (below).
Neither Maven nor Gradle has a flag of those names, and only the exact spelling is taken —
`--tools` is Maven's — so nothing of the tool's is lost. `jk mvn --tools-dir /opt/jk-tools clean`
therefore provisions Maven under `/opt/jk-tools` and runs `mvn clean`; `jk --help mvn` lists the
four.

A distribution jk downloads is verified before it is unpacked, against the first of these that
exists: the wrapper's own `distributionSha256Sum`; a digest accepted for that version earlier;
the checksum its publisher puts beside the archive — Apache Maven's `.sha512` (3.7 and later),
else its `.sha1` (the 3.6 line publishes only that), Gradle's `.sha256`. The `Maven 3.6.3
downloaded · verified against the published .sha1` line says which one held. A wrapper pinned to
a distribution with none of the three is refused, naming each checksum jk looked for; pin its
SHA-256 in `maven-wrapper.properties`, or accept that one download with
`jk mvn --accept-unverified-tool …` (or `JK_ACCEPT_UNVERIFIED_TOOL=1` in a CI step): jk installs
the archive, records its SHA-256 as `tools/maven/<version>.accepted.sha256` in the store, and
verifies every later download of that version against it, so the next run is silent. The flag
never bypasses a checksum that is published.

`jk mvn` also writes jk's run report for the Maven run: `target/jk-results.md` and the history
row behind `jk results`, MCP `jk_results` and `jk_diagnostics` (header `trigger: cli · tool: mvn`).
A small Maven core extension, `jk-maven-spy-<version>.jar`, rides Maven's `-Dmaven.ext.class.path`
and records the reactor's events; after Maven exits the engine folds those events, each module's
`target/surefire-reports` / `target/failsafe-reports` XML and the compiler plugin's
`file:[line,col]` failures into the same Tests, Modules and Diagnostics blocks a jk build gets.
The jar is looked up in this order, first hit wins: the `jk.maven-spy.jar` system property;
`~/.jk/lib/jk-maven-spy-<version>.jar` (where the installers put it from a dist); the store's
`jk-local` shelf (`jk install` from a checkout); `lib/` beside the `jk` binary (the `target/dist`
ship layout). A release install (`curl … | bash`, `install.ps1`, the JVM client) carries no spy
until the first `jk mvn`, which fetches its own version's `jk-maven-spy-<version>.jar` from the
release directory into `~/.jk/lib/` — the same directory, signed `SHA256SUMS` and checksum the
engine jar is held to, shown as a `Maven` download line — and then runs Maven with it. A fetch that
fails (offline, a mirror without the jar) is one line on stderr; Maven still runs, without a
report, and the next `jk mvn` tries again. `jk doctor` has an `mvn` row that names the jar's
place and the one command that fills it. With no jar found, `jk mvn` is a plain passthrough and
writes no report. A project with only a `pom.xml` binds for MCP by its POM coordinate.

**POM import** is the primary path, and it reads the POM the way Maven does: the effective
model, built by Maven's own model builder. Parents are flattened (a sibling `pom.xml` in the
reactor answers first, then any `<repository>` the POM declares, then the repositories jk knows),
`dependencyManagement` is merged so a dependency declared without a version gets the managed one,
`import`-scope BOMs become `[platform]` entries with their versions resolved, `${property}`
placeholders are interpolated, and profiles Maven would activate on this machine (active by
default, JDK, OS) are folded in. The fidelity report names what each parent contributed —
"versions for X, Y managed by parent g:a:v" — and a parent no repository has is a Tier-3 row, not
a failed import. The compiler level is written as `java = N`, never as a `jdk` pin: a level below
17 is raised to jk's floor with a row saying so, and `jdk =` appears only when the POM pins a
toolchain (`maven-toolchains-plugin` or `<jdkToolchain>`). Inactive profiles land by payload
(the table below); exclusions and classifiers are still rows. Read that report before trusting the
generated `jk.toml`.

**A direct version is the version, as it is under Maven.** Import writes every `<dependency>`
version as an exact pin and sets `[resolve] pins = "nearest"`, so the lock resolves a pinned module
the way Maven's nearest-wins did: the project's pin is the version, and a transitive POM's range on
that module is reported, not enforced. `cryptofs 2.10.0` declares `jakarta.inject-api 2.0.1.MR`;
the POM's own `2.0.1` wins, the lock edge reads `jakarta.inject-api@2.0.1 <- 2.0.1.MR`, and
`jk lock` prints one warning per overridden range so the divergence from what the library asked for
is on record. A `jk.toml` written by hand keeps the default, `pins = "exact"`, under which the same
shape is a conflict PubGrub refuses with its explanation; delete the `[resolve]` line to get that
strictness back on an imported project. The alternative — importing direct versions as `>=` floors
so highest-wins lifts them — would float every imported project past the versions Maven built
with, which is not what the POM says. Making an existing Maven project work under jk — plugin-aware
mapping, structured results from `jk mvn`, and a jk loop over an unmodified `pom.xml` — is the
first epic of [the 1.0 plan](../contributors/plan-1.0.md).

**A reactor imports as one workspace.** The walk follows `<modules>` the way Maven does: through
every aggregator (a `pom`-packaged module with `<modules>` of its own is a parent and a list, not
a module jk builds) and into the modules a profile active on this machine adds, so a reactor of
181 POMs becomes one `[workspace]` of root-relative paths (`community/kernel`, `websocket/spi`).
A dependency on any module of the reactor is a workspace edge — `{ workspace = true }`, with
`kind = "tests"` for a `test-jar` — wherever the module sits and however its version is spelled,
because siblings match by `groupId:artifactId`. A sibling answers as a parent and as an
`import`-scope BOM before any repository is asked, so a `dependencyManagement` that imports a
sibling BOM is applied to the declared dependencies and the BOM is not written as a `[platform]`
entry (a workspace module is not a published BOM; the row says so). CI-friendly versions —
`${revision}`, `${changelist}`, `${sha1}` — take their values from the POM chain's
`<properties>`, which is where Maven reads them without `-D`; a placeholder no POM defines is
written as `0.0.0-SNAPSHOT` with a row naming the property. A module list that lives only in
profiles Maven would not activate here is a Tier-3 row, and a workspace whose modules have no
source tree fails `jk build` with a one-line `built nothing` reason instead of finishing green.

### Where import stands on real repositories

The [Maven top-20 corpus](https://github.com/JumpKickOSS/jk-examples/tree/main/corpus/maven-top20)
in jk-examples clones the twenty most-starred GitHub repositories that build with Maven on Java 17
or newer and drives Maven and jk through one protocol: import, lock, build, test, and the same
cold / no-op / one-file-edit walls for both tools. Its `RESULTS.md` is the ratchet; a run that
lowers a count is a regression.

| Count (of 20) | jk 0.13.7 | main, run 2 | main, run 3 | main, run 4 |
|---|---:|---:|---:|---:|
| import with no Tier-3 row | 15 | 13 | 11 | 12 |
| `jk lock` succeeds | 2 | 6 | 6 | 4 |
| `jk build --skip-tests` compiles something | 1 | 3 | 3 | 2 |
| `jk test` runs and passes | 0 | 0 | 0 | 1 |

The lock and build counts moved because the effective-POM import resolved every managed version;
the import count fell because the same import now reports a parent or BOM it cannot fetch as a
Tier-3 row where 0.13.7 wrote `=unresolved` and failed later. Run 3 lowered it again for the same
reason: the packaging mapping now names a `war` module (apollo, java-design-patterns) as a Tier-3
row instead of importing it as a jar that Maven would never have built that way. An import count
that falls because a silent mismatch became a named one is the ratchet working; a count that falls
because a repository stopped importing is not. Run 4 (main 12de60df5, all of 2026-09-16) is the
first with a repository green end to end: TheAlgorithms/Java runs and passes its 9,745 tests under
jk, in 13 s against 37 s under Maven, once the test JVM kept the platform default thread stack.
Its lock and build counts fell for a reason the table cannot show: run 3 imported dataease as one
module of fifteen and neo4j as three of 181, and locked those fragments; run 4 imports the whole
reactor of each (nested aggregators, CI-friendly versions, sibling edges) and the full graph hits
two walls the fragments never reached. Both are tickets: two imported BOMs managing one artifact,
where Maven takes the first-declared import and jk still refuses (seven repositories); and a
workspace module's exact pin losing to a transitive's floor under the nearest policy (neo4j,
analysis-ik). The other walls are named in the corpus's `tier3-reasons.md`, each with its ticket.

### Which Maven plugins import, and how well

Counted across 66 public repositories cloned for the Maven corpus and the agent-loop corpus on
2026-09-16 (root and module POMs, `<build>` and `<pluginManagement>`; a repo counts once per
plugin). The fidelity report cites this table per plugin; a grade says how the imported build
relates to the Maven one.

| Plugin | Repos | Lands in jk as | Grade |
|---|---:|---|---|
| spring-boot-maven-plugin | 36 | `[spring-boot] version` at the Boot version the chain resolves (Boot jar, platform BOM); `<mainClass>` → `[application] main`; `<excludes>` and buildpack `<image>` → rows | approximate |
| maven-surefire-plugin | 35 | `<groups>` / `<excludedGroups>` → `[test] include-tags` / `exclude-tags`; `<argLine>` (minus `${argLine}` and the JaCoCo agent) → `[test] jvm-args`; `<systemPropertyVariables>` / `<systemProperties>` → `[test] system-properties`; `<includes>` / `<excludes>` and `skipTests` → rows (`--class`, `--skip-tests`) | exact |
| maven-compiler-plugin | 35 | `java =` (floor 17), `<compilerArgs>` and the `<parameters>`, `<enablePreview>`, `<failOnWarning>` switches → `[javac] args`, `annotationProcessorPaths` → `[processor-dependencies]` | exact |
| maven-jar-plugin | 24 | `[manifest]` entries, `Main-Class` → `[application]` | exact |
| maven-javadoc-plugin | 22 | a library ships the javadoc jar by default; `<failOnError>true` / `<doclint>` → `javadoc = "strict"` | exact |
| maven-source-plugin | 19 | `sources = "always"` — the sources jar on every `jk build` | exact |
| maven-resources-plugin | 19 | nothing to map for the fixed layout; a filtered or non-standard `<resource>` directory is a row | approximate |
| jacoco-maven-plugin | 17 | `jk test --coverage` is a run flag, not a manifest key → row | manual |
| maven-gpg-plugin | 17 | `jk publish --sign` | exact |
| maven-assembly-plugin | 17 | `jar-with-dependencies` → `[application] assembly = true` (needs a `<mainClass>`, else a row); other descriptors → row | approximate |
| maven-enforcer-plugin | 17 | `requireJavaVersion` → `java =`; banned deps → `jk deny`; rest → row | approximate |
| build-helper-maven-plugin | 16 | `add-source` → `[build] extra-src`, `add-test-source` → `[test] extra-src`; other goals → row | approximate |
| exec-maven-plugin | 16 | `java` goal → `[application]`; `exec` goal → row (build logic) | manual |
| maven-dependency-plugin | 15 | nothing (analysis / copy goals) → row when bound to a phase | manual |
| maven-clean-plugin | 15 | `jk clean` | exact |
| maven-checkstyle-plugin | 14 | lint step (planned battery) → row until then | manual |
| maven-deploy-plugin | 14 | `jk publish` | exact |
| maven-install-plugin | 13 | `jk install` to `~/.m2` | exact |
| maven-failsafe-plugin | 13 | row naming its patterns (`**/*IT.java`, …) and the move into `src/integration/java`, jk's `integration` suite; `argLine` and system properties → the same `[test]` keys when Surefire set none, else a row | manual |
| maven-shade-plugin | 12 | `[application] assembly = true`, `Main-Class` from the manifest transformer; relocations, filters, other transformers and `minimizeJar` → rows | approximate |
| kotlin-maven-plugin | 12 | `kotlin =` on the module; `test-compile`-only → mixed module | exact |
| maven-release-plugin | 11 | nothing (release flow) | manual |
| central-publishing-maven-plugin | 11 | `jk publish --central` (planned battery) | manual |
| spotbugs-maven-plugin | 10 | lint step (planned battery) | manual |
| spotless-maven-plugin | 10 | `jk format` | approximate |
| maven-antrun-plugin | 10 | build logic script → row | manual |
| maven-pmd-plugin | 8 | lint step (planned battery) | manual |
| license-maven-plugin | 8 | nothing → row | manual |
| protobuf-maven-plugin | 8 | `[protobuf]` | exact |
| versions-maven-plugin | 7 | `jk outdated` / `jk update` | exact |
| docker-maven-plugin / jib-maven-plugin | 7 | row carrying the `[image]` lines to paste (`base`, `registry`, `name`, `tag` from `<from>` / `<to>`); nothing is written to `jk.toml` | manual |
| frontend-maven-plugin | 7 | `[dev.sidecars]` + a resource module | manual |
| quarkus-maven-plugin | 7 | `[quarkus]` | exact |
| flatten-maven-plugin | 6 | nothing (`jk export maven` writes a flat POM) | exact |
| native-maven-plugin | 6 | `[native]`: `<imageName>` → `name`, `<buildArgs>` → `args`; `<mainClass>` → `[application] main` | approximate |
| maven-war-plugin | 5 | not supported (packaging `war`) → Tier-3 row | none |
| antlr4-maven-plugin | 5 | `[antlr]` (planned generator preset) | manual |
| openapi-generator-maven-plugin | 4 | `[openapi]` (planned generator preset) | manual |

**Grades.** *exact*: the imported build does what the plugin did. *approximate*: the common
configuration maps; unusual configuration lands in the report. *manual*: the report names the
plugin and where its job belongs. *none*: the module needs `jk mvn`.

### Where Maven profiles land

Maven uses one `<profile>` for five different jobs; jk keeps them apart, so import maps by
payload and activation rather than one-to-one:

| Maven profile shape | Lands in jk as | Why |
|---|---|---|
| Active on this machine (`activeByDefault`, JDK range, OS, property or file that matches) | Folded into the effective model; the report says which profile was applied | That is what Maven itself would build here |
| Not active; `<dependencies>` (versions from the profile's own `<dependencyManagement>` when it has one) | `[features.<id>]` with those deps marked `optional = true`; not in `default` | A feature is exactly "what optional deps you have" |
| Not active; `maven.compiler.*` or `<compilerArgs>`, an `argLine` property | `[profiles.<id>]` `javac` (`--release N`, the args) / `jvm-args` | A jk profile is "how you compile" |
| Not active; `<repositories>` | Merged into the top-level repositories with a Tier-2 row | A repository is never conditional in jk |
| Not active; `<build><plugins>` | Hand-port checklist row naming the plugins | Plugin mapping is its own table; a profile does not change where a plugin lands |
| JDK- or OS-activated with per-platform deps (native classifiers, `os-maven-plugin`) | Tier-2 row proposing a `[variants]` dimension | Which product you build, not what you compile with |
| `<properties>` that only other POM fields read | Interpolated away; nothing written | The effective model already substituted them |

Activation kinds that never map: a property set on the Maven command line (`-Pfoo` /
`-Dfoo=true`) has no jk equivalent, so the import names the profile and its payload's landing
place; `<file>` existence stays a checklist row. A profile with more than one payload kind gets
one row per kind, each naming the same `<id>`; a `<dependencyManagement>` with no dependency of
its own is a row and nothing is written.

**Gradle import** does not execute build scripts (no Groovy/Kotlin evaluation). It does
read on-disk `gradle/libs.versions.toml` (libraries, bundles, `version.ref`) and maps
type-safe accessors like `libs.guava` into `[dependencies]`. Unresolved catalog refs show
up in the import report rather than vanishing. Versions stay on deps/BOMs — they are not
written into jk library catalog layers. Keep `jk gradle` for modules that still need full
Gradle.

Single-file scripts: `jk tool run script.java` / `jkx` — [Tools](tools.md).

MCP: `jk_import` auto-detects the build file; `jk_export` writes maven/gradle/bom; `jk_ide`
writes the IDE project files.

## Related

[Projects](projects.md) · [Dependencies](dependencies.md) · [IDE](ide.md) · [Aliases](aliases.md)
