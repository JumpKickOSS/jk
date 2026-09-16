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

Everything after `jk mvn` / `jk gradle` belongs to the tool — `jk mvn -v` prints Maven's
version, `jk gradle -q build` keeps Gradle quiet, `jk mvn -C install` is Maven's strict-checksums
flag followed by a goal. jk's global flags go before the command name (`jk -q mvn package`,
`jk -C app gradle build`).

The one exception is the command's own three options, which say how jk provisions the tool and
so are matched wherever they appear after the name, spelled out in full: `--tools-dir <dir>`
(where jk installs Maven or Gradle), `--jdks-dir <dir>` (where it finds the JDK it runs them on)
and `--no-discover` (skip the look for an installed one). Neither Maven nor Gradle has a flag of
those names, and only the exact spelling is taken — `--tools` is Maven's — so nothing of the
tool's is lost. `jk mvn --tools-dir /opt/jk-tools clean` therefore provisions Maven under
`/opt/jk-tools` and runs `mvn clean`; `jk --help mvn` lists the three.

`jk mvn` also writes jk's run report for the Maven run: `target/jk-results.md` and the history
row behind `jk results`, MCP `jk_results` and `jk_diagnostics` (header `trigger: cli · tool: mvn`).
A small Maven core extension, `jk-maven-spy-<version>.jar`, rides Maven's `-Dmaven.ext.class.path`
and records the reactor's events; after Maven exits the engine folds those events, each module's
`target/surefire-reports` / `target/failsafe-reports` XML and the compiler plugin's
`file:[line,col]` failures into the same Tests, Modules and Diagnostics blocks a jk build gets.
The jar is looked up in this order, first hit wins: the `jk.maven-spy.jar` system property;
`~/.jk/lib/jk-maven-spy-<version>.jar` (where `install.sh` puts it); the store's `jk-local` shelf
(`jk install` from a checkout); `lib/` beside the `jk` binary (the `target/dist` ship layout). With
no jar found, `jk mvn` is a plain passthrough and writes no report. A project with only a `pom.xml`
binds for MCP by its POM coordinate.

**POM import** is the primary path, and it reads the POM the way Maven does: the effective
model, built by Maven's own model builder. Parents are flattened (a sibling `pom.xml` in the
reactor answers first, then any `<repository>` the POM declares, then the repositories jk knows),
`dependencyManagement` is merged so a dependency declared without a version gets the managed one,
`import`-scope BOMs become `[platform]` entries with their versions resolved, `${property}`
placeholders are interpolated, and profiles Maven would activate on this machine (active by
default, JDK, OS) are folded in. The fidelity report names what each parent contributed —
"versions for X, Y managed by parent g:a:v" — and a parent no repository has is a Tier-3 row, not
a failed import. Still not mapped: profiles that are not active (each gets a hand-port checklist),
plugins other than `maven-compiler-plugin`, exclusions and classifiers. Read that report before
trusting the generated `jk.toml`. Making an existing Maven project work under jk — plugin-aware
mapping, structured results from `jk mvn`, and a jk loop over an unmodified `pom.xml` — is the
first epic of [the 1.0 plan](../contributors/plan-1.0.md).

### Which Maven plugins import, and how well

Counted across 66 public repositories cloned for the Maven corpus and the agent-loop corpus on
2026-09-16 (root and module POMs, `<build>` and `<pluginManagement>`; a repo counts once per
plugin). The fidelity report cites this table per plugin; a grade says how the imported build
relates to the Maven one.

| Plugin | Repos | Lands in jk as | Grade |
|---|---:|---|---|
| spring-boot-maven-plugin | 36 | `[spring-boot]` (Boot jar, platform BOM) | exact |
| maven-surefire-plugin | 35 | `[test]` includes/excludes, `argLine`, system properties, `<groups>` → tags | approximate |
| maven-compiler-plugin | 35 | `java =`, `[javac]` args, `annotationProcessorPaths` → `[processor-dependencies]` | exact |
| maven-jar-plugin | 24 | `[manifest]` entries, `Main-Class` → `[application]` | exact |
| maven-javadoc-plugin | 22 | javadoc jar from `jk package` for a library | exact |
| maven-source-plugin | 19 | sources jar from `jk package` | exact |
| maven-resources-plugin | 19 | resource filtering when declared; otherwise nothing to map | approximate |
| jacoco-maven-plugin | 17 | `[test] coverage = true` | exact |
| maven-gpg-plugin | 17 | `jk publish --sign` | exact |
| maven-assembly-plugin | 17 | fat jar (`jar-with-dependencies`); other descriptors → row | approximate |
| maven-enforcer-plugin | 17 | `requireJavaVersion` → `java =`; banned deps → `jk deny`; rest → row | approximate |
| build-helper-maven-plugin | 16 | `add-source` / `add-test-source` → extra source roots | exact |
| exec-maven-plugin | 16 | `java` goal → `[application]`; `exec` goal → row (build logic) | manual |
| maven-dependency-plugin | 15 | nothing (analysis / copy goals) → row when bound to a phase | manual |
| maven-clean-plugin | 15 | `jk clean` | exact |
| maven-checkstyle-plugin | 14 | lint step (planned battery) → row until then | manual |
| maven-deploy-plugin | 14 | `jk publish` | exact |
| maven-install-plugin | 13 | `jk install` to `~/.m2` | exact |
| maven-failsafe-plugin | 13 | `integration` suite from `*IT` patterns, `argLine` | approximate |
| maven-shade-plugin | 12 | fat jar with relocations → row; plain shade → fat jar | approximate |
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
| docker-maven-plugin / jib-maven-plugin | 7 | `[image]` | approximate |
| frontend-maven-plugin | 7 | `[dev.sidecars]` + a resource module | manual |
| quarkus-maven-plugin | 7 | `[quarkus]` | exact |
| flatten-maven-plugin | 6 | nothing (`jk export maven` writes a flat POM) | exact |
| native-maven-plugin | 6 | `[native]` | approximate |
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
| Not active; only `<dependencies>` / `<dependencyManagement>` | `[features.<id>]` with those deps marked `optional = true`; not in `default` | A feature is exactly "what optional deps you have" |
| Not active; only `maven.compiler.*` or `<compilerArgs>` properties | `[profiles.<id>]` `javac-args` / `jvm-args` | A jk profile is "how you compile" |
| Not active; `<repositories>` | Merged into the top-level repositories with a Tier-2 row | A repository is never conditional in jk |
| Not active; `<build><plugins>` | Hand-port checklist row naming the plugins | Plugin mapping is its own table; a profile does not change where a plugin lands |
| JDK- or OS-activated with per-platform deps (native classifiers, `os-maven-plugin`) | Tier-2 row proposing a `[variants]` dimension | Which product you build, not what you compile with |
| `<properties>` that only other POM fields read | Interpolated away; nothing written | The effective model already substituted them |

Activation kinds that never map: a property set on the Maven command line (`-Pfoo` /
`-Dfoo=true`) has no jk equivalent, so the import names the profile and its payload's landing
place; `<file>` existence stays a checklist row. A profile with more than one payload kind gets
one row per kind, each naming the same `<id>`.

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
