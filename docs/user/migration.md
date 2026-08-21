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

**POM import** is the high-fidelity path.

**Gradle import** does not execute build scripts (no Groovy/Kotlin evaluation). It does
read on-disk `gradle/libs.versions.toml` (libraries, bundles, `version.ref`) and maps
type-safe accessors like `libs.guava` into `[dependencies]`. Unresolved catalog refs show
up in the import report rather than vanishing. Versions stay on deps/BOMs — they are not
written into jk library catalog layers. Keep `jk gradle` for modules that still need full
Gradle.

Single-file scripts: `jk tool run script.java` / `jkx` — [Tools](tools.md).

MCP: `jk_import` auto-detects the build file; `jk_export` writes maven/gradle/bom (IDE
files stay `jk ide`).

## Related

[Projects](projects.md) · [Dependencies](dependencies.md) · [IDE](ide.md) · [Aliases](aliases.md)
