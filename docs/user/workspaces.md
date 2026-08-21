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
jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.18.2" }
```

```toml
# libs/core/jk.toml — minimal module
name = "core"
# group, version, java, jdk, … inherit from the workspace root
```

**`name` is always required** on a module. Other identity fields may be omitted and
resolve from the root. **`description` does not auto-inherit.** Override with a concrete
value (`java = 17`) or `version.workspace = true`. The root must keep concrete values for
fields members inherit.

Inheritance is resolved when the workspace loads members. Resolved values are frozen into
`jk-lock.toml` as `[[module]]` rows — re-lock after changing root or member identity.

`jk new path/to/mod` and `jk add ./path` register modules for you.

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
jk test --affected-since=origin/main

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
