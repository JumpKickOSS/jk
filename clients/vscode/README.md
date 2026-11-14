# JumpKick for VS Code

**JumpKick is the build system and agentic workbench for Java, an alternative to Maven and
Gradle.** The fastest recompiles and re-tests, plus code intelligence your coding agent can use,
with live feedback on every build, test and agent run.

This extension connects VS Code to the JumpKick engine for any folder with a `jk.toml`.

## Features

| Feature | How |
|---|---|
| **Java projects** | Open a `jk.toml` folder and the Java language server (Language Support for Java by Red Hat, installed with this extension) gets one project per module, with its source roots, sibling modules, libraries and their sources, and its Java level, straight from the engine. Edit `jk.toml` or run `jk add` and the classpath follows after two quiet seconds, with no reload and no `.project` or `.classpath` files in your tree |
| **Commands** | Command Palette → **JumpKick: Build**, **Test**, **Lock**, **Sync dependencies**, **Cancel running build**. Each starts the same engine job the `jk` command does |
| **Status bar** | A `jk` item follows every job in the window's folders, the ones you start from a terminal or an agent included: progress while one runs, the verdict when it ends, and whether the engine is reachable. Click it for the output |
| **Problems** | When a job ends, its compile errors and warnings, a `jk.toml` that does not parse or validate, and a dependency that cannot be resolved appear in the Problems panel at their file and line; the next green run clears them |
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

The Java language server loads `server/jk-jdtls.jar` (sources in `jdtls/`), an importer bundle
that applies the engine's `GET /api/project/model` (`docs/contributors/http.md`). `npm run
build:jdtls` compiles it against the Eclipse bundles inside the redhat.java VSIX that
`jdtls/deps.json` pins (downloaded once from Open VSX into `node_modules/.cache/redhat-java/`, sha256 checked) and writes
the jar reproducibly; `npm run test:jdtls` also runs its model test. Both need a JDK 21 or newer.
`npm run package` puts the jar in the VSIX.

The integration suite starts VS Code with the extension against a stub engine and a fake `jk`, so
it needs no engine; it installs the pinned redhat.java into its scratch profile in LightWeight
mode. With `JK_TEST_REAL_JK=<jk>` it runs against the engine that `jk` starts instead, and with
`JK_TEST_JAVA=1` (a two-module workspace) or `JK_TEST_JAVA=single` as well, it runs the Java
language server cases: one project per module, a sibling's and a Central library's classes
resolving with sources, no project files in the tree, and a dependency added to `jk.toml` picked up
without a reload. Those cases need network access to Maven Central. `VSCODE_EXECUTABLE` names an installed VS Code; without it the suite downloads
the current stable one. On Linux without a display, run it under `xvfb-run -a`. CI runs the same
commands on Linux, macOS and Windows (`.github/workflows/ci.yml`, job `vscode-extension`).
