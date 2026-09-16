# Dependencies

Declare libraries in `jk.toml`. JumpKick resolves them with PubGrub, writes
[`jk-lock.toml`](lockfile.md), and never re-resolves on `jk build`.

```bash
jk add jackson3-databind          # catalog short name; writes today's stable as a pin
jk add com.acme:mylib:1.2.3       # Maven coordinate, exact version
jk add com.acme:mylib             # no version: today's stable, written as a number
jk add ./path/to/module           # workspace / path
jk remove jackson3-databind
```

`jk add` / `jk remove` edit `[dependencies]` (or another scope you pass). Catalog names
map to `group:artifact` only — versions live on the dependency or a BOM, never in the
catalog. See [Catalogs](#library-catalog) below and [Platforms](platforms.md). The version
`jk add` writes is a **pin**; move it later with `jk update` — [Lockfile](lockfile.md#jk-update).

A project with [guards](guards.md) may carry `depend` rules — banned coordinates, scopes a
library must stay in, version floors, licence and snapshot policy. `jk add` and `jk remove`
evaluate them before writing: an edit a rule bans is refused with the rule's card (`Instead`,
`Why`, the `allow` path), and the manifest is left as it was. The same rules run over the
manifest and the resolved lock in the model lane of every build.

## Coordinates

Three spellings, one grammar. The left-hand key is the local handle (`jk why`, `jk update
<name>`, `[features] deps`); it defaults to the artifact id and need not equal it.

```toml
[dependencies]
jackson2-databind = "2.22.2"                                 # catalog short name → exact 2.22.2
mylib    = "com.acme:mylib:1.2.3"                            # Maven coordinate → exact 1.2.3
web      = "org.springframework.boot:spring-boot-starter-web" # versionless: a BOM manages it
guava    = "com.google.guava:guava:^33.4"                    # selector in the third slot, opt-in
postgres = { group = "org.postgresql", name = "postgresql", version = "42.7.4", optional = true }
```

| Spelling | When |
|----------|------|
| `name = "1.2.3"` | The key is a [catalog](#library-catalog) short name |
| `name = "group:artifact:1.2.3"` | Any Maven coordinate; `group:artifact` alone is platform-managed |
| `name = { group, name, version, … }` | Extra fields: `optional`, `features`, `classifier`, `kind`, `git`, `path`, `sha256` |

`jk add` picks the spelling for you in that order: catalog hit → GAV string → inline table.
`jk format` never rewrites one spelling into another. A classifier or type in a GAV string
(`g:a:v:classifier`) is an error — use the inline table.

Version syntax: [Projects](projects.md#version-strings). Scopes:
[Projects](projects.md#dependency-scopes).

Main, **test**, and **processor** graphs are solved **separately** so annotation-processor
constraints do not force main classpath versions.

A dependency that only transitive POMs name resolves to the **highest version any of those
POMs declares** (Gradle's rule, not Maven nearest-wins), never to a newer release the
repository happens to advertise; only your own opt-in selectors (`^`, `~`, `latest`, ranges)
reach for the newest release in range — a bare version is a pin. Version order is Maven's, so
an unknown qualifier such as `2.0.1.MR` counts as newer than `2.0.1` when a selector floats —
a POM that declares `2.0.1` still gets `2.0.1`. Conflicts get PubGrub prose. With a BOM:
[Platforms](platforms.md).

Your own exact pin is one constraint among the transitives' by default: a pin below a floor some
POM declares is a conflict, explained. `[resolve] pins = "nearest"` makes the pin the version
instead, as a direct dependency's is under Maven's nearest-wins — the transitive's range on that
module is recorded on the lock edge (`<- 2.0.1.MR`) and reported as a warning, not enforced.
`jk import` writes that line for a Maven POM so the imported project resolves as Maven resolved it;
a transitive with no pin on it keeps the highest-declared rule either way.

**Maven relocations are followed** (`distributionManagement/relocation`).

### Classifiers that follow the host

Some POMs spell a platform artifact's classifier with a property a Maven build values from the
machine: OpenJFX's `${javafx.platform}` (an OS-activated profile in its parent) and
os-maven-plugin's `${os.detected.classifier}`, `${os.detected.name}` and `${os.detected.arch}`.
jk values those from the running host — `linux`, `linux-aarch64`, `mac`, `mac-aarch64`, `win`
for OpenJFX; `linux-x86_64`, `osx-aarch_64`, `windows-x86_64`, … for os-maven-plugin — in the
effective model of every POM it reads and in the model `jk import` reads, so `javafx-graphics`
resolves to this machine's `javafx-graphics-25.0.3-linux.jar`. A POM that defines the property
itself keeps its own value. The lock pins the artifact of the host that ran `jk lock` and says
so in a note naming the edge and the expression; a lock made on another platform pins that
platform's artifact.

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

The lock pins the resolved git SHA. Tag moves fail loudly until `jk update --git`.

Workspace siblings: [Workspaces](workspaces.md).

## Inspect

```bash
jk tree                  # workspace graph (even from a member dir)
jk tree :foo             # one module
jk tree -t               # include transitives
jk why com.foo:bar       # why a pin is on the graph, and what each step declared for the next
```

`jk why` prints one path per declared root, each step as `coordinate (declared <selector> by
<parent>)`, so a surprising transitive version is traced to the declaration that produced it.
The selector rides the lock's edge lines; see [Lockfile](lockfile.md#what-an-edge-records).

## Related

[Lockfile](lockfile.md) · [Repositories](repositories.md) · [Publish](publish.md) (`jk audit` / `jk deny`)
