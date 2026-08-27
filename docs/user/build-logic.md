# Project build logic (`.jk/`)

`jk.toml` stays **data**. Custom generate / prep steps live in a **hidden project-local
directory**, not in TOML scripts.

**Convention:** if `.jk/` exists next to that module's `jk.toml`, JumpKick runs its
top-level stem scripts on build (action-cached). Prefer plugins for heavy/reusable
tools; use `.jk/` for small project-local codegen.

This is **per module**. A workspace member needs `.jk/` next to *its* `jk.toml`. A
sourceless workspace-root aggregator does not run build logic. There is no
`.jk-build/` / `jk-build/` compatibility path — leftover those directories fail the
build.

```text
my-app/
  jk.toml
  src/…
  .jk/
    before-compile.groovy
    after-resources.kts
```

```toml
# optional override — only when you do not want the .jk/ convention
[build]
logic = "tools/codegen"
# logic = "off"                    # disable even if .jk/ exists
```

Sample: [examples/line-count-build](examples/line-count-build/).

## Stem scripts

Top-level files under `.jk/` (not recursive). Same stems for `.groovy` and `.kts`:

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

Scripts run **out of process**. A Groovy `System.exit` or OOM cannot take the engine
down.

Compiled `.java` / `.kt` under `.jk/` is rejected. Put reusable tools in a
[plugin](plugins.md).

Authoring plugins (reusable, versioned): [contributor plugins](../contributors/plugins.md).
