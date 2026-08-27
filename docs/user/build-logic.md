# Project build logic (`jk/` or `.jk/`)

`jk.toml` stays **data**. Custom generate / prep steps live in a **project-local
directory**, not in TOML scripts.

**Convention:** if `jk/` or `.jk/` exists next to that module's `jk.toml`, JumpKick
runs its top-level stem scripts on build (action-cached). Same feature either way —
`jk/` is visible in a default listing, `.jk/` is hidden. If **both** exist, **`jk/`
wins** (the trees are not merged). Prefer plugins for heavy/reusable tools; use
`jk/` / `.jk/` for small project-local codegen.

This is **per module**. A workspace member needs the directory next to *its*
`jk.toml`. A sourceless workspace-root aggregator does not run build logic. There
is no `.jk-build/` / `jk-build/` compatibility path — leftover those directories
fail the build.

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
`jk.toml` and an entry in `[workspace] modules`, not a `src/` tree — so a check that has
to see the whole workspace can live in a member of its own. A workspace-*root* aggregator
does not run build logic; the directory has to belong to a member.

Scripts run **out of process**. A Groovy `System.exit` or OOM cannot take the engine
down.

Compiled `.java` / `.kt` under `jk/` / `.jk/` is rejected. Put reusable tools in a
[plugin](plugins.md).

Authoring plugins (reusable, versioned): [contributor plugins](../contributors/plugins.md).
