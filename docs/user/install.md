# Install

```bash
# Linux / macOS
curl -fsSL https://jumpkick.build/install.sh | bash
jk --help
```

```powershell
# Windows (PowerShell 5.1+ / PowerShell 7+)
irm https://jumpkick.build/install.ps1 | iex
jk --help
```

The installer puts **`jk`** and **`jkx`** on your PATH. JumpKick requires **JDK 25+** to
run and will install one if needed. After that, prefer `java = N` in `jk.toml` for
language level — [Concepts](concepts.md).

Windows PATH install dir: `%USERPROFILE%\.jk\bin` — the same `bin/` every other platform
uses. `install.ps1` prepends it to your **User PATH** (visible from cmd and PowerShell) and
runs `jk activate --yes` for profile hooks.

Local dogfood from this repository:

```powershell
# Thin JVM client (supported on Windows; Smart App Control blocks unsigned jk.exe)
.\gradlew :cli:installDist installLocal
.\install.cmd clients\cli\build\install\jk\bin\jk.bat

# Native image — needs unsigned PE runnable (SAC off) or a signed release
.\gradlew dist
.\install.cmd build\dist\jk.exe
```

(`.\install.ps1` alone often fails under the default **Restricted** execution policy;
`install.cmd` and `irm | iex` do not.) Installing `jk.bat` parks a leftover `jk.exe` so
`jk` does not keep launching the blocked PE. See [Contributing](../../CONTRIBUTING.md).

Self-update of an installed binary: `jk self update` (verifies the release). Release
layout and signing: [contributor releases](../contributors/releases.md).

## On-disk layout

Everything jk owns lives under **one directory**, `$HOME/.jk`, and it is the same tree on
Linux, macOS and Windows. There is no platform table to consult and no environment variable
to decode: if you want to know where a file is, it is under `~/.jk`.

```
~/.jk/
  config.toml   your settings
  bin/          PATH launchers: jk, jkx, and `jk tool install` shims
  cache/        action cache + cache CAS (rebuildable; costs local CPU)
  config/       per-app config, <bin>/config.toml
  creds/        forge tokens (creds/forge) and per-repo credentials (creds/repo)
  lib/          live engine jar + installed app jars
  state/        engine socket, AOT caches, build history, JDK inventory, scratch
  store/        artifact store: repos/, tools/, templates/, completions/, jdks.json
```

On Windows that is `%USERPROFILE%\.jk`, with the same children.

**store vs cache** is the one distinction worth learning, because it decides what a delete
costs you. `cache/` holds bytes jk can rebuild with local CPU. `store/` holds bytes it would
have to re-download from Maven Central, which enforces a per-IP quota. So:

```bash
rm -rf ~/.jk/cache     # rebuild locally, cheap
rm -rf ~/.jk/store     # re-download everything, slow
```

`creds/`, `bin/` and `lib/` are separate roots on purpose: they are the deletion units, so
`jk self nuke --store` cannot log you out or remove the engine you are running.

The live engine jar is `~/.jk/lib/jk-engine/jk-engine-<version>.jar` (or
`jk-engine-<version>.<epoch>.jar` when that name is already occupied), with the live pointer
in `~/.jk/lib/jk-engine/jk-engine.toml`.

**Managed JDKs are the one exception** and stay outside `~/.jk`: they use the IntelliJ shared
root (`~/.jdks`, or `~/Library/Java/JavaVirtualMachines` on macOS) so the IDE and JumpKick
share runtimes. JumpKick records those installs in `~/.jk/state/jk-jdks.toml` (defaults +
fingerprints). Access times are not tracked: jk never removes a JDK on its own, so
`jk jdk uninstall` is the only path. Discovery still picks up SDKMAN, mise, Homebrew,
`JAVA_HOME`, and system installs before downloading. See [JDK](jdk.md).

## Environment overrides

Five names, and `JK_HOME` is the only one most people need.

