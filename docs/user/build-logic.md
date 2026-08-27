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
no `.jk-build/` / `jk-build/` compatibility path — leftover those directories fail the
build.

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

**A script that writes nothing runs every build.** An empty `outDir` is never a cache
hit, so a task that produces no artifact is re-executed instead of replayed. That is the
shape a *check* wants — scan, throw to fail the build, produce nothing — and it is why a
check does not need to declare inputs the way a Gradle task does. A task that generates
files gets the opposite: write to `outDir` and the cache replays it while the module's
sources, `jk.toml` and `jk-lock.toml` are unchanged.

**A module with no sources still runs its build logic.** A workspace member needs a
`jk.toml` and an entry in `[workspace] modules`, not a `src/` tree.

Scripts run **out of process**. A Groovy `System.exit` or OOM cannot take the engine
down.

Compiled `.java` / `.kt` under `jk/` / `.jk/` is rejected. Put reusable tools in a
[plugin](plugins.md).

## Workspace build logic

A `jk/` or `.jk/` beside the **workspace root's** `jk.toml` runs one stem, and only that
one:

| File | When |
|------|------|
| `after-build.groovy` / `.kts` | Once per build, after **every member module** has built |

That is the anchor a workspace-wide step or check wants: every member's sources and
outputs are on disk, and the script runs once rather than once per module. Ordering comes
from the build graph — the root becomes a unit that depends on all its members — so
`[build] order-after` is not needed for it.

The two sets do not mix, in either direction, and using the wrong one **fails the build**
rather than being skipped:

- A module stem (`before-compile`, `after-compile`, `after-resources`, `before-package`)
  at the root is an error. A root compiles and packages nothing, so there is no cut for
  them to be relative to.
- `after-build` inside a module is an error. A module has no "after every member" moment.

A root script has no classes tree to merge into: its `outDir` is its own output and
nothing downstream reads it. A root that carries its own `src/` is an ordinary module as
well, and uses the module stems for that half.

```text
my-workspace/
  jk.toml              # [workspace] modules = ["core", "app"]
  .jk/
    after-build.kts    # runs once, after core and app
  core/
    jk.toml
    .jk/
      before-compile.groovy
  app/
    jk.toml
```

Authoring plugins (reusable, versioned): [contributor plugins](../contributors/plugins.md).
