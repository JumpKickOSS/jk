# Projects and `jk.toml`

A JumpKick project is a directory with a **`jk.toml`**. TOML is data: no embedded scripts.
`jk add` / `jk remove` edit this file for you.

Create one: [Getting started](getting-started.md), [Templates](templates.md).
Source trees: [Layout](layout.md). Multi-module: [Workspaces](workspaces.md).

## Minimal manifest

```toml
group   = "com.example"
name    = "my-app"
version = "0.1.0"
java    = 25

[dependencies]
jackson2-databind = "2.22.2"     # catalog short name; the version is an exact pin

[test-dependencies]
junit-jupiter = "6.1.3"
```

### Editor support

`docs/user/jk.toml.schema.json` is a JSON Schema of the manifest vocabulary — the identity and
language keys, every core table, the dependency scopes, and the `[application]` keys. It is
generated from nothing: the parser's own key sets are the truth and a test fails when the two
disagree. Associate it with `jk.toml` and a stock editor completes table names and flags an unknown
`[application]` key:

- **IntelliJ IDEA** — Settings → Languages & Frameworks → Schemas and DTDs → JSON Schema Mappings:
  add the schema file and map the file name `jk.toml` to it (TOML support comes with the bundled
  TOML plugin).
- **VS Code** with Even Better TOML — put `#:schema ./docs/user/jk.toml.schema.json` on the first
  line of the manifest, or add the mapping under `evenBetterToml.schema.associations`.

Plugin-owned tables (`[micronaut]`, `[android]`, …) are allowed by the schema and documented by
their plugins.

## Identity and language

