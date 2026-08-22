# Packaging

JumpKick writes **additive** artifacts. The thin jar is always produced. Extra flags add jars
beside it; they do not replace it. Framework plugins that own the product shape (Spring Boot,
Quarkus, Grails) still own it — a Boot jar is not “thin plus extras.”

R8 minification is **opt-in**, never the default.

| Artifact | Config | Command |
|----------|--------|---------|
| Thin jar `target/<name>-<version>.jar` | always | `jk build` |
| Fat jar `*-all.jar` | `[application] assembly = true` | `jk assemble` / `jk build` |
| Minified jar `*-min.jar` | `[application] minified = true` | `jk assemble` / `jk build` (also builds the fat jar) |
| Spring Boot jar | spring-boot plugin | `jk build` |
| Quarkus fast-jar / uber-jar | `[quarkus]` | `jk build` |
| Grails jar (Boot layout) | grails plugin | `jk build` |

```toml
[application]
main     = "com.example.App"   # required for run / fat / typical apps
assembly = true
# minified = true
# config   = "config/install.toml"  # optional template → $JK_CONFIG_DIR/<bin>/config.toml
```

On `jk install`, fat/minified apps land under `<data>/lib/<bin>/` and write
`$JK_CONFIG_DIR/<bin>/config.toml` (default `~/.config/jk/<bin>/config.toml`, or
`$JK_HOME/config/<bin>/config.toml`). If `config` is set, that module-relative template is
rendered with `${version}`, `${jar}`, `${name}`, and any `jk-config.*` system properties.
Without `config`, those same `jk-config.*` properties (plus `jar` / `version` / `name`) are
written directly.

Samples: [assembly-app](examples/assembly-app/), [minified-cli](examples/minified-cli/).
Frameworks: [Frameworks](frameworks.md). Native / OCI: [Native](native.md), [Images](images.md).

## One-off CLI override

```bash
jk assemble --fat
jk assemble --minified
jk assemble --minified --write-config    # also set minified = true in jk.toml
jk assemble --fat --write-config
```

`--write-config` surgically edits `[application].assembly` / `.minified` (requires an
existing `[application].main`). One-offs print a loud note that the mode is not persisted.
Fat and minified never share an action-cache key.

`jk assemble` without flags errors with a one-line fix if assembly is off.

## Fat jar merge rules

- Concatenate `META-INF/services/*`
- Concatenate `META-INF/spring.handlers`, `spring.schemas`, `spring.factories`, and Boot
  `AutoConfiguration.imports`
- Project classes win on path conflict; dependency jars earlier in the graph win among themselves

**Dropped:** signature files (`META-INF/*.SF` / `*.RSA` / `*.DSA` / `*.EC` / `SIG-*`) and
dependency `module-info.class` (JPMS descriptors break a single classpath jar).

## Minified jar (R8)

```toml
[application]
minified = true

[minified]
# keep = ["-keep class com.example.** { *; }"]
# keep-files = ["proguard-rules.pro"]
# obfuscate = false         # default
# strict-warnings = false   # default
```

JumpKick derives keep rules from by-name indexes (`META-INF/services/*` and
`META-INF/<vendor>/<interface>/<impl>` markers) and composes GraalVM metadata published
under `META-INF/native-image`. The effective rule set is written next to the artifact as
`<name>-keep.pro`.

Missing optional classes (Netty/Micronaut `Class.forName` probes) are **warnings** by
default (`strict-warnings = true` to fail). After shrink, an **audit** fails the build if
R8 removed a class still named by a service file or index in the jar.

**Limitations (why this stays opt-in):**

- Instantiation by name that appears in **no** index (Logback `logback.xml` appenders).
- Types whose **generic signature** is read at runtime. In R8 `--classfile` full mode, a
  class keeps its generic signature only if the class is explicitly kept. Apps that resolve
  types by runtime generic matching **cannot be shrunk** at any keep setting.
- A minified jar can be silently wrong. The fat jar is built beside it so you can compare.

Training (`jk train`) is **not** part of `jk build`. See [Dynamic surface](dynamic-surface.md).

## `jk install` (project)

Writes the thin jar and POM to the local repo (`repos/jk-local`), then prefers a native
binary in `~/.local/bin` if one exists, else a minified/fat jar under `$JK_HOME/lib/<name>/`
plus a `java -jar` script, else a thin `java -cp` script over the repo jars.

Plugin workers (`jk-plugin.toml`) are those same repo jars — the engine rebuilds their
runtime classpath from the installed POM. Outside a project, pass a coordinate (`jkx` mode).

## Related

[Build](build.md) · [Dynamic surface](dynamic-surface.md) · [Publish](publish.md)
