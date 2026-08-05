# JumpKick for IntelliJ

Wire-only IntelliJ plugin: **Tools → JumpKick** drives the `jk` CLI (or `JK_BIN`).
Never loads the JumpKick engine jar into the IDE.

## Features

| Action | Behavior |
|---|---|
| **Sync project** | `jk ide --print-model` → `jk ide --idea` → `jk bsp install` + VFS refresh (JK-1511) |
| Sync dependencies only | `jk sync` |
| Install BSP connection | `jk bsp install` → `.bsp/jk.json` |
| Build / Test / Lock | `jk build` / `test` / `lock` |

On project open, if `jk.toml` is present, the plugin offers Sync (and **auto-Sync** when no
`.idea/modules.xml` / `*.iml` exist yet). You do **not** need to run `jk ide` manually.

**Import model:** engine `ide-model` is the source of truth (same as BSP). The IntelliJ module
files are applied via the shared `jk ide --idea` generator so layout/classpath stay aligned with
CLI export. BSP remains dual-path for JetBrains BSP clients and VS Code.

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
