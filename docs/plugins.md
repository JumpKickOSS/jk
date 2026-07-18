# Authoring build plugins

A build plugin teaches jk a new `jk.toml` table — `[spring-boot]`, `[android]`, `[protobuf]` —
and shapes the standard commands around it. First-party examples live under
[`plugins/`](../plugins/) (start with [`plugins/spring-boot`](../plugins/spring-boot)).

**The bar:** you declare *what*; jk owns *when* (ordering) and *whether it can be skipped*
(caching / action keys). You do not hand-manage the content-addressed store.

## Anatomy

One jar containing:

```
jk-plugin.toml          # declarative layer (required)
scaffold/…              # optional templates for jk new / import
<your classes>          # optional code layer (forked process only)
```

- **Declarative layer** — jk parses `jk-plugin.toml` (data; safe even for untrusted plugins)
  and applies contributions itself. Zero plugin code in the engine JVM.
- **Code layer** — runs in a **forked worker** over a JSONL protocol. The engine never
  classloads your classes.

Compile against **`plugin-sdk`** (`jk-plugin-sdk`). Keep the worker at a JDK floor compatible
with user projects (first-party workers target `--release 17` where they ride the user’s JVM).

## Manifest essentials

### Identity and schema

```toml
[plugin]
id        = "spring-boot"
table     = "spring-boot"     # jk.toml table you own
version   = "1.0.0"
jk-compat = ">=0.10"

[schema]
version = { type = "string", required = true, example = "4.0.1",
            hint = "the Spring Boot release to build against" }
aot     = { type = "bool" }   # no default = tri-state
```

Schema types: `string`, `bool`, `int`, `string-list`. Missing `required` keys fail parse with
your `example` / `hint`.

### Contributions

```toml
[[contribute.platform-dependency]]
coordinate = "org.springframework.boot:spring-boot-dependencies:${config.version}"

[[contribute.compiler-args]]
javac  = ["-parameters"]
kotlin = ["-java-parameters"]
ksp    = ["room.schemaLocation=…"]

[[contribute.kotlin-plugin]]
id         = "org.jetbrains.kotlin.noarg"
coordinate = "org.jetbrains.kotlin:kotlin-noarg-compiler-plugin-embeddable:${kotlin.version}"
options    = ["preset=jpa"]
when       = { classpath-has = "jakarta.persistence:jakarta.persistence-api" }
```

**Interpolation (closed set):** `${config.<key>}`, `${kotlin.version}`,
`${project.group|name|version}`, `${host.os}`, `${host.os-arch}`.

**Conditions (closed set, one per `when`):** `classpath-has`, `config`/`equals`,
`native-declared`, `kotlin-project`. Richer logic belongs in code.

### Packaging

```toml
[packaging]
packager       = "boot-jar"   # code packager id
exec-mode      = "jar"        # jar | classpath | binary
self-contained = true
classes-run    = true
main-scan      = true
layered-image  = true
```

Static data consulted by `jk run` / `install` / `image` without forking your code.

### Scaffold and import

```toml
[scaffold]
flag = "spring"               # jk new --spring

[[import.gradle-plugin]]
id         = "org.springframework.boot"
version-to = "version"
```

### Code layer

```toml
[code]
main = "cc.example.MyPluginMain"   # implements the plugin-sdk entry
```

Workers speak the same JSONL style as compiler plugins. Prefer the harness in `plugin-sdk`
(`BuildPlugin`, step/packager SPIs) over hand-rolled protocols.

## Steps and transforms

Plugins contribute **steps** into the build pipeline (codegen before compile, class transforms
after compile, custom packagers). Important SPI notes:

- **`transformsClasses`** — at most one classes transform per build (e.g. Hilt weaving); runs
  between compile and package and replaces the classes dir for downstream steps.
- **Action keys include plugin worker jar hashes** — upgrading the plugin invalidates cache.
- Steps declare inputs/outputs so incrementality and `jk explain` stay correct.

## Testing

- Unit-test pure logic in the plugin module.
- Integration-test through a small fixture project with `jk lock` / `jk build` (see first-party
  engine tests for patterns).
- Never require the engine to load your classes on its classpath.

## Distribution (today)

First-party plugins ship inside the jk distribution and version with jk. A public marketplace
is intentionally deferred. Private / vendor jars (path or coord + content hash) are on the
project board for enterprise use before any registry.

## Further reading

- Blueprint plugin: `plugins/spring-boot/`
- SPI sources: `shared/plugin-sdk/`
- Architecture context: [architecture.md](architecture.md)