| Field | Meaning |
|-------|---------|
| `group` | Maven groupId |
| `name` | Artifact id (required; workspace members may omit other identity fields) |
| `version` | Project version |
| `java` | Language + bytecode (`--release`). Default **25**. Prefer this over `jdk` — [Concepts](concepts.md). jk's floor is **17**: a lower level down to 8 still parses and the toolchain JDK cross-compiles for it, with one warning per module — the place for it is a module whose sources or dependencies need an API a later JDK removed (`java.security.acl`, Pack200, Nashorn, RMI activation); a compile for a newer release that misses one names the API and the level to write. Such a module compiles without incremental analysis when a library supertype's signature names the removed type — the analysis reflects over supertypes with the compiler worker's JDK — so a warning says it is compiled in full every build |
| `jdk` | Specific JDK *install* (rare) |
| `kotlin` | Kotlin compiler version (Kotlin modules). jk's floor is **2.4.10**, the oldest Kotlin its compile path drives: a version below it compiles with the floor — the lock pins `2.4.10` and notes it, the build warns once per module — and `jk import` writes the floor with a row. The stdlib family (`kotlin-stdlib*`, `kotlin-reflect`, `kotlin-test*`) is the compiler's: a platform BOM or a declared root that holds it at another version follows the compiler in the lock, with a note, so one stdlib reaches the compile classpath |
| `groovy` | Groovy compiler version (Groovy modules). jk's floor is **5.0**, the oldest line its compiler worker drives: a version below it compiles and runs with jk's bundled Groovy (`5.0.4`) — the lock carries that runtime and notes it, the build warns once per module |
| `scala` | Scala 3 compiler version (Scala 3 only; mixed Java+Scala compile in one Zinc session). jk injects the stdlib pinned to the resolved compiler version — `"3.8.4"` holds both, an opt-in `"^3.8"` moves compiler and library together (`scala-library`; on 3.8+ that jar *is* the Scala 3 library). jk's floor is **3.0**: a Scala 2 version compiles with jk's bundled Scala 3 (`3.8.4`) and its library — the lock pins that compiler and notes it, the build warns once per module |
| `description` | Optional; does **not** auto-inherit in workspaces unless you set it or `description.workspace = true` |
| `sources` | Sources jar: a library (no `[application]`) builds it always; `true` = `jk publish` assembles it for an application; `"always"` = `jk build` writes it for any module — [Packaging](packaging.md#library-artefacts-sources-and-javadoc-jars) |
| `javadoc` | Javadoc jar for a library: default lenient (doclint off; javadoc's warnings and errors are warnings in the results, the step never fails); `"strict"` fails on javadoc errors; `false` skips it |
| `[m2] integration` | Read the Maven local repository as a source for third-party jars and write fetched ones through to it (default **true**). A build reads only the store's copy under `JK_STORE_DIR/repos/<origin-id>/`; `false` leaves the local repository alone (one tree per repository origin — [Repositories](repositories.md#store-layout-one-tree-per-origin)). First-party workers always stay in `repos/jk-local`. Machine override: `JK_M2_INTEGRATION=false` or user-config `[m2] integration = false`. |
| `[m2] install` | Write `jk install` artifacts into the Maven local repository (default **true**). Independent of `integration`: `[m2] install = false` keeps `jk install` under `repos/jk-local` even when third-party jars still come from `~/.m2`. Machine override: `JK_M2_INSTALL=false` or user-config `[m2] install = false`. |

**Language mix:** Java is the default. A module may mix Java with Kotlin, Groovy, **or**
Scala 3 (Java↔Scala circular refs compile in one Zinc session). **Kotlin + Groovy in one
module is rejected.** Scaffold with `jk new --lang kotlin`, `--lang groovy`, or `--lang scala`.
With no language key at all the languages are inferred from the tree; once one is declared, the
declaration is the whole set — a `kotlin =` manifest over `src/main/java` compiles no Java, and the
build warns once per module, naming the root and the key to add (`src/main/java holds Java sources
this module does not compile: jk.toml declares kotlin and not java — add java = 25 to compile them`).

## Version strings

One grammar, every place `jk.toml` names a version: every dependency scope,
`[workspace.dependencies]`, plugin `version` keys (`[spring-boot] version`, …), the `kotlin` /
`groovy` / `scala` compiler keys, and `[native] metadata-repository`.

| Written | Means |
|---------|--------|
| `"1.2.3"` | Exact `1.2.3` |
| `"=1.2.3"` | Exact `1.2.3` (same type; writers emit the bare form) |
| `"^1.2.3"` | Caret, opt-in |
| `"~1.2.3"` | Tilde, opt-in |
| `">=1.2,<2"` | Range, opt-in |
| `"latest"` | Newest stable at the next resolve, opt-in; do not commit in scaffolds |
| `"g:a:1.2.3"` | Maven GAV, exact `1.2.3` |
| `"g:a:^1.2.3"` / `"g:a:latest"` | GAV with an explicit selector in the third slot |
| `"g:a"` | Versionless / platform-managed |
| `"managed"` | Platform-managed, on a catalog short name |

A bare version is a pin: `jk build` and `jk lock` never move it. Floating is always spelled
out with a decoration or a keyword. Maven's `LATEST` and `RELEASE` metaversions are not part of
this grammar — `jk import` writes them as `latest` — but a dependency's POM may still use them,
and the resolver reads them as Maven does ([Dependencies](dependencies.md#coordinates)). `jk add` and `jk new` write today's stable as a number, and
`jk update` rewrites those numbers — [Lockfile](lockfile.md#jk-update). A `^N` major-line
floor is the floating form of a framework version (`[spring-boot] version = "^4"`).

## Dependency scopes

| Table | Typical use |
|-------|-------------|
| `[dependencies]` | Main compile/runtime |
| `[test-dependencies]` | Tests only |
| `[provided-dependencies]` | Compile, not packaged |
| `[runtime-dependencies]` | Runtime only |
| `[processor-dependencies]` | Annotation processors and javac plugins (own resolve graph); absent, processors registered on the compile classpath run — [Annotation processors](build.md#annotation-processors) |
| `[test-processor-dependencies]` | Processors compile-test alone runs, beside `[processor-dependencies]`; compile-main never sees them |
| `[platform-dependencies]` | BOMs — [Platforms](platforms.md) |
| `[managed-dependencies]` | Versions for modules only transitive POMs bring in (Maven's inline `dependencyManagement`) — [Managed versions](dependencies.md#managed-versions) |
| `[plugin-dependencies]` | Refused in `jk.toml`. The `plugin` scope is the lock's own: `jk lock` writes a pinned third-party plugin's SDK floor (`jk-plugin-sdk`, `jk-host`) into `jk-lock.toml` under it — [Using plugins](plugins.md#third-party-and-vendored-plugins) |
| `[export-dependencies]` | Published API surface |
| `[dev-dependencies]` / `[test-dev-dependencies]` | Optional extra scopes |

Git, path, workspace, optional features: [Dependencies](dependencies.md).

## javac plugins and args

```toml
[javac]
plugins = { ErrorProne = { options = ["-Xep:NullAway:ERROR"] } }
args    = ["-Xlint:all"]        # verbatim javac args, appended last
```

The plugin's jar is a `[processor-dependencies]` entry; the key is its javac name. Both compile
steps run it and the compile key hashes it — [Build](build.md#javac-plugins).

## Audit ignores

```toml
[audit]
ignore = [
  { id = "GHSA-xxxx-xxxx-xxxx", reason = "test-only dependency", until = "2026-12-31" },
]
```

Advisories `jk audit` reports but leaves out of its exit status; `reason` is required, `until` is
optional. Lives in the manifest beside `jk-lock.toml` — [Publish](publish.md#accepting-a-finding).

## Application

```toml
[application]
main     = "com.example.App"   # required to `jk run` / fat jar
assembly = true                # also write `-all.jar`
# minified = true              # also write `-min.jar` via R8
# native   = true              # native-image on jk build / jk install
```

Absent `[application]` means **library**: `jk build` also writes the `-sources.jar` and
`-javadoc.jar` Maven Central requires. Packaging matrix: [Packaging](packaging.md).

## Features, profiles, variants

These are **deliberately separate**:

| Knob | Changes |
|------|---------|
| **Features** | *Which* optional deps are on (`[features]` naming `optional = true` entries; an optional dep no feature names is simply yours, never a consumer's — [Dependencies](dependencies.md#optional-dependencies)) |
| **Profiles** | *How* you compile and test (`javac` flags, test-JVM args, tag filters). `--profile` / auto `ci` |
| **Variants** | *Which product* you build (sources, deps, plugin config). `--variant` / `--release` |

```toml
[profiles.strict]
javac = ["-Werror"]                 # appended to the compiler argv

[profiles.probe]
inherits = "strict"                 # parent first, then this table
jvm-args = ["-Dprobe=1", "-Xmx1g"]  # every forked test JVM (jk test, and the test step of jk build)
exclude-tags = ["slow"]             # replaces the [test] list when present
```

`jvm-args` are a test input: `--profile probe` re-runs a suite a plain run left green, and `jk
explain` keys the step the same way. They do not reach `jk run` — an application's JVM flags are
its own, not a profile's. Tag precedence and the `ci` auto-profile: [Test](test.md#tag-filters).

```toml
[dependencies]
postgres = { group = "org.postgresql", name = "postgresql", version = "42.7.4", optional = true }

[features]
default = ["postgres"]
[features.postgres]
deps = ["postgres"]
```

## Commands that create or edit projects

```bash
jk new my-app
jk init
jk add jackson3-databind          # catalog short name; writes today's stable
jk add com.acme:mylib:1.2.3       # exact GAV
jk add com.acme:mylib             # GAV without a version: today's stable
jk remove <coord>
```

`jk new` without `-t` is the wizard / flag scaffolder (library or `--executable` app).
`--template` cannot be combined with `--plugin`. Every language it scaffolds — Java, Kotlin, Groovy,
Scala — gets its level as `java = N` and no toolchain pin: the host JDK compiles to the level. A
`jdk = "…"` line is written only for an explicit `--jdk` (`--jdk corretto-25`, `--jdk 21`), the one
case where a particular install is being asked for.

## Related

[Lockfile](lockfile.md) · [Config](config.md) · [Commands](commands.md)
