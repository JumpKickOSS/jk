# JumpKick for VS Code

**Wire-only IDE integration** — the extension never loads the JumpKick
engine jar; it shells the `jk` CLI on PATH (or `jk.path`) and installs BSP for import.

## Requirements

1. **JumpKick on PATH** (`jk --version`), installed via `./install.sh` / release.  
2. Workspace root contains **`jk.toml`**.  
3. Optional but recommended for classpath import: a **BSP client** extension (e.g. Metals /
   JetBrains BSP support in other IDEs). VS Code discovers servers under `.bsp/*.json`.

## Features

| Feature | How |
|---|---|
| **BSP install** | Command **JumpKick: Install BSP connection** → `jk bsp install` → `.bsp/jk.json` |
| **Tasks** | Terminal → Run Task → `jk` (`lock`, `sync`, `build`, `test`, `assemble`) |
| **Commands** | Sync / Build / Test / Lock via Command Palette |
| **Status bar** | `jk` item — click to show the JumpKick output channel |
| **Auto-prompt** | If `jk.toml` exists without `.bsp/jk.json`, offers BSP install (`jk.autoBspInstall`) |

## Install (from this repo)

### Development (F5)

```bash
# In VS Code: open clients/vscode, then Run → Start Debugging
# Or from a monorepo checkout:
code clients/vscode
```

### VSIX (install from disk)

```bash
cd clients/vscode
npm install
npm run package          # produces jumpkick-*.vsix
# VS Code: Extensions → … → Install from VSIX…
```

Or:

```bash
./scripts/package-vscode.sh
```

Marketplace publishing is optional; install-from-disk is the MVP distribution path.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `jk.path` | `jk` | Executable name or absolute path |
| `jk.autoBspInstall` | `true` | Prompt when `.bsp/jk.json` is missing |

## Architecture (hard constraint)

```
VS Code extension  ──spawn──►  jk CLI  ──wire (UDS/TCP)──►  engine JVM
       │                         │
       └── no engine jars        └── jk bsp serve (stdio BSP for import clients)
```

See the JumpKick repo docs: [IDE](../../docs/user/ide.md) and
[architecture](../../docs/contributors/architecture.md) (BSP sequence).

## Open questions (resolved for this MVP)

1. **Spawn model:** CLI subprocess (`jk …`) and `jk bsp serve` via `.bsp/jk.json` — not an
   embedded wire client in the extension process.  
2. **Engine version skew:** user-installed `jk` on PATH; extension does not pin a bundled engine.
