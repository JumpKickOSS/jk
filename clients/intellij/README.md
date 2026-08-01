# JumpKick for IntelliJ

Wire-only IntelliJ plugin: **Tools → JumpKick** actions shell `jk` on PATH (or `JK_BIN`).
Never loads the JumpKick engine jar into the IDE.

# # Features

| Action | CLI |
|---|---|
| Install BSP connection | `jk bsp install` → `.bsp/jk.json` |
| Sync / Build / Test / Lock | `jk sync` / `build` / `test` / `lock` |

* *Import:** after BSP install, use JetBrains’ BSP support (or compatible plugin) to import the
connection file. This plugin owns lifecycle install + build actions, not a full language server.

# # Requirements

- IntelliJ IDEA 2024.2+ (Community or Ultimate)
- `jk` on PATH (`jk --version`)

# # Build installable zip

```bash
./scripts/package-intellij.sh
# → clients/intellij/build/distributions/jumpkick-intellij-*.zip
```

* *Install from disk:** Settings → Plugins → ⚙ → Install Plugin from Disk…

# # Architecture

```
IntelliJ plugin  ──Process──►  jk CLI  ──wire──►  engine JVM
       │                         │
       └── no engine jars        └── jk bsp serve (via .bsp/jk.json for BSP clients)
```

Same constraint as the VS Code extension (`clients/vscode/`).
