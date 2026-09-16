# JumpKick for IntelliJ

Wire-only IntelliJ plugin: **Tools → JumpKick** drives the `jk` CLI (or `JK_BIN`).
Never loads the JumpKick engine jar into the IDE.

## Features

| Action | Behavior |
|---|---|
| **Sync project** | `jk ide --print-model` → `jk ide --idea` → `jk bsp install` + VFS refresh |
| Sync dependencies only | `jk sync` |
| Install BSP connection | `jk bsp install` → `.bsp/jk.json` |
| Build / Test / Lock | `jk build` / `test` / `lock` |

On project open, if `jk.toml` is present, the plugin offers Sync (and **auto-Sync** when no
`.idea/modules.xml` / `*.iml` exist yet). You do **not** need to run `jk ide` manually.

**Import model:** engine `ide-model` is the source of truth (same as BSP). The IntelliJ module
files are applied via the shared `jk ide --idea` generator so layout/classpath stay aligned with
CLI export. BSP remains dual-path for JetBrains BSP clients and VS Code.

## Distribution

Not on the JetBrains Marketplace: `publishPlugin` is not wired and no release runs it. Install from
the zip `buildPlugin` produces, or run from source. Editor intelligence for `jk.toml` does not
depend on this plugin — see the JSON Schema note in `docs/user/projects.md`.

## Requirements

- IntelliJ IDEA 2024.1+ (Community or Ultimate)
- `jk` on PATH (`jk --version`), or `JK_BIN` / system property `jk.bin`

## Build installable zip

```bash
./scripts/package-intellij.sh
# → clients/intellij/build/distributions/jumpkick-intellij-*.zip
```

**Install from disk:** Settings → Plugins → ⚙ → Install Plugin from Disk…

## Architecture

```
IntelliJ plugin  ──Process──►  jk CLI  ──wire──►  engine JVM
       │                         │
       │  ide --print-model      └── jk bsp serve (via .bsp/jk.json)
       │  ide --idea
       └── no engine jars
```

Same constraint as the VS Code extension (`clients/vscode/`).

## Design: the plugin owns a live project model

**Decision.** The plugin becomes an IntelliJ *external system* (the same API family the bundled
Gradle and Maven integrations use): a `ProjectResolver` that turns the engine's `ide-model` into
IntelliJ's project structure through `ProjectDataManager`. The plugin owns modules, content roots,
source and test roots (every discovered suite), generated-source roots, libraries with sources
jars, per-module SDK, and compiler output directories. Nothing is written to `.idea/modules.xml`
or `*.iml` by jk for an external-system project; IntelliJ persists what the resolver produced,
exactly as it does for Gradle.

**Wire-only stays.** The resolver obtains the model by running `jk ide --print-model` (JSON on
stdout, `IdeWireModel`) and `jk sync` for progress, through `JkCliRunner`. No engine jar enters the
IDE process. `JK_BIN` / `jk.bin` keep locating the binary; when neither is set and `jk` is not on
PATH, the resolver reports one actionable error (install line from `docs/user/install.md`).

**Sync triggers.**

| Trigger | Behaviour |
|---|---|
| Project open with `jk.toml` | Link the external project and resolve once; no prompt when the project was linked before |
| `jk.toml` or `jk-lock.toml` saved (any module) | Debounced re-resolve (2 s); libraries and roots update in place |
| Tools → JumpKick → Sync | Explicit re-resolve, progress in the Build tool window |
| `jk add` / `jk remove` from a terminal | Covered by the lock-file trigger through the VFS watcher |

**Compiler output.** Resolved modules point IntelliJ's compiler at the isolated IDE output
directories the model already carries (`target/jdt/classes/main` and `…/test`), never at jk's
`target/classes`. The gutter's JUnit run compiles there; `jk build` never sees IDE-written class
files.

**Run and test.** The JUnit gutter keeps IntelliJ's own runner over the resolved classpath; a
later slice adds a run-configuration producer that routes Debug through `jk test --debug`. Shell
run configurations for `jk test` are no longer generated for external-system projects; the Build
and Test actions remain in the menu.

**Retirement plan for generated files.**

1. Land the resolver behind the same plugin id; `Sync project` resolves through it.
2. `jk ide --idea` stays as the offline export for users without the plugin; the plugin no longer
   invokes it, and the open-project activity no longer offers it when the plugin is installed.
3. Once the Marketplace listing ships, `docs/user/ide.md` describes the plugin as the IntelliJ
   path and `jk ide --idea` as export.

**MVP scope.** Open jk's own 78-module workspace: every module resolved with roots, libraries,
sources jars, SDK; zero `.iml` files on disk; a terminal `jk add` reflected after the lock-file
trigger; sync errors surfaced in the Build tool window with the CLI's first error line.
