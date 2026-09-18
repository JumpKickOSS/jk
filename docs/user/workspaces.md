# Workspaces

A workspace is a multi-module repo with **one root `jk.toml`**, **one `jk-lock.toml`**,
and module output under **`target/<module-rel>/`** at the workspace root.

```toml
# root jk.toml
group   = "com.example"
name    = "root"
version = "1.2.3"

[workspace]
modules = ["libs/*", "services/*"]

[workspace.dependencies]
jackson2-databind = "2.22.2"                                  # catalog short name, exact
mylib             = "com.acme:mylib:1.2.3"                    # Maven coordinate, exact
```

```toml
# libs/core/jk.toml — minimal module
name = "core"
# group, version, java, jdk, … inherit from the workspace root
```

A `modules` entry is a path or a glob. `*` (also `?`, `[...]`) matches one path segment
and expands, sorted, to every matching directory that holds a `jk.toml`; `libs/*` picks up
a new module without a root edit. A glob that matches nothing is an error, and `**` is not
accepted. Literal entries keep their declared order; a module named twice counts once.

**`name` is always required** on a module. Other identity fields may be omitted and
resolve from the root. **`description` does not auto-inherit.** Override with a concrete
value (`java = 17`) or `version.workspace = true`. The root must keep concrete values for
fields members inherit.

Inheritance is resolved when the workspace loads members. Resolved values are frozen into
`jk-lock.toml` as `[[module]]` rows — re-lock after changing root or member identity.

