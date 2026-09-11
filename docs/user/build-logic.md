# Project build logic (`jk/` or `.jk/`)

`jk.toml` stays **data**. Custom generate / prep steps live in a **project-local
directory**, not in TOML scripts.

**Convention:** if `jk/` or `.jk/` exists next to that module's `jk.toml`, JumpKick
runs its top-level stem scripts on build (action-cached). Same feature either way —
`jk/` is visible in a default listing, `.jk/` is hidden. If **both** exist, **`jk/`
wins** (the trees are not merged). Prefer plugins for heavy/reusable tools; use
`jk/` / `.jk/` for small project-local codegen.

A **module** needs the directory next to *its* `jk.toml`. The **workspace root** has one
too, with its own anchor — see [Workspace build logic](#workspace-build-logic). There is
no `.jk-build/` / `jk-build/` compatibility path — a leftover directory of either name
fails the build on sight, even when a valid `jk/` or `.jk/` sits beside it.

```text
my-app/
  jk.toml
  src/…
  .jk/                 # or jk/ — pick one
    before-compile.groovy
    after-resources.kts
```

```toml
# optional override — only when you do not want the jk/ / .jk/ convention
[build]
logic = "tools/codegen"
# logic = "off"                    # disable even if jk/ or .jk/ exists
```

Sample: [examples/line-count-build](examples/line-count-build/).

## Stem scripts

Top-level files under `jk/` or `.jk/` (not recursive). Same stems for `.groovy` and `.kts`:

| File | When |
|------|------|
| `before-compile.groovy` / `.kts` | Before compile (generated-source root) |
| `after-compile.groovy` / `.kts` | After compile |
| `after-resources.groovy` / `.kts` | After resources |
| `before-package.groovy` / `.kts` | Before package |

Suffixes allowed: `before-compile-collections.groovy`. Underscores are aliases. A
`.kts` and `.groovy` with the same stem name: **the `.kts` runs**, the `.groovy` is
ignored.

**`outDir` is the only place a task may write.** It is the only thing the action cache
captures. Bindings include `projectDir` and `outDir` (Groovy also gets `properties` and
`ant` when Ant jars resolve). Groovy `@Grab` and Kotlin `@file:` annotations are the
script languages' own dependency hooks — not a nested Java/Kotlin project.

**A script that writes nothing still caches.** It records a *verdict*: the script ran on
these exact inputs and produced no artifact, so an unchanged tree skips it. That is the
shape a *check* wants — scan, throw to fail the build, produce nothing — and it is why a
check does not need to declare inputs the way a Gradle task does. A task that generates
files gets the same treatment with its output attached: write to `outDir` and the cache
replays it while the module's sources, `jk.toml` and `jk-lock.toml` are unchanged. Only a
success is recorded, so a failing script re-runs rather than replaying its own red.

**The key covers the whole scope, on purpose.** A script declares no inputs, so its key
has to cover everything it *could* read: for a module stem, the module's source roots
(plugin-contributed roots included) plus `jk.toml` and `jk-lock.toml`; for a root stem
(`after-build`, `guard`), every file in the checkout except build output (`target/`,
Gradle's `build/`) and tool metadata (`.git`, `.gradle`, `.idea`, `node_modules`). The
tradeoff runs one way: an edit anywhere in scope re-runs the script even when it never
reads the changed file, and a script is never skipped on inputs it did read. That re-run
is the only cost of not declaring inputs, and computing the key is cheap — every hash is
memoized on the file's size and mtime, so a warm engine pays one stat per file (tens of
milliseconds for a few-thousand-file checkout, once per build across a module's anchors)
and only reads a file that changed since it was last hashed. The key is computed for the
first script at an anchor that consults the cache, so an anchor whose scripts are all
`jk: always` never walks its scope at all.

**A script that must run every time says so.** A `//` comment line `jk: always` in the
script's header (its first 40 lines) exempts it from both the verdict and the artifact
cache: it runs whenever its anchor runs, and nothing is recorded. That is for work whose
answer depends on state the cache key cannot see — a sweep that measures build output and
reclaims it — where "same sources" says nothing about what the script would do now. Checks
and generators do not want this; the key already describes their inputs.

**A module with no sources still runs its build logic.** A workspace member needs a
`jk.toml` and an entry in `[workspace] modules`, not a `src/` tree.

Scripts run **out of process**, so a script cannot take the engine down. The two languages
differ in how much else they share — see below.

Compiled `.java` / `.kt` under `jk/` / `.jk/` is rejected. Put reusable tools in a
[plugin](plugins.md).

## How `.kts` scripts run

Every `.kts` of a build shares **one** child JVM, and each script is **compiled once**.
The compiled form is cached under `$JK_CACHE_DIR/kts/`, keyed by the script's own bytes
and the host's script definition, so:

- An unchanged script is not recompiled — not on the next build either.
- Editing one script recompiles only that script.
- The same script run against several modules compiles **once**. Bindings arrive as
  declared properties at evaluation time, so a script's text never contains a module path.

This matters because compiling a `.kts` is expensive and starting a JVM to do it is more
so — together about 5 s for a large script, previously paid on every run of every script.
A second script in an already-running session costs milliseconds.

Two consequences worth knowing:

- **A `.kts` that calls `System.exit` (or exhausts the heap) takes the shared host with
  it.** The build fails against that script, and the next script gets a fresh host. Groovy
  scripts still fork per script, so they keep per-script isolation.
- **A `.kts` has no per-script working directory.** Resolve paths from `projectDir`.

Supported `@file:` annotations are Kotlin's own: `@file:DependsOn`, `@file:Repository`,
`@file:CompilerOptions` and `@file:Import`. `@file:Import` compiles the imported files into
the *same* unit — one cached jar — which is how a large script splits across files without
paying to compile several.

> `@file:Import` needs Kotlin **2.4.10 or newer**, which is jk's floor. On 2.4.0 the K2
> frontend fails to compile any script that uses it.

## Workspace build logic

A `jk/` or `.jk/` beside the **workspace root's** `jk.toml` runs these stems (and only
these):

| File | When |
|------|------|
| `after-build.groovy` / `.kts` | Once per build, after **every member module** has built |
| `guard.groovy` / `.kts` | Share-the-commit bar: `jk test --guard` / `jk test --scripts-only`. Not on inner `jk test` / `jk build`. |

`after-build` is the always-on workspace-wide step: every member's sources and outputs
are on disk, and the script runs once rather than once per module. Ordering comes from
the build graph — the root becomes a unit that depends on all its members — so
`[build] order-after` is not needed for it.

`guard` is the same *shape* (once per graph, whole-tree cache key, a check that writes
nothing records a verdict) and the other *budget*. Tree-scan checks that are not house
rules — a release-notes lint, a generated-file freshness probe — belong here so the inner
loop does not pay them; house rules themselves are `jk-guards.toml`, not a script
(`jk guard explain` walks them). A standalone project root is the invocation root and the
module at once, so it carries the module stems (`before-compile`, `after-compile`,
`after-resources`, `before-package`) and `guard` side by side, and `jk build`, `jk guard`
and `jk test --guard` all accept that same set. `after-build` stays a sourceless workspace
root's stem — a standalone project has no members to be after. A workspace **member** may
not use `guard`.

`--scripts-only` runs the guard stem without JUnit (legal with or without `--guard`).
`--no-scripts` runs `--guard` tests without the extra scripts. Combining the two flags is
a config error. `--scripts-only` with no `guard` stem is a config error naming the paths
looked for (`jk/guard.{kts,groovy}`, `.jk/guard.{kts,groovy}` at the root).
`jk build --guard --skip-tests` packages, then runs guard scripts (no JUnit).
`--guard --scripts-only` is the same as `--scripts-only`.

The two sets do not mix, in either direction, and using the wrong one **fails the build**
rather than being skipped:

- A module stem (`before-compile`, `after-compile`, `after-resources`, `before-package`)
  at a sourceless workspace root is an error. Such a root compiles and packages nothing, so
  there is no cut for them to be relative to.
- `after-build` or `guard` inside a module is an error. A module has no "after every
  member" moment.

A root script has no classes tree to merge into: its `outDir` is its own output and
nothing downstream reads it. A root that carries its own `src/` is an ordinary module as
well, and uses the module stems for that half.

```text
my-workspace/
  jk.toml              # [workspace] modules = ["core", "app"]
  .jk/
    after-build.kts    # runs once, after core and app, every build
    guard.kts           # runs on --guard / --scripts-only, not on inner jk test
  core/
    jk.toml
    .jk/
      before-compile.groovy
  app/
    jk.toml
```

Authoring plugins (reusable, versioned): [contributor plugins](../contributors/plugins.md).
