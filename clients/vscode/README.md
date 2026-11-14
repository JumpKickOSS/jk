# JumpKick for VS Code

**JumpKick is the build system and agentic workbench for Java, an alternative to Maven and
Gradle.** The fastest recompiles and re-tests, plus code intelligence your coding agent can use,
with live feedback on every build, test and agent run.

This extension connects VS Code to the JumpKick engine for any folder with a `jk.toml`.

## Features

| Feature | How |
|---|---|
| **Commands** | Command Palette → **JumpKick: Build**, **Test**, **Lock**, **Sync dependencies**, **Cancel running build**. Each starts the same engine job the `jk` command does |
| **Status bar** | A `jk` item follows every job in the window's folders, the ones you start from a terminal or an agent included: progress while one runs, the verdict when it ends, and whether the engine is reachable. Click it for the output |
| **Output** | The **JumpKick** output channel shows each job's output and diagnostics as the engine streams them |
| **Tasks** | Terminal → Run Task → `jk` (`lock`, `sync`, `build`, `test`, `assemble`), or a `"type": "jk"` task in `tasks.json` |
| **BSP connection** | **JumpKick: Install BSP connection** runs `jk bsp install` and writes `.bsp/jk.json` for BSP-aware tools |

Run `jk web` in a terminal to watch every build, test and agent run live in the browser.

## Requirements

Install JumpKick so `jk` is on your PATH:

```bash
curl -fsSL https://jumpkick.build/install.sh | bash      # macOS, Linux
irm https://jumpkick.build/install.ps1 | iex              # Windows PowerShell
```

The extension runs the `jk` you installed to find its engine (`jk web -O jsonl` prints the address
and token, and starts the engine when none is running). It does not bundle or pin a `jk`. In a
Remote-WSL, SSH or container window it runs where the folder is, so it reaches that machine's
engine.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| `jk.path` | `jk` | The `jk` executable, absolute or on PATH. Read from user settings only: a workspace cannot choose the program this extension runs |

In an untrusted workspace the extension starts no builds.

## Learn more

- [jumpkick.build](https://jumpkick.build)
- [IDE support](https://github.com/JumpKickOSS/jk/blob/main/docs/user/ide.md)
- [Source](https://github.com/JumpKickOSS/jk/tree/main/clients/vscode), Apache-2.0

## Development

TypeScript, bundled by esbuild into `dist/extension.js`. Open `clients/vscode` in VS Code and
press F5, or from a shell:

```bash
cd clients/vscode
npm ci                       # exact versions from package-lock.json
npm run build                # type-check (strict) and bundle
npm run test:unit            # the engine client, event stream and run folding, under node --test
VSCODE_EXECUTABLE=/usr/share/code/code xvfb-run -a npm run test:integration
npm run package              # writes jumpkick-<version>.vsix
```

The integration suite starts VS Code with the extension against a stub engine and a fake `jk`, so
it needs no engine. `VSCODE_EXECUTABLE` names an installed VS Code; without it the suite downloads
the current stable one. On Linux without a display, run it under `xvfb-run -a`. CI runs the same
commands on Linux, macOS and Windows (`.github/workflows/ci.yml`, job `vscode-extension`).
