# Project build logic (`.jk-build/`)

**Tickets:** JK-1026 (design), JK-1037 (MVP), JK-1039 (multi-task), **JK-1044** (graph SPI / anchors) — [kanartist](https://github.com/jkbuild/kanartist) project `jk`  
**Related:** [mill-comparison.md](../mill-comparison.md) §6

## Intent

JumpKick stays **convention-over-configuration** with a **data-only** `jk.toml`. Custom build
behavior lives under **`.jk-build/`** as stem scripts (`.groovy` today; `.kts` later) and/or a
**project-local Java (later Kotlin) module** — not in TOML scripts. Same idea as Mill’s programmable
tasks, without making the manifest a programming language.

The convention directory is **hidden** (`.jk-build/`) so it does not sit next to product `src/`
like Gradle’s `buildSrc/`. Prefer that; use `[build].logic` only when you want a different path.

| Layer | Role |
|---|---|
| `jk.toml` | Data only; optional `[build].logic` pointer |
| **`.jk-build/`** | Default directory for project build logic sources |
| Plugins | Out-of-process workers for heavy / reusable tools |
| Verbs | `build` / `test` / … remain the user-facing product |

## Convention

If a directory named **`.jk-build`** exists next to `jk.toml` and contains stem scripts and/or
`.java` sources, the engine discovers tasks and runs them at the matching anchors (action-cached).

```text
my-app/
  jk.toml
  src/…
  .jk-build/
    before-compile.groovy          # optional stem scripts (scripts-only is fine)
    after-resources.kts            # same stems for .kts (kotlinc -script)
    src/demo/LineCountBuild.java   # optional Java SPI / *Build mains
    src/demo/TokenLogic.kt         # optional Kotlin SPI (same anchors)
```

No TOML required when the convention directory is present.

### Stem scripts (`.groovy` / `.kts`)

Top-level files under `.jk-build/` (not recursive). Same stems for both extensions:

| File | Anchor | Stage wire |
|------|--------|------------|
| `before-compile.groovy` / `.kts` | `BEFORE_COMPILE` | `generate` |
| `after-compile.groovy` / `.kts` | `AFTER_COMPILE` | `compile` |
| `after-resources.groovy` / `.kts` | `AFTER_RESOURCES` | `compile` |
| `before-package.groovy` / `.kts` | `BEFORE_PACKAGE` | `package` |

Suffixes allowed for multiple scripts at one anchor: `before-compile-collections.groovy`. Underscores
are aliases (`before_compile.kts`). A `.groovy` and `.kts` with the same stem name conflict.

**Bindings** (injected for both hosts):

| Name | Type | Meaning | Groovy | `.kts` |
|------|------|---------|--------|--------|
| `projectDir` | `java.nio.file.Path` | Project root (`jk.toml`) | yes | yes |
| `outDir` | `Path` | Per-task output (action-cached; merged into classes) | yes | yes |
| `classesDir` | `Path` | Module classes tree | yes | yes |
| `properties` | `Map<String,Object>` | Mutable bag (e.g. nested `evaluate`) | yes | — |
| `ant` | `groovy.ant.AntBuilder` | When Ant jars resolve | yes | — |

| Host | How it runs |
|------|-------------|
| **Groovy** | Reflective `GroovyShell`; jars fetched into `$JK_CACHE_DIR/tools/build-logic-groovy/` |
| **`.kts`** | Product `kotlinc -script` (Kotlin home via `CompileToolchain`); wrapper injects bindings |

`.kts` example:

```kotlin
import java.nio.file.Files
Files.writeString(outDir.resolve("stamp.txt"), "ok")
```

## Override location

```toml
[build]
logic = "tools/codegen"          # project-relative directory (instead of .jk-build)
logic-main = "demo.LineCountBuild"  # optional; otherwise discover *Build / BuildMain
```

Disable even if `.jk-build/` exists:

```toml
[build]
logic = "off"   # also: false, none, disable
```

`logic` must resolve under the project root (no `../` escape).

## Runtime

1. Resolve logic dir (override or `.jk-build`).  
2. Discover **stem scripts** (`*.groovy` / `*.kts` at the logic dir root).  
3. If any **`.java` / `.kt`** sources exist under the logic tree:
   - Compile `.java` with the system javac (SPI jar on classpath).  
   - Compile `.kt` with the product **kotlin-compiler worker** (default Kotlin line + stdlib;
     non-incremental). Kotlin may call already-compiled Java in the same tree.  
   - Discover:
     - **SPI:** classes implementing `cc.jumpkick.plugin.buildlogic.BuildLogicContributor`
       call `register(BuildLogicGraph)` and may attach named tasks to anchors.  
     - **Legacy mains:** every public class named `*Build` / `*BuildMain` with
       `public static void main` (or `[build].logic-main`) runs at **`AFTER_RESOURCES`**.  
4. BuildPlan anchors invoke matching tasks as **independently action-cached** steps
   (each maps to a [`BuildStage`](../../architecture.md#request-phases-vs-build-stages) wire name):
   - `BEFORE_COMPILE` — before main language compile (**stage `generate`**) — codegen home  
   - `AFTER_COMPILE` — after main compile / assemble (**stage `compile`**)  
   - `AFTER_RESOURCES` — after static resources copy (default for legacy mains; **stage `compile`**)  
   - `BEFORE_PACKAGE` — immediately before jar/image packaging (**stage `package`**)  
5. Merge each task’s `outDir` into the classes tree.  
6. Labels: `build-logic:<name>: cache hit` or `build-logic:<name>: <anchor>`.

Scripts and Java may coexist; task names must be unique across both.

### SPI sketch

```java
package demo;
import cc.jumpkick.plugin.buildlogic.*;
import java.nio.file.*;

public class CodegenLogic implements BuildLogicContributor {
  @Override
  public void register(BuildLogicGraph g) {
    // Sources that must exist before javac/kotlinc:
    g.task("gen-collections", BuildLogicAnchor.BEFORE_COMPILE, ctx -> {
      // write into projectDir source or generated roots
    });
    g.task("gen-tokens", BuildLogicAnchor.AFTER_COMPILE, ctx -> {
      Files.writeString(ctx.outDir().resolve("tokens.txt"), "ok");
    });
    g.task("stamp-package", BuildLogicAnchor.BEFORE_PACKAGE, ctx -> {
      Files.writeString(ctx.outDir().resolve("pkg.stamp"), "1");
    });
  }
}
```

Legacy `*Build` mains still work unchanged (AFTER_RESOURCES). Java SPI, Kotlin SPI, and scripts
may coexist (unique task names).

### Kotlin SPI sketch

```kotlin
package demo
import cc.jumpkick.plugin.buildlogic.*
import java.nio.file.Files

class TokenLogic : BuildLogicContributor {
  override fun register(g: BuildLogicGraph) {
    g.task("gen-tokens", BuildLogicAnchor.AFTER_COMPILE) { ctx ->
      Files.writeString(ctx.outDir().resolve("tokens.txt"), "ok")
    }
  }
}
```

Sample (legacy main): [examples/line-count-build/](examples/line-count-build/).

## Non-goals

- Scripts **inside** `jk.toml`  
- Loading build logic into the native CLI image  
- Replacing first-party plugins for reusable tooling  
- Dual convention with a visible `jk-build/` (use `[build].logic` if you want a non-dot path)

## Future

- Workspace-shared logic via `[workspace]`  
- Richer graph (task→task edges, Mill-style traits)

## Naming history

Earlier drafts used `[hatch]` / `build-hatch` / visible `jk-build/`. Product term is **project
build logic** / **`.jk-build/`**.
