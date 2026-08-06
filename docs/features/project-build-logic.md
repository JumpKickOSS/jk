# Project build logic (`.jk-build/`)

**Tickets:** JK-1026 (design), JK-1037 (MVP), JK-1039 (multi-task), **JK-1044** (graph SPI / anchors) — [kanartist](https://github.com/jkbuild/kanartist) project `jk`  
**Related:** [mill-comparison.md](../mill-comparison.md) §6

## Intent

JumpKick stays **convention-over-configuration** with a **data-only** `jk.toml`. Custom build
behavior lives in a **project-local Java (later Kotlin) module**, not in TOML scripts — the same
idea as Mill’s programmable tasks, without making the manifest a programming language.

The convention directory is **hidden** (`.jk-build/`) so it does not sit next to product `src/`
like Gradle’s `buildSrc/`. Prefer that; use `[build].logic` only when you want a different path.

| Layer | Role |
|---|---|
| `jk.toml` | Data only; optional `[build].logic` pointer |
| **`.jk-build/`** | Default directory for project build logic sources |
| Plugins | Out-of-process workers for heavy / reusable tools |
| Verbs | `build` / `test` / … remain the user-facing product |

## Convention

If a directory named **`.jk-build`** exists next to `jk.toml` and contains `.java` sources, the
engine compiles and runs it during the resources phase (action-cached).

```text
my-app/
  jk.toml
  src/…
  .jk-build/
    src/demo/LineCountBuild.java
```

No TOML required when the convention directory is present.

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
2. Compile all `.java` under that tree with the **build-logic SPI** (`jk-plugin-sdk`) on the
   compile classpath.  
3. Discover tasks:
   - **SPI:** classes implementing `cc.jumpkick.plugin.buildlogic.BuildLogicContributor`
     call `register(BuildLogicGraph)` and may attach named tasks to anchors.  
   - **Legacy mains:** every public class named `*Build` / `*BuildMain` with
     `public static void main` (or `[build].logic-main`) runs at **`AFTER_RESOURCES`**.  
4. BuildPlan anchors invoke matching tasks as **independently action-cached** steps:
   - `AFTER_COMPILE` — after main compile / assemble  
   - `AFTER_RESOURCES` — after static resources copy (default for legacy mains)  
   - `BEFORE_PACKAGE` — immediately before jar/image packaging  
5. Merge each task’s `outDir` into the classes tree.  
6. Labels: `build-logic:<name>: cache hit` or `build-logic:<name>: <anchor>`.

### SPI sketch

```java
package demo;
import cc.jumpkick.plugin.buildlogic.*;
import java.nio.file.*;

public class CodegenLogic implements BuildLogicContributor {
  @Override
  public void register(BuildLogicGraph g) {
    g.task("gen-tokens", BuildLogicAnchor.AFTER_COMPILE, ctx -> {
      Files.writeString(ctx.outDir().resolve("tokens.txt"), "ok");
    });
    g.task("stamp-package", BuildLogicAnchor.BEFORE_PACKAGE, ctx -> {
      Files.writeString(ctx.outDir().resolve("pkg.stamp"), "1");
    });
  }
}
```

Legacy `*Build` mains still work unchanged (AFTER_RESOURCES). Both styles may coexist.

Sample (legacy main): [examples/line-count-build/](examples/line-count-build/).

## Non-goals

- Scripts **inside** `jk.toml`  
- Per-phase free-form `.kts` hooks  
- Loading build logic into the native CLI image  
- Replacing first-party plugins for reusable tooling  
- Dual convention with a visible `jk-build/` (use `[build].logic` if you want a non-dot path)

## Future

- Kotlin sources in `.jk-build/`  
- Workspace-shared logic via `[workspace]`  
- Richer graph (task→task edges, Mill-style traits)

## Naming history

Earlier drafts used `[hatch]` / `build-hatch` / visible `jk-build/`. Product term is **project
build logic** / **`.jk-build/`**.