Three tables are workspace facts and read from the root as well: `[publish]` applies to every
member that declares none, `[profiles.<name>]` merge by name with the member's winning, and the
`[image]` registry facts — `base`, `user`, `registry`, `tag`, `env`, `labels`, `platforms`,
`docker-executable`, `aot-cache` — sit under the member's own table (see
[Images § In a workspace](images.md#in-a-workspace)). `[manifest]`, `[application]`, `[native]`
and the dependency tables are each member's own and never inherit.

`jk new path/to/mod` and `jk add ./path` register modules for you.

## One name in two groups

Two members may carry the same `name` when their `group` differs, the shape Maven's reactor
allows (`org.thingsboard.common:edqs` under `common/edqs` and `org.thingsboard:edqs` under
`edqs`). Each member's output lives under its own `target/<module-rel>/`, so
`target/common/edqs/lib/edqs-4.4.0.jar` and `target/edqs/lib/edqs-4.4.0.jar` never meet. One
`group:name:version` declared by two members is a `workspace module collision`: a workspace
publishes one artifact per coordinate.

A bare workspace edge (`edqs.workspace = true`, or a `[build] order-after` entry) is spelled by
module name alone. An edge to a name two members carry names the member's group as well:

```toml
[dependencies]
edqs = { workspace = true, group = "org.thingsboard.common" }
```

The key is still the member's `name`; `group` picks which of the two, and `kind`, `optional` and
`fixtures` sit beside it as on any workspace edge. A bare edge to a shared name is refused as
``workspace edge `edqs` is ambiguous``, naming the member that depends on it, both members that
carry the name and the groups to choose from; a qualified edge whose group no member carrying the
name has is refused naming the groups that do. Two same-named members nothing depends on by name
build side by side. `jk import` writes the qualified form for every Maven dependency on a shared
artifactId, since the POM already named the group.

## Workspace dependencies

```toml
# services/api/jk.toml
[dependencies]
jackson-databind.workspace = true   # shared external from [workspace.dependencies]
widget-core.workspace = true        # sibling module (matches name)
```

A shared entry in the root's `[workspace.dependencies]` is a catalog short name with a version
(`jackson-databind = "2.18.2"`), a `group:artifact:version` coordinate, or an inline table with
`version` (plus `group` / `name` when the key is not a catalog name) or `git` (with `group`). An
inline table also takes `exclude`, the entry's own exclusions, which every member edge to it
carries beside the edge's own — one place to prune a transitive every member would otherwise
prune itself:

```toml
[workspace.dependencies]
guava = { group = "com.google.guava", version = "33.4.8-jre", exclude = ["com.google.guava:listenablefuture"] }
```

Sibling **test** output (Maven test-jar / Mill `testModuleDeps`) — test scope only:

```toml
[test-dependencies]
widget-core = { workspace = true, kind = "tests" }
```

`kind = "tests"` is illegal outside `[test-dependencies]` / `[test-dev-dependencies]`.
Default kind is `main`. The same key works on published Maven coordinates (type `test-jar`,
classifier `tests`).

A consumer inherits a sibling's `[dependencies]` and `[export-dependencies]` — its classes tree
on the compile classpath, its jar and those libraries at runtime — and never a sibling's
[`optional = true`](dependencies.md#optional-dependencies) entries, external or sibling: those are
the sibling's own, as a POM's `<optional>` dependencies are under Maven. A starter's optional
integrations stay out of every module that depends on the starter until that module declares them.

Sibling **fixtures** (Gradle `testFixtures`) are a directory, not a second artifact. The
producer declares `[test] fixtures = true` (default root `src/fixtures/java`); a consumer
opts in:

```toml
[test]
fixtures = true          # or fixtures = "src/fixtures/java"

[test-dependencies]
widget-core = { workspace = true, fixtures = true }
```

`fixtures = true` does not imply `kind = "tests"` and is illegal outside test scopes. The
output is `{target}/test-fixtures/classes/` — it never enters a POM.

## Members that disagree

One lock, one solve: the workspace's dependencies are resolved together, and a row of
`jk-lock.toml` is the version every member reads. Members that only *ask* differently still share
it — an edge that declares `1.0` where a sibling's declares `2.0` is a floor, and the workspace
takes `2.0` for both, as it would in one project.

A member's own coordinate is the member's everywhere. A dependency whose POM asks for a published
`group:artifact` the workspace builds — `hbase-server` depending on `org.apache.hadoop:hadoop-common`
inside the Hadoop reactor — is served by the member, as Maven's reactor stands in for a published
artifact of the same coordinate: the lock carries no row for it, nothing only that published POM
pulled is locked, and every classpath reads the member's output. The lock's notes name the first
POM that asked.

A member's platform BOMs — the root's `[platform-dependencies]`, its own, the BOM a framework
table such as `[spring-boot]` injects, and those of the siblings it depends on — constrain that
member's graph, and so do the [`[managed-dependencies]`](dependencies.md#managed-versions) entries
of the same manifests, folded in the same order. They do not reach a member that never depends on it: `zipkin-server`'s
`spring-boot-dependencies` lifts `jakarta.jms-api` to 3.1.0 for `zipkin-server`, and the collector
that only depends on `activemq-client` compiles against the 2.0.3 that library declares, as it
does under Maven. The table's BOM does govern the member that declares the table, even where that
member's POM imported no BOM and Maven took each library's declared version: a `[spring-boot]`
module resolves as a Boot application, on Boot's managed versions, and so does a member that
depends on it.

A BOM a framework table implies is a coordinate the lock fetches like any other: `[quarkus]
version = "3.39.2"` is `io.quarkus.platform:quarkus-bom:3.39.2`, and repositories publish it for
releases only. A workspace that builds the framework itself has no such release — the Quarkus
reactor's members run `quarkus-maven-plugin` at `999-SNAPSHOT`, and the BOM their tables would
imply exists nowhere — so `jk import` writes no `[quarkus]` table on a member whose version is one
the reactor builds `quarkus-bom` at, and its report says which members build as plain jars (the
same rule covers `[spring-boot]` and `spring-boot-dependencies`). A framework table written by hand
at such a version fails the lock with an error that names the table and the BOM it implies; a
missing BOM never fails the lock as a bare coordinate.

The workspace's rows are solved under the BOMs every member holds — the root's
`[platform-dependencies]` and any BOM every member declares or depends into — and under the
[`[managed-dependencies]`](dependencies.md#managed-versions) entries every member holds — the
root's, and an entry every member declares alike, module, version and `exclude` list — so a BOM or
entry only some members hold never moves a plain row. A member is resolved on its own exactly when
the workspace's answer cannot be its answer:

- it declares an exact version the workspace's row does not carry (`logback-classic = "1.2.13"`
  in one member, `"1.5.32"` in three others), or
- a BOM or `[managed-dependencies]` entry of its own table that not every member holds — its own,
  one a framework table implies, or a sibling's it depends on — manages a coordinate in its graph
  at a version the workspace's row does not carry (`zipkin-server`'s Boot BOM lifts `jakarta.jms-api` to 3.1.0 where the workspace's
  row is the 2.0.3 `activemq-client` declares), or
- a coordinate in its graph was pinned by a BOM the member does not hold — a versionless
  dependency (`group:artifact` alone) a sibling declares under such a BOM takes the BOM's version
  as the workspace's row, as an exact pin would (the first member's say, in `[workspace] modules`
  order, when two members each hold a BOM of their own that disagree on it: each member's table
  folds its own BOMs alone, so no `platform BOM conflict` is raised where no member holds both,
  and the later member reads a row of its own), while the BOM's `<exclusions>` on it reach the
  sibling alone through a row of its own — and the member's own platform table manages it
  at another version or a dependency's POM in the member's own graph declared a version the pinned
  one cannot stand in for: below the declaration, or past its compatible line (`^` of what the POM
  declared), or outside a range it wrote, or
