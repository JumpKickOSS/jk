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
jackson = "2.18.2"          # bare version means caret: ^2.18.2

[test-dependencies]
junit = "5.11.0"
```

## Identity and language

| Field | Meaning |
|-------|---------|
| `group` | Maven groupId |
| `name` | Artifact id (required; workspace members may omit other identity fields) |
| `version` | Project version |
| `java` | Language + bytecode (`--release`). Default **25**. Prefer this over `jdk` — [Concepts](concepts.md) |
| `jdk` | Specific JDK *install* (rare) |
| `kotlin` | Kotlin compiler version (Kotlin modules) |
| `groovy` | Groovy compiler version — **5+** (Groovy modules) |
| `scala` | Scala 3 compiler version (Scala 3 only; mixed Java+Scala compile in one Zinc session). jk injects the matching stdlib (`scala-library`; on 3.8+ that jar *is* the Scala 3 library) |
| `description` | Optional; does **not** auto-inherit in workspaces unless you set it or `description.workspace = true` |
| `m2integration` | Use the Maven local repository as the primary third-party jar store (default **true**). `false` hosts those jars only under `JK_STORE_DIR/repos/<name>/`. First-party workers always stay in `repos/local`. |

**Language mix:** Java is the default. A module may mix Java with Kotlin, Groovy, **or**
Scala 3 (Java↔Scala circular refs compile in one Zinc session). **Kotlin + Groovy in one
module is rejected.** Scaffold with `jk new --lang kotlin`, `--lang groovy`, or `--lang scala`.

## Version strings

| Written | Means |
|---------|--------|
| `"1.2.3"` | Caret: `^1.2.3` |
| `"=1.2.3"` | Exact |
| `"~1.2.3"` | Patch-only |
| `">=1.2,<2"` | Range |
| `"latest"` | Newest stable at lock time |

## Dependency scopes

| Table | Typical use |
|-------|-------------|
| `[dependencies]` | Main compile/runtime |
| `[test-dependencies]` | Tests only |
| `[provided-dependencies]` | Compile, not packaged |
| `[runtime-dependencies]` | Runtime only |
| `[processor-dependencies]` | Annotation processors (own resolve graph) |
| `[platform-dependencies]` | BOMs — [Platforms](platforms.md) |
| `[export-dependencies]` | Published API surface |
| `[dev-dependencies]` / `[test-dev-dependencies]` | Optional extra scopes |

Git, path, workspace, optional features: [Dependencies](dependencies.md).

## Application

```toml
[application]
main     = "com.example.App"   # required to `jk run` / fat jar
assembly = true                # also write `-all.jar`
# minified = true              # also write `-min.jar` via R8
# native   = true              # native-image on jk build / jk install
```

Packaging matrix: [Packaging](packaging.md).

## Features, profiles, variants

These are **deliberately separate**:

| Knob | Changes |
|------|---------|
| **Features** | *What* optional deps you have (`[features]`, `optional = true`) |
| **Profiles** | *How* you compile (flags, JVM args, tag filters). `--profile` / auto `ci` |
| **Variants** | *Which product* you build (sources, deps, plugin config). `--variant` / `--release` |

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
jk add g:a:v
jk remove <coord>
```

`jk new` without `-t` is the wizard / flag scaffolder (library or `--executable` app).
`--template` cannot be combined with `--plugin`.

## Related

[Lockfile](lockfile.md) · [Config](config.md) · [Commands](commands.md)