| Env / flag | Effect |
|------------|--------|
| `JK_HOME` | jk's home directory. Default `$HOME/.jk`; relocating it moves the whole tree. Hermetic tests, cold CI roots, installing to `/opt`. Does **not** move the JDK root. |
| `JK_STORE_DIR` | Store root only — the big, network-expensive one. For putting the artifact store on another filesystem. |
| `JK_CACHE_DIR` | Cache root only. Same reason, plus isolating the action cache without forcing a cold store. |
| `JK_STATE_DIR` | State root only (engine sockets, build history, JDK inventory). |
| `JK_JDKS_DIR` | Managed JDK **write** root. Set it with `JK_HOME` for hermetic JDK isolation. |
| `JK_AOT_TRAIN=off` | Skip AOT train-on-miss (still **use** existing `.aot` caches). CI / short-lived builds usually set this |
| `JK_WORKER_AOT=off` | Plugin workers: no AOT map and no train |
| `JK_CANCEL_GRACE_MS` | Shared cancel window for forked workers (default **500** ms, max 5000) |
| `JK_M2_INTEGRATION` | `false` skips the Maven local repo for third-party jars (same as `[m2] integration = false`) |
| `JK_M2_INSTALL` | `false` keeps `jk install` under `JK_STORE_DIR/repos/jk-local` instead of the Maven local repo (same as `[m2] install = false`) |
| `--cache-dir <dir>` | Same as `JK_CACHE_DIR` for one command; passed to the resident engine |

Every root override must be an absolute path; shell `~` expansion does not occur inside an
environment value. The root-specific variables win over `JK_HOME`, and they compose:
`JK_HOME=/tmp/cold JK_STORE_DIR=$HOME/.jk/store` gives you a scratch everything with a warm store.

Cold resolve without wiping your real store:

```bash
COLD=$(mktemp -d /tmp/jk-cold-XXXX)
jk lock --cache-dir "$COLD"
```

The engine process is keyed by **state directory + store**; isolating only the action
cache leaves CAS reuse intact.

## `jk env`

Build-visible environment is layered (lowest → highest): workspace `.env`, module `.env`,
then the real process environment. The **shell always wins** over files.

```bash
jk env                 # .env keys + JK_* from the process
jk env -v              # also show file values shadowed by the shell
jk env --all           # every process env key (noisy)
jk env --output json
```

Values that came from a `.env` file are treated as secrets: shown as `***` and labeled
`secret`.

## Shell integration

```bash
jk activate --yes              # PATH + hooks + completions in your shell rc
jk activate bash               # print the eval snippet
jk deactivate                  # how to drop session env or remove the marker block
jk completion                  # refresh completion files
```

The installer / `jk activate` writes a marker block:

```text
# >>> jk installer >>>
eval "$("$HOME/.jk/bin/jk" activate zsh)"
# <<< jk installer <<<
```

(bash: `activate bash`; fish: `"$HOME/.jk/bin/jk" activate fish | source`.)

- **PATH** — prepends `~/.jk/bin` so real `jk` / `jkx` resolve. On Windows the same step
  writes the User PATH, so `cmd.exe` and GUI-launched processes see it too
- **Hooks** — `jk hook-env` updates `JAVA_HOME` / `GRAALVM_HOME` and swaps only those toolchain
  `bin` dirs onto your live `PATH` when you `cd` (nvm and other PATH edits are left alone). A
  project `jk-lock.toml` `[jdk]` / `[graal]` entry wins over the global default when some installed
  JDK/Graal meets the lock floor (same major or newer).
- **Completions** — bash, zsh, fish, pwsh

## Official URLs

| URL | Role |
|-----|------|
| `https://jumpkick.build/install.sh` | Installer (Linux / macOS) |
| `https://jumpkick.build/install.ps1` | Installer (Windows / PowerShell) |
| `https://jumpkick.build/releases/` | Native client + engine jar |
| `https://jumpkick.build/repo/` | First-party Maven repo (workers, `cc.jumpkick.*`) |

First-party coordinates never resolve from Central — [Repositories](repositories.md).

## Next

[Getting started](getting-started.md) · [Cache](cache.md) · [CI](ci.md) · [Config](config.md)
