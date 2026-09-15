# Workspace showcase

A minimal **multi-module workspace**: one root `jk.toml` that lists its members, one
`jk-lock.toml` for the whole tree, and an `app` module that depends on its sibling `lib`.

```text
workspace-showcase/
  jk.toml        # [workspace] modules, and the one JUnit version
  jk-lock.toml   # one lockfile for every module, at the workspace root
  lib/jk.toml    # name only — group, version and java inherit from the root
  app/jk.toml    # lib.workspace = true
```

Build output lands under `target/<module>/` at the workspace root. Modules have no
`target/` of their own.

## Run it

```console
$ jk lock
✓ [10] org.opentest4j:opentest4j:1.3.0
 ✓ Lock   Workspace lock successful. Resolved 10 dependencies took 54ms

$ jk build
✓ [1 of 2] com.example:lib took 6ms
✓ [2 of 2] com.example:app took 5ms
 ✓ Build   Build successful, checked 2 modules, all up to date took 508ms

$ jk test
✓ [1 of 2] com.example:lib took 4ms
✓ [2 of 2] com.example:app took 5ms
 ✓ Test   Tests passed for 2 modules took 33ms

$ jk run
hello world

$ jk run . ada
hello ada
```

`jk lock` is optional: `jk build` writes `jk-lock.toml` when it is missing. The lockfile is
committed, so every reader resolves the same JUnit 6.1.3 — delete it, build, and the
regenerated file is identical.

`jk build` above found every action already in the cache; on a cold cache the same two module
lines appear with real compile times. `--redo` forces the work either way, which is where the
order shows: `lib` is compiled before the `app` that depends on it.

```console
$ jk build --redo
✓ [1 of 2] com.example:lib took 1.2s
✓ [2 of 2] com.example:app took 454ms
 ✓ Build   Build successful for 2 modules took 1.3s
```

Both modules have tests, and `jk test --redo` runs all four —
`target/jk-results.md` records `Tests: **100%** pass · 4 passed (4 total)`.

## What the manifests say

The root owns identity and the shared JUnit version:

```toml
[workspace]
modules = ["lib", "app"]

[workspace.dependencies]
junit-jupiter = "6.1.3"
```

`lib/jk.toml` declares a `name` and nothing else about identity — `group`, `version` and
`java` inherit. Inheritance is resolved at lock time and frozen into `jk-lock.toml`, so the
lockfile is where you read a module's effective identity:

```toml
[[module]]
path    = "lib"
group   = "com.example"
name    = "lib"
version = "0.0.1"
java    = 25
```

`app/jk.toml` reaches its sibling by name, and both modules take JUnit from the root:

```toml
[dependencies]
lib.workspace = true

[test-dependencies]
junit-jupiter.workspace = true
```

`jk tree` shows the resulting graph, with `[workspace]` marking the sibling edge:

```console
$ jk tree
 ≡ Dependencies Tree
 ● com.example:workspace-showcase:0.0.1
 │ · Scopes: export, main, runtime
 ╰─ main
    ╰─ com.example:app:0.0.1
       ╰─ com.example:lib [workspace]
```

## Selecting modules

`-m` / `--modules` narrows a run to some members. A selected module drags in the modules it
depends on, so testing `app` also builds `lib`:

```console
$ jk build -m lib
 …building module lib…
✓ [1 of 1] com.example:lib took 4ms
 ✓ Build   Build successful, checked 1 module, all up to date took 26ms

$ jk test -m app
 …testing module app…
✓ [1 of 2] com.example:lib took 4ms
✓ [2 of 2] com.example:app took 4ms
 ✓ Test   Tests passed for 2 modules took 29ms
```

## An edit in `lib` makes `app` dirty

This is what the sibling edge buys, and `jk explain` shows it before any work happens.
Change the fallback word in `lib/src/main/java/com/example/lib/Greet.java`:

```console
$ jk explain
 ≡ Build Graph
 ● com.example:workspace-showcase
 │
 ╰─ Rebuild  2 modules are dirty
    │
    ├─ lib
    │  ╰─ □ Compile 1 source changed › □ Test ~2 tests › □ Package
    ╰─ app
       ╰─ □ Compile 2 sources › □ Test ~2 tests › □ Package
```

One edited file in `lib`, two dirty modules. `app`'s test asserts the composition across
that boundary — `Main.greeting(...)` is `app`'s code calling `lib`'s `Greet.hello(...)`, so
it fails if the edge ever stops being wired.

## A sibling has to be a member

`workspace = true` resolves against `[workspace] modules` and `[workspace.dependencies]`,
nothing else. Drop `"lib"` from the members list and `app`'s dependency has nowhere to
resolve:

```console
$ jk build
 Build  Failure
 ┃ no workspace dependency or sibling named `lib`
 ┗━

 ✘ Build   Failed to build dependency resolution failed
```

The build exits 2.

## Related

[Workspaces](../../workspaces.md) · [Lockfile](../../lockfile.md) ·
[Dependencies](../../dependencies.md)
