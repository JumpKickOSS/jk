# Project build logic (`.jk-build/`)

`jk.toml` stays **data**. Custom generate / prep steps live in a **hidden project-local
directory**, not in TOML scripts.

**Convention:** if `.jk-build/` exists next to `jk.toml`, JumpKick compiles and runs its
sources on build (action-cached). Prefer plugins for heavy/reusable tools; use `.jk-build`
for small project-local codegen.

```text
my-app/
  jk.toml
  src/…
  .jk-build/
    before-compile.groovy
    src/demo/LineCountBuild.java
```

```toml
# optional override — only when you do not want the .jk-build/ convention
[build]
logic = "tools/codegen"
logic-main = "demo.LineCountBuild"
# logic = "off"                    # disable even if .jk-build/ exists
```

Sample: [examples/line-count-build/](examples/line-count-build/).

## Stem scripts

Top-level files under `.jk-build/` (not recursive). Same stems for `.groovy` and `.kts`:

| File | When |
|------|------|
| `before-compile.groovy` / `.kts` | Before compile (generated-source root) |
| `after-compile.groovy` / `.kts` | After compile |
| `after-resources.groovy` / `.kts` | After resources |
| `before-package.groovy` / `.kts` | Before package |

Suffixes allowed: `before-compile-collections.groovy`. Underscores are aliases. A
`.groovy` and `.kts` with the same stem name conflict.

**`outDir` is the only place a task may write.** It is the only thing the action cache
captures. Bindings include `projectDir` and `outDir` (Groovy also gets `properties` and
`ant` when Ant jars resolve).

Java `*Build` mains and `BuildLogicContributor` SPI provide named tasks at the same
anchors. Kotlin sources in `.jk-build/src` are supported the same way.

Authoring plugins (reusable, versioned): [contributor plugins](../contributors/plugins.md).
