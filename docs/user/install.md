# Install

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
jk --help
```

The installer puts **`jk`** and **`jkx`** on your PATH. JumpKick requires **JDK 25+** to
run and will install one if needed. After that, prefer `java = N` in `jk.toml` for
language level — [Concepts](concepts.md).

Windows: `%USERPROFILE%\.local\bin`. Developer builds of this repository:
[Contributing](../../CONTRIBUTING.md).

Self-update of an installed binary: `jk self update` (verifies the release). Release
layout and signing: [contributor releases](../contributors/releases.md).

## On-disk layout

Product data uses platform-native locations (XDG on Linux/macOS; Windows Known Folders).

| Role | Linux / macOS | Windows |
|------|---------------|---------|
| **bin** (PATH) | `~/.local/bin` | `%USERPROFILE%\.local\bin` |
| **data** (engine lib, store/CAS) | `~/.local/share/jk` | `%LOCALAPPDATA%\jk\data` |
| **cache** (action cache) | `~/.cache/jk` | `%LOCALAPPDATA%\jk\cache` |
| **state** (engine socket, build history) | `~/.local/state/jk` | `%LOCALAPPDATA%\jk\state` |
| **config** | `~/.config/jk/config.toml` | `%APPDATA%\jk\config.toml` |
| **managed JDKs** | Linux: `~/.jdks` · macOS: `~/Library/Java/JavaVirtualMachines` | `%USERPROFILE%\.jdks` |

**Artifact store** (dependency CAS + `repos/`) lives under **data** (`…/store`). **Cache
CAS** (action outputs) lives under **cache** (`…/cache/sha256`). The live engine jar is
`<data>/lib/jk-engine/<jar>` with metadata in `<config>/jk-engine/config.toml`
(or `$JK_HOME/lib/jk-engine/…` + `$JK_HOME/config/jk-engine/config.toml`).

Managed JDKs use the **IntelliJ shared root** so the IDE and JumpKick share runtimes.
Discovery still picks up SDKMAN, mise, Homebrew, `JAVA_HOME`, and system installs before
downloading. See [JDK](jdk.md).

XDG variables (`XDG_CACHE_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME`, `XDG_CONFIG_HOME`,
`XDG_BIN_HOME`) are honored on Linux and macOS when `JK_HOME` is unset.

## Environment overrides

| Env / flag | Effect |
|------------|--------|
| `JK_HOME` | Optional **single-tree umbrella** for product dirs (`config/`, `cache/`, `store/`, `state/`, `data/`, `bin/`, `lib/`). Hermetic tests and cold CI roots. Does **not** move the default JDK root. Global prefs: `$JK_HOME/config/config.toml`; per-app install config: `$JK_HOME/config/<bin>/config.toml`. |
| `JK_CACHE_DIR` | Action / local CPU cache |
| `JK_STORE_DIR` | CAS / network-expensive store |
| `JK_STATE_DIR` | Engine sockets, build history |
| `JK_DATA_DIR` | Product data root (engine lib + default store parent) |
| `JK_BIN_DIR` / `JK_INSTALL_DIR` | PATH install directory for `jk` / `jkx` |
| `JK_CONFIG_DIR` | Config root (global `config.toml` + per-app `<bin>/config.toml`). Default: `$JK_HOME/config` or `~/.config/jk` |
| `JK_CONFIG_FILE` | Absolute path to the global `config.toml` |
| `JK_JDKS_DIR` | Managed JDK **write** root. Set with `JK_HOME` for hermetic JDK isolation |
| `JK_AOT_TRAIN=off` | Skip AOT train-on-miss (still **use** existing `.aot` caches). CI / short-lived builds usually set this |
| `JK_WORKER_AOT=off` | Plugin workers: no AOT map and no train |
| `JK_CANCEL_GRACE_MS` | Shared cancel window for forked workers (default **500** ms, max 5000) |
| `--cache-dir <dir>` | Same as `JK_CACHE_DIR` for one command; passed to the resident engine |

Role-specific `JK_*_DIR` always wins over `JK_HOME` / XDG.

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
eval "$("$HOME/.local/bin/jk" activate zsh)"
# <<< jk installer <<<
```

(bash: `activate bash`; fish: `"$HOME/.local/bin/jk" activate fish | source`.)

- **PATH** — prepends the platform bin so real `jk` / `jkx` resolve
- **Hooks** — `jk hook-env` updates `JAVA_HOME` / `PATH` when you `cd`
- **Completions** — bash, zsh, fish, pwsh

## Official URLs

| URL | Role |
|-----|------|
| `https://jumpkick.build/install.sh` | Installer |
| `https://jumpkick.build/releases/` | Native client + engine jar |
| `https://jumpkick.build/repo/` | First-party Maven repo (workers, `cc.jumpkick.*`) |

First-party coordinates never resolve from Central — [Repositories](repositories.md).

## Next

[Getting started](getting-started.md) · [Cache](cache.md) · [CI](ci.md) · [Config](config.md)
