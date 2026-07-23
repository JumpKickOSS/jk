# Authoring build plugins

A build plugin teaches jk a new `jk.toml` table — `[spring-boot]`, `[android]`, `[protobuf]` —
and shapes the standard commands around it. First-party examples live under
[`plugins/`](../plugins/) (start with [`plugins/spring-boot`](../plugins/spring-boot)).

**Who this is for (pre-1.0):** **first-party** plugins in this monorepo, and **private/
vendored** plugin jars (path or Maven pin + required `sha256`). A public third-party
authoring path is **deferred until ~1.0** when the plugin SPI freezes — `jk-plugin-sdk` is
**not** published to Maven Central yet. Do not plan on consuming a released SDK coordinate
from outside this tree until that lands.

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

Compile against the in-tree **`plugin-sdk`** module (`shared/plugin-sdk`, artifact name
`jk-plugin-sdk` when published later). Keep the worker at a JDK floor compatible with user
projects (first-party workers target `--release 17` where they ride the user’s JVM).

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

| Path | Status |
|---|---|
| **First-party** plugins under `plugins/` | Ship inside the jk dist; version with jk |
| **Private / vendored** jars (`[plugins]` + `sha256`) | Supported now — see below |
| **Public third-party** SDK on Maven | **Not available** until ~1.0 SPI freeze (JK-1074) |
| **Plugin marketplace / registry** | Intentionally deferred (product anti-goal pre-freeze) |

### Private plugins (path or Maven pin)

Enterprises can vendor a plugin jar without a registry. Declare it under `[plugins]` with a
**required** content pin (`sha256`). Unpinned plugins are refused at parse time.

```toml
# Path pin (air-gapped / monorepo vendor dir) — path relative to the project jk.toml
[plugins]
acme-rules = { path = "vendor/acme-rules-1.0.0.jar",
               sha256 = "…" }   # 64 hex chars; optional sha256: prefix

# Maven coordinate pin (fetch at lock; must match the pin)
[plugins]
acme-rules = { group = "com.acme", name = "acme-rules", version = "1.0.0",
               sha256 = "…" }
# or: coordinate = "com.acme:acme-rules:1.0.0", sha256 = "…"
```

**Trust model (fail closed)**

| Situation | Behavior |
|---|---|
| Missing `sha256` | Parse error |
| Path pin, file hash ≠ declared | `jk lock` error naming both digests |
| Coord pin, resolved jar ≠ declared | `jk lock` error naming both digests |
| Worker code not trusted | Fork refused (`jk trust plugin <group:name>`) — manifest contributions still apply |

**Packaging checklist** (mirror `plugins/spring-boot/`):

1. Jar root must contain `jk-plugin.toml` (`[plugin]` id/table/version + `[schema]` + optional
   `[[contribute.*]]` + optional `[code]` for a worker main).
2. Compile against in-tree `plugin-sdk` (or a future published `jk-plugin-sdk` after ~1.0);
   never require engine classes on the plugin classpath.
3. Pin: `sha256sum vendor/your-plugin.jar` → paste into `sha256`.
4. `jk lock` materializes the manifest under `target/plugin-manifests/<sha>.jk-plugin.toml`.
5. Code layer: trust once, then worker forks use the locked CAS jar (action keys include jar hash).

Private plugins **error** if they claim a table or id already owned by a built-in plugin.

## Further reading

- Blueprint plugin: `plugins/spring-boot/`
- SPI sources: `shared/plugin-sdk/`
- Architecture context: [architecture.md](architecture.md)
