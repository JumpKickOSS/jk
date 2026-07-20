# Project build logic (`jk-build/`)

**Tickets:** [1026](../kanban/ticket-1026-programmable-escape-hatch-design.md) (design),
[1037](../kanban/ticket-1037-programmable-escape-hatch-mvp.md) (MVP)  
**Related:** [mill-comparison.md](../mill-comparison.md) §6

## Intent

JumpKick stays **convention-over-configuration** with a **data-only** `jk.toml`. Custom build
behavior lives in a **project-local Java (later Kotlin) module**, not in TOML scripts — the same
idea as Mill’s programmable tasks, without making the manifest a programming language.

| Layer | Role |
|---|---|
| `jk.toml` | Data only; optional `[build].logic` pointer |
| **`jk-build/`** | Default directory for project build logic sources |
| Plugins | Out-of-process workers for heavy / reusable tools |
| Verbs | `build` / `test` / … remain the user-facing product |

## Convention

If a directory named **`jk-build`** exists next to `jk.toml` and contains `.java` sources, the
engine compiles and runs it during the resources phase (action-cached).

```text
my-app/
  jk.toml
  src/…
  jk-build/
    src/demo/LineCountBuild.java
```

No TOML required when the convention directory is present.

## Override location

```toml
[build]
logic = "tools/codegen"          # project-relative directory (instead of jk-build)
logic-main = "demo.LineCountBuild"  # optional; otherwise discover *Build / BuildMain
```

Disable even if `jk-build/` exists:

```toml
[build]
logic = "off"   # also: false, none, disable
```

`logic` must resolve under the project root (no `../` escape).

## Runtime

1. Resolve logic dir (override or `jk-build`).  
2. Compile all `.java` under that tree **once**.  
3. Discover every public class named `*Build` / `*BuildMain` (or a single class from
   `[build].logic-main`).  
4. Run each main with `--project <module>` and `--out <generated-dir>` as an
   **independently action-cached** task (ticket-1039).  
5. Merge each task’s outputs into the classes tree as resources.  
6. Labels: `build-logic:<SimpleName>: cache hit` or `…: compile + run`.

Sample: [examples/line-count-build/](examples/line-count-build/).

## Non-goals

- Scripts **inside** `jk.toml`  
- Per-phase free-form `.kts` hooks  
- Loading build logic into the native CLI image  
- Replacing first-party plugins for reusable tooling  

## Future

- Task graph SPI (`register(BuildGraph)`) with explicit anchors (after compile, before package)  
- Kotlin sources in `jk-build/`  
- Workspace-shared logic via `[workspace]`  


## Naming history

Earlier drafts used `[hatch]` / `build-hatch`. Product term is **project build logic** /
**`jk-build/`**.