- a BOM or `[managed-dependencies]` entry of its own table that not every member holds writes
  `exclude` patterns on a module in its graph — a BOM's `<exclusions>` on a root it manages, an
  entry's on every edge onto its module — that prune an edge the workspace's row carries, or the
  member's own root carries an `exclude` list the workspace's root of that coordinate lacks
  (`middle = { version = "1.0", exclude = ["com.foo:deep"] }` in one member, `middle = "1.0"` in
  another declared before it). The versions may agree; the member's row is the workspace's
  version without the pruned edges, anywhere under the root, as Maven applies an imported BOM's
  exclusions to the module that imports it and a dependency's exclusions to its whole subtree.

Its rows are then written beside the workspace's, each with `members = ["<path>"]`, and its
classpath reads those rows instead — [Lockfile](lockfile.md#rows-a-member-owns). Everything the
member agrees on stays a plain row; where the member's own BOM manages such a row at the
workspace's version, the plain row carries that BOM as `pinned-by`, as the member's own row would,
and `jk why` names it for every member that reads the row. Agreement is per scope: a version the workspace carries only as
a test row is not on any member's main classpath, so a member whose main graph wants that version
gets a main-scoped row of its own rather than reading the workspace's main row. A workspace whose members all agree has no `members` key
anywhere, and nothing about it changes.

A declaration a pinned workspace row does satisfy is a floor, as a sibling's higher edge is: where
a versionless root under a sibling's Boot BOM puts `commons-logging` 1.3.6 on the workspace's row,
it stands in for the 1.3.5 a `spring-context` module's graph declares, and the member reads the
workspace's row. A floating selector on the member's own root — `latest`, a
caret, the Jupiter the test runner adds to a member that declares no test dependencies — asks
the workspace for its answer and never disagrees with it; so a member that holds no platform table
and pins nothing exactly reads the plain rows alone. A `--features` name reaches a member's own
solve only where that member's `[features]` declares it; a name the member lacks is not its to
activate and is left out for it.

`jk why <coord>` lists every version the lock carries for the coordinate, each with the members it
belongs to. `jk lock` prints one note per member solved on its own, after the summary line, naming
the coordinates it reads its own rows for with the member's version and the workspace's
(`lib reads its own rows for 1 coordinate: com.foo:leaf 1.0 (workspace 2.0)`; a row at the
workspace's version that lacks edges its exclusions pruned reads `com.foo:leaf 1.0 (the
workspace's without com.foo:deep)`). A member whose own rows are every one pinned by a BOM or
entry of its own table — the shape a member-held BOM documents, its versions and its exclusions
alike — gets one sentence naming the count and the table instead (`plugins/quarkus reads its own
rows for 37 coordinates io.quarkus:quarkus-bootstrap-bom:3.38.0 manages`): the rows say what the
BOM says and name nothing to act on. The same notes land
in the run record — the `Lock notes` section of `jk-results.md` and its `details.jsonl` — and the
web view. While the pass runs, the progress label names the member being solved and how many
are flagged (`Solving members on their own… 2 of 7: services/api`): each flagged member costs a
solve of its own, which on a cold large reactor is where the lock's time goes. `jk update`
rewrites the declared pins and relocks, so a partition that stops being necessary disappears on
its own. The monorepo pack's converge-versions guard judges the workspace's rows and each member's
view of the lock on their own: a member's own row beside the workspace's is one classpath's version
and another's, not two versions on one classpath.

A member that holds no `[platform-dependencies]` table reads the workspace's plain rows, and
nothing aligns them for it. When two artifacts of one library family land there on different
lines for that member — `maven-resolver-api 2.0.22` beside `maven-resolver-transport-http 1.9.27`,
the shape the converge-versions guard holds per artifact and a member's own BOM would hold per
family — `jk lock` warns, member by member, naming the family and every artifact's version:
`plugins/tool has no [platform-dependencies] table and its rows mix
org.apache.maven.resolver:maven-resolver-* lines: maven-resolver-api 2.0.22, …`. A family is a
group and the first two hyphen-separated segments of the artifact name, so `maven-resolver-api`,
`maven-resolver-transport-http` and `maven-resolver-named-locks` are one and `commons-io` and
`commons-lang3` are not. Pin the family to one line in the member, or hold it under a platform BOM
there.

## One repository set

A member's `[repositories]` table joins the workspace's: the lock resolves every member against
the root's entries followed by each member's, in `[workspace] modules` order, keyed by id. An id
the root or an earlier member already declared at the same URL adds nothing; the entry that
declared it first keeps its credentials, exclusive groups and trust flags. The same id at two URLs
is refused by `jk lock`, naming both modules and both URLs — give the two repositories two ids, or
one URL. Two spellings of one origin are one URL: scheme and host case, an explicit default port
(`:443` on https) and the trailing slash do not count, and `jk import` writes the one normalized
form (`https://packages.confluent.io/maven/`).

## Nothing to build

A workspace that declares no modules, or whose every module has no source tree, no extra
source root and no build logic, fails `jk build` and `jk test` with exit 2 and a one-line
`built nothing` reason naming the modules. A green build that compiled nothing would read as
a passing build; jk does not report one. A module selection (`-m`) is judged by the selection
alone.

`jk test` has the sibling verdict: a workspace test run in which no module ran a test — none has
a test tree, or every suite is empty — fails with exit 2 and a one-line `no tests ran` reason
naming the modules, after every module has finished. A suite replayed from its green stamp counts
as run. `--skip-tests`, a `--class` selection (which has its own `no test classes matched`
verdict) and a plain project are not judged by it. [Test](test.md#an-empty-run-is-not-green).

## Select modules

From a **module directory**, `jk build` / `jk test` is that module plus upstream
prerequisites (same as `jk build -m <that-module>`).

```bash
jk build -m api,worker
jk test -m 'libs/*'
jk explain -m '{api,worker}'
jk build -m jk-engine
jk build -m :server:engine          # Gradle-style colon path

jk build --affected-since=origin/main
jk test --affected-since=origin/main  # ranked test classes (does not run)
jk build --affected                 # working-tree module cone (not a git ref)
jk test --affected                  # ranked test classes as a table (does not run)

# Intersection when both flags set
jk build -m 'libs/*' --affected-since=origin/main
```

`-m` / `--modules` and `--affected-since` work across the build family: `build`, `test`,
`explain`, `native`, `compile`, `image`, `show`, `tasks`, `inspect`.

`jk native` builds native-eligible modules plus their dependency closure. With tests on,
the cone includes test/dev workspace deps. `--skip-tests` uses production scopes only.

## Selective CI plan

```bash
jk selective prepare --since=origin/main
jk selective run test
jk selective resolve --modules 'api,worker'   # dry list
```

`prepare` records per-module content fingerprints (`jk.toml` + `src/**`). A later
`selective run` without `--force` / `--redo` skips modules that still match. Hashes are
content-based (not absolute paths). Generated / `target` trees are not fingerprinted.

**Caveat:** fingerprints are **not** transitive. An unchanged module can be skipped even
when an upstream sibling it depends on changed. Use a full `jk build` / `--force` when
the graph matters more than incremental CI savings.

Outside a git repo or with an invalid ref, jk prints a clear error. If nothing under the
workspace matched, it exits 0 with “nothing affected” / “nothing selected”.

## Related

[Projects](projects.md) · [Lockfile](lockfile.md) · [Build](build.md) · [Test](test.md)
