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
(`g:a:v:classifier`) is an error — use the inline table. `classifier` names the classified jar of
the module (`natives-linux`, `linux-x86_64`); the solver and the lock key that edge as
`group:artifact:jar:classifier`, so the plain jar and a classified twin are two entries under two
handles. It applies to a Maven coordinate only, and `kind = "tests"` already names the test-jar's
`tests` classifier, so the two do not combine.

Version syntax: [Projects](projects.md#version-strings). Scopes:
[Projects](projects.md#dependency-scopes).

### Optional dependencies

`optional = true` means what Maven's `<optional>true</optional>` means to whoever depends on you:
the dependency is yours alone. A workspace sibling that depends on the module does not inherit it
— not on its compile or runtime classpath, not in its `jk tree` closure, not in its fat jar — the
way a published POM's optional edges stay out of a consumer's graph, and the POM `jk publish` writes
marks it `<optional>`. What it means to the module itself depends on `[features]`: an optional
dependency no feature names is the module's own root, on its classpaths like any other; one a
feature names is off until that feature is active ([Projects](projects.md#features-profiles-variants)).
A consumer that wants the library declares it, or activates the feature.

### Exclusions

A dependency's inline table may prune coordinates from its own subtree — Maven's `<exclusions>`:

```toml
[dependencies]
schema-json = { group = "io.apicurio", name = "apicurio-registry-schema-util-json", version = "2.6.13.Final",
                exclude = ["io.apicurio:apicurio-common-app-components-logging", "com.github.everit-org.json-schema:*"] }
```

Each entry is `group:artifact`, `group:*` for every artifact of a group, `*:artifact` for an
artifact whatever its group, or `*:*` for the whole subtree — Maven's `<groupId>*</groupId>`
spellings. The key needs the inline table: a catalog one-liner or a GAV string has nowhere to carry it, so a catalog name with an
exclusion is written `jackson2-databind = { version = "2.22.2", exclude = ["…"] }` — the group and
artifact still come from the catalog.

The rule is Maven's. An exclusion on your edge to A removes, inside A's subtree, every edge to the
excluded coordinate and everything only reachable through it. The coordinate still lands in the
lock when another path brings it — your own entry for it, or another dependency's POM that does
not exclude it — because a coordinate is dropped only when *every* path that reaches it excludes
it. The `<exclusions>` a dependency POM writes on its own edges apply the same way, one level
down. A package you declare yourself expands under your edge's exclusions alone: a POM path that
excludes something from that package's subtree does not prune it, as a direct dependency is the
nearest edge under Maven.

With `[platform-dependencies]`: a versionless entry the BOM manages carries `exclude` like any
other — the BOM supplies the version, the table the exclusions. The `<exclusions>` a BOM's own
`<dependencyManagement>` writes on a module apply to an entry the BOM manages that writes no
`exclude` of its own, whether its version comes from the BOM or is written, as they do under Maven;
an entry with its own list keeps that list alone. A `git` or `path` source has no POM subtree and
refuses the key; a `workspace = true` edge carries its `exclude` to the coordinate the workspace
resolves it to, joined with the `exclude` the shared `[workspace.dependencies]` entry declares
([Workspaces](workspaces.md#workspace-dependencies)).

The lock row whose POM edge was pruned records it under `excluded-by`
([Lockfile](lockfile.md#what-an-exclusion-records)), and `jk why <coordinate>` prints
`<coordinate> is excluded under <row> (excluded by jk.toml:<handle>)` — or by the POM that declared
the exclusion — beside any path that still brings it.

Main, **test**, and **processor** graphs are solved **separately** so annotation-processor
constraints do not force main classpath versions.

**Classpath order.** A module's compile, test and run classpaths (javac, the test JVM, `jk run`,
the jars a fat jar or image embeds) list the module's own declarations first, in `jk.toml` order
(`[dependencies]`, then `[provided-dependencies]`, then the test tables), then their transitives
breadth-first through the lock graph, then — in a workspace — the remaining rows of the shared lock;
workspace siblings' classes trees and jars come after the lock rows. This is Maven's order: when two
jars carry the same package (a fork beside the library it forked), the jar the module declared is
the one javac and the JVM see first. The order is a compile input, so moving a declaration recompiles
the module.

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

The test classpath is the main classpath plus the test rows, so an exact pin in a main scope is the
version on it too, under both policies: a test dependency's edge onto the pinned module takes the
pin, the edge records what it asked for, and the lock carries one row with both scopes rather than
a test row above the pin. A test-scope pin of its own stays a test fact — it has no say on the main
classpath.

A dependency POM that asks for Maven's `LATEST` or `RELEASE` metaversion gets what Maven reads from
the repository's metadata: `RELEASE` is the newest release, `LATEST` the newest version of any
kind — a snapshot too, from a repository whose [snapshot policy](#snapshots) is on. Both float
within the solve only; the lock pins the number and the edge records `<- LATEST`, so `jk why`
explains it. `jk import` writes a direct `LATEST` or `RELEASE` as the `latest` selector with a row.

### Managed versions

`[managed-dependencies]` pins the version a module resolves to when only other libraries' POMs
bring it in — Maven's inline `<dependencyManagement>`, Gradle's `constraints`. An entry uses the
dependency grammar and must carry a version; it puts nothing on the classpath of its own.

```toml
[dependencies]
web = "org.springframework.boot:spring-boot-starter-web"

[managed-dependencies]
commons-io = "commons-io:commons-io:2.16.1"      # a CVE fix nothing here declares directly
snakeyaml  = { group = "org.yaml", name = "snakeyaml", version = "2.3" }
```

Every transitive edge onto a managed module takes the entry's version, the way a one-module BOM's
would, under both pin policies. The order on one module is Maven's: an exact version you declare
under `[dependencies]` beats the managed entry; the managed entry beats every
`[platform-dependencies]` BOM (as a POM's own `dependencyManagement` beats the BOMs it imports —
`jk lock` says which BOM gave way); the BOMs decide among themselves in declaration order
([Platforms](platforms.md#two-boms-that-manage-one-module)); a module none of them manage takes the
highest version the POMs that name it declare. The lock row records the entry as
`pinned-by = "jk.toml:<handle>"`, so `jk why` names it. In a workspace the root's table applies to
every member, then each member's own; a member's entry on a module another member pins exactly is
that member's [own row](workspaces.md#members-that-disagree).

An entry may carry `exclude` like any dependency, and it reaches further than an edge's: the
managed exclusions apply to *every* edge onto the module — a dependency POM's as much as your
own — so `hadoop = { group = "org.apache.hadoop", name = "hadoop-common", version = "3.4.1",
exclude = ["*:*"] }` leaves hadoop-common on the classpath bare wherever anything brings it in,
as Maven's `<dependencyManagement>` exclusions do. The lock row whose edge was pruned names the
entry under `excluded-by` (`jk.toml:hadoop`).

`jk import` writes a POM's inline pins that no declared dependency uses into this table, and a
reactor's parent pins once on the workspace root, so the imported project resolves a transitive the
parent forced to the version Maven built with; a managed entry's `<exclusions>` are written as the
row's `exclude`, whether or not a declared dependency uses its version, so the closure loses what
Maven's did. The report names the modules it wrote. A coexistence build of an unmodified `pom.xml`
reads the same table from its shadow manifest.

**Maven relocations are followed** (`distributionManagement/relocation`). The stub's one edge
carries the target's version as a floor, like any POM dependency, so a module whose line ended in
a relocation (`bcprov-ext-jdk18on` → `bcprov-jdk18on`) does not hold the target below what another
edge needs.

**Ranges in dependency POMs and Gradle module metadata** read in Maven's spelling (`[1.0,2.0)`,
`(,2.0]`, `[1.0]`) and in the ISO spelling Gradle publishes (`[1.0,2.0[` is exclusive above,
`]1.0,2.0]` exclusive below). A bracket that points at the version includes it; one that points
away excludes it.

**An exact pin on an old release** stays a candidate however long the module's history: the
solver's candidate window is the newest releases plus every version something asked for by
number, so `2.17.0` of a module with two hundred releases resolves when the project pins it.

### Repositories a dependency's POM declares

A published POM may carry `<repositories>` of its own — apicurio's parent names JitPack for
`com.github.everit-org.json-schema:org.everit.json.schema`. jk reads them the way Maven does,
with one rule:

- A repository a dependency POM declares (at the top level, in a parent, or in a profile Maven
  activates with nothing on the command line — `activeByDefault`, or a `<property><name>!x</name>`
  activation) is consulted **only for that POM's subtree**: the dependencies it declares and
  theirs, after every repository the project declares has missed. It never answers for your own
  declarations or for another dependency's subtree, and an [exclusive group](repositories.md#exclusive-groups-your-internals)
  bound to a declared repository stays bound.
- A row it serves records it in the lock's `source` (`jitpack.io+https://jitpack.io`), so
  `jk build` and `jk sync` fetch from it without a `[repositories]` entry, and `jk lock --sources`
  asks it for that row's `-sources.jar` too.
- It is held to the trust rule a project-declared repository meets by default: https and a
  published checksum for every artifact. A POM has no table to opt out with, so a plaintext
  `http://` repository it declares is not used, and an artifact it publishes no checksum for fails
  the lock naming the repository.
- Its `<releases>` and `<snapshots>` policies are read as Maven reads them: a repository declared
  with `<releases><enabled>false</enabled></releases>` is asked for `-SNAPSHOT` versions only, and
  one with snapshots disabled is never asked for a snapshot.
- `jk lock` notes a declared repository once a row of the lock came from it, naming the POM that
  introduced it and the policy it is asked under; a repository a POM declares that served nothing
  earns no note. A repository refused is always noted.
- `jk lock` says so: one note per repository names it, its URL and the POM that introduced it.
  Declaring the same URL under `[repositories]` makes it a project repository with the project's
  order and opt-ins.

### Snapshots

A `-SNAPSHOT` version is asked only of repositories whose snapshot policy is on — never of Maven
Central or the other built-in remotes, which host releases only. A `[repositories]` entry serves
snapshots unless it says `snapshots = false` ([Repositories](repositories.md#release-and-snapshot-policy)),
and a repository a POM declares follows the policy the POM wrote. A snapshot is a candidate only
when something asks for one by name — a `-SNAPSHOT` pin or a POM edge that names one — or through
the `snapshot` selector; a floating selector such as `latest` or `^6.1` never lands on a snapshot
a repository happens to advertise.

When a snapshot is pinned and no repository the dependency may resolve from serves snapshots, the
refusal says so, naming each repository asked and its policy:

```text
‼ Cannot resolve dependencies:
  │ No versions of org.questdb:questdb-client match 1.3.10-SNAPSHOT
  │   available: 1.3.9, 1.3.8, …
  │   1.3.10-SNAPSHOT is a snapshot, and no repository org.questdb:questdb-client may resolve from serves snapshots: central (releases only). Declare one under [repositories] …
```

### An exact version the first catalog does not list

Version discovery asks the repositories in order and stops at the first `maven-metadata.xml`
that lists a release — one read per dependency in the common case. When a version something asked
for by name (the project's pin, or the plain version a dependency's POM wrote) is missing from that
catalog, the remaining repositories are asked too and their catalogs unioned — the way Maven asks
every repository for an exact version. An artifact whose old releases sit on Central and whose
current ones sit on a vendor repository therefore resolves to the pinned version from the vendor
repository, with that repository as its lock `source`.

`jk import` hoists every reactor module's `<repositories>` onto the workspace root, since the
workspace lock resolves every member against the root's list.

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

A catalog file may also carry a **`[packages]`** table — package prefix → catalog name — for the
libraries whose group prefixes none of their packages: `com.google.common` is guava's, not
`com.google.guava`'s; Jackson 2 lives under `com.fasterxml.jackson.*` with a group ending in
`.core`. A build failing on `package X does not exist` reads it to name the coordinate to `jk add`
([the hint](machine-output.md#jk-results)): the longest prefix in the highest layer that has one wins,
and a row must name a `[libraries]` entry of the same file. The bundled table covers guava, gson,
Jackson 2 and 3 (databind, core, annotations), SLF4J, commons-lang3, commons-io, JUnit Jupiter,
AssertJ, Mockito, OkHttp, picocli and JSpecify; a project's `jk-libs.toml` adds its own.

```toml
# jk-libs.toml
[libraries]
acme-commons = "com.acme:commons"

[packages]
"org.acme.util" = "acme-commons"
```

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
