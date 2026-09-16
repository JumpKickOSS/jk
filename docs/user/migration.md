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
