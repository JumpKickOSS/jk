# Dependencies

Declare libraries in `jk.toml`. JumpKick resolves them with PubGrub, writes
[`jk-lock.toml`](lockfile.md), and never re-resolves on `jk build`.

```bash
jk add jackson3-databind          # catalog short name
jk add com.google.guava:guava:33.4.8
jk add ./path/to/module           # workspace / path
jk remove jackson3-databind
```

`jk add` / `jk remove` edit `[dependencies]` (or another scope you pass). Catalog names
map to `group:artifact` only — versions live on the dependency or a BOM, never in the
catalog. See [Catalogs](#library-catalog) below and [Platforms](platforms.md).

A project with [guards](guards.md) may carry `depend` rules — banned coordinates, scopes a
library must stay in, version floors, licence and snapshot policy. `jk add` and `jk remove`
evaluate them before writing: an edit a rule bans is refused with the rule's card (`Instead`,
`Why`, the `allow` path), and the manifest is left as it was. The same rules run over the
manifest and the resolved lock in the model lane of every build.

## Coordinates

```toml
[dependencies]
jackson = "2.18.2"                                          # short name or bare artifact
guava   = { group = "com.google.guava", name = "guava", version = "33.4.8" }
postgres = { group = "org.postgresql", name = "postgresql", version = "42.7.4", optional = true }
```

Version syntax: [Projects](projects.md#version-strings). Scopes:
[Projects](projects.md#dependency-scopes).

Main, **test**, and **processor** graphs are solved **separately** so annotation-processor
constraints do not force main classpath versions.

A dependency that only transitive POMs name resolves to the **highest version any of those
POMs declares** (Gradle's rule, not Maven nearest-wins), never to a newer release the
repository happens to advertise; only your own floating selectors (`1.2`, `^`, `~`, `latest`,
ranges) reach for the newest release in range. Version order is Maven's, so an unknown
qualifier such as `2.0.1.MR` counts as newer than `2.0.1` when a selector floats — a POM
that declares `2.0.1` still gets `2.0.1`. Conflicts get PubGrub prose. With a BOM:
[Platforms](platforms.md).

**Maven relocations are followed** (`distributionManagement/relocation`).

## Library catalog

Short names resolve through layered maps **name → `group:artifact`** (no versions):

| Layer | Source | Wins |
|-------|--------|------|
| **project** | `jk-libs.toml` at the workspace (or standalone) root | first |
| **global** | downloaded registry (`jk library update`; quiet 12h revalidation) | then |
| **bundled** | shipped with the binary | offline floor |

There is **no** host-local catalog file and **no** `catalog =` pin in `jk.toml`. Modules
must not ship `jk-libs.toml`.

```toml
# jk-libs.toml (workspace root only)
[libraries]
internal-core = "com.acme:core"
```

Major coordinate forks get distinct names when curated (`jackson2-*` / `jackson3-*`).

```bash
jk library search jackson
jk library list
jk library update          # refresh the global registry
```

**Starters** are real Maven artifacts (`spring-boot-starter-web`, Quarkus extensions),
usually versionless under a BOM — not catalog bundles. [Platforms](platforms.md),
[Frameworks](frameworks.md).

## Git and path

```toml
[dependencies]
mylib = { git = "https://github.com/acme/mylib", tag = "v1.4.0" }
# or: branch / rev; path = "subdir" inside the repo
local = { path = "../sibling" }   # local project with its own jk.toml
```

The lock pins the resolved git SHA. Tag moves fail loudly until `jk update`.

Workspace siblings: [Workspaces](workspaces.md).

## Inspect

```bash
jk tree                  # workspace graph (even from a member dir)
jk tree :foo             # one module
jk tree -t               # include transitives
jk why com.foo:bar       # why a pin is on the graph
```

## Related

[Lockfile](lockfile.md) · [Repositories](repositories.md) · [Publish](publish.md) (`jk audit` / `jk deny`)
