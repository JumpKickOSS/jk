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

Sibling **test** output (Maven test-jar / Mill `testModuleDeps`) — test scope only:

```toml
[test-dependencies]
widget-core = { workspace = true, kind = "tests" }
```

`kind = "tests"` is illegal outside `[test-dependencies]` / `[test-dev-dependencies]`.
Default kind is `main`. The same key works on published Maven coordinates (type `test-jar`,
classifier `tests`).

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

A member's platform BOMs — the root's `[platform-dependencies]`, its own, the BOM a framework
table such as `[spring-boot]` injects, and those of the siblings it depends on — constrain that
member's graph. They do not reach a member that never depends on it: `zipkin-server`'s
`spring-boot-dependencies` lifts `jakarta.jms-api` to 3.1.0 for `zipkin-server`, and the collector
that only depends on `activemq-client` compiles against the 2.0.3 that library declares, as it
does under Maven.

So a member is resolved on its own exactly when the workspace's answer cannot be its answer:

- it declares an exact version the workspace's row does not carry (`logback-classic = "1.2.13"`
  in one member, `"1.5.32"` in three others), or
- a coordinate in its graph was pinned by a BOM the member does not hold, and an edge of the
  member's own graph declared something else.

Its rows are then written beside the workspace's, each with `members = ["<path>"]`, and its
classpath reads those rows instead — [Lockfile](lockfile.md#rows-a-member-owns). Everything the
member agrees on stays a plain row. A workspace whose members all agree has no `members` key
anywhere, and nothing about it changes.

`jk why <coord>` lists every version the lock carries for the coordinate, each with the members it
belongs to; `jk lock` prints one line per coordinate it partitioned, naming the member, its version
and the workspace's; the same line reaches the results file and the web view. `jk update` rewrites
the declared pins and relocks, so a partition that stops being necessary disappears on its own.

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
