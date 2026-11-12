# JumpKick for VS Code

**JumpKick is the build system and agentic workbench for Java, an alternative to Maven and
Gradle.** The fastest recompiles and re-tests, plus code intelligence your coding agent can use,
with live feedback on every build, test and agent run.

This extension brings the `jk` command into VS Code for any folder with a `jk.toml`.

## Features

| Feature | How |
|---|---|
| **Commands** | Command Palette → **JumpKick: Build**, **Test**, **Sync dependencies**, **Lock** |
| **Tasks** | Terminal → Run Task → `jk` (`lock`, `sync`, `build`, `test`, `assemble`), or a `"type": "jk"` task in `tasks.json` |
| **Status bar** | A `jk` item; click it to open the JumpKick output channel |
| **BSP connection** | **JumpKick: Install BSP connection** runs `jk bsp install` and writes `.bsp/jk.json` for BSP-aware tools. The extension offers it when a `jk.toml` folder has none |

Run `jk web` in a terminal to watch every build, test and agent run live in the browser.

## Requirements

Install JumpKick so `jk` is on your PATH:

```bash
curl -fsSL https://jumpkick.build/install.sh | bash      # macOS, Linux
irm https://jumpkick.build/install.ps1 | iex              # Windows PowerShell
```

The extension runs the `jk` you installed. It does not bundle or pin one.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `jk.path` | `jk` | The `jk` executable, absolute or on PATH. Read from user settings only: a workspace cannot choose the program this extension runs |
| `jk.autoBspInstall` | `true` | Offer `jk bsp install` when a `jk.toml` folder has no `.bsp/jk.json` |

In an untrusted workspace the extension does not offer the BSP install.

## Learn more

- [jumpkick.build](https://jumpkick.build)
- [IDE support](https://github.com/JumpKickOSS/jk/blob/main/docs/user/ide.md)
- [Source](https://github.com/JumpKickOSS/jk/tree/main/clients/vscode), Apache-2.0

## Development

Open `clients/vscode` in VS Code and press F5. To build a VSIX:

```bash
cd clients/vscode
npm install
npm run package          # writes jumpkick-<version>.vsix
```
