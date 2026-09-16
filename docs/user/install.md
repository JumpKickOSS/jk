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

Native clients are hosted for Linux and macOS on x86_64 and aarch64, and for Windows on
x86_64. Every other host — macOS on Intel, Windows on ARM, Linux on ARM or a Raspberry Pi,
Solaris, FreeBSD, anything else a JDK 25 runs on — gets the [JVM client](#the-jvm-client) from
the same installers.

Remote installs authenticate the exact `SHA256SUMS` bytes with the built-in RSA-3072 key,
require one exact checksum entry, and hash the archive before replacing or executing anything.
Unix needs stock-compatible OpenSSL; Windows uses .NET RSA in PowerShell 5.1. Positional local
file installs remain available for development and do not require release evidence.

`JK_ARCHIVE_URL` is an advanced remote override and requires `JK_VERSION`; checksum and signature
evidence comes from `JK_RELEASES_URL/<version>/`, so an arbitrary archive URL cannot choose its
own trust metadata.

Windows PATH install dir: `%USERPROFILE%\.jk\bin` — the same `bin/` every other platform
uses. `install.ps1` prepends it to your **User PATH** (visible from cmd and PowerShell) and runs
`jk activate --yes` for profile hooks. When a new session's execution policy would be
`Restricted`/`AllSigned` (so `$PROFILE` hooks cannot load), the installer prints
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` for you to run; it applies that command
itself only with `-SetExecutionPolicy` or `JK_SET_EXECUTION_POLICY=1`, because a persistent
policy change is nothing an uninstall reverts. Group Policy that locks the policy is reported
with a note — ask an admin. Windows on ARM64 installs the `windows-x86_64` build, which runs
under x64 emulation; no `windows-aarch64` release exists. `JK_CLIENT=jvm` installs the [JVM
client](#the-jvm-client) on an ARM64 JDK instead.

Local dogfood from this repository needs a jk to build it, and no Windows client is hosted yet;
[releases](../contributors/releases.md#platforms-without-a-hosted-client) says how the first one is
produced. With one installed, `jk build --skip-tests` writes `target\dist\jk.exe` and
`.\install.cmd target\dist\jk.exe` installs it (Smart App Control blocks an unsigned `jk.exe`;
released natives are signed).

(`.\install.ps1` alone often fails under the default **Restricted** execution policy;
`install.cmd` and `irm | iex` do not.) Installing `jk.bat` parks a leftover `jk.exe` so
`jk` does not keep launching the blocked PE. See [Contributing](../../CONTRIBUTING.md).

Self-update of an installed binary: `jk self update` (verifies the release). Release
layout and signing: [contributor releases](../contributors/releases.md). Reporting a
signature or install defect: [Security](security.md).

## The JVM client

jk's client is a plain JVM program; the native binary is that program compiled ahead of time
for the hosts a release builds it for. On any other host the installers install the program
itself: `jk-<version>.jar`, one platform-neutral jar published beside the native clients and
verified against the same signed `SHA256SUMS`, plus the engine jar. `install.sh` picks it
whenever `uname` names a host no native client is hosted for; `install.ps1` picks it on
`JK_CLIENT=jvm` (or `-Jvm`). `JK_CLIENT=jvm` asks for it on a hosted platform too;
`JK_CLIENT=native` refuses to fall back.

```bash
# Linux on ARM, macOS on Intel, FreeBSD, Solaris, … — the same command
curl -fsSL https://jumpkick.build/install.sh | bash
```

```powershell
# Windows on ARM64, on an ARM64 JDK
$env:JK_CLIENT = "jvm"; irm https://jumpkick.build/install.ps1 | iex
```

What lands is the jar under `~/.jk/lib/jk/jk-<version>.jar`, the engine under
`~/.jk/lib/jk-engine/` as always, and a launcher on the PATH in place of the binary: `~/.jk/bin/jk`
(POSIX `sh`) or `%USERPROFILE%\.jk\bin\jk.bat`. `jkx` comes with it. Everything else — `jk
activate`, the engine, `jk self update` — is the same; the update replaces the jar and rewrites
the launcher instead of swapping a binary.

**You bring the JDK.** jk downloads JDKs only for hosts the JDK feed covers, and a host on this
path is by definition one it does not. The installer needs a full JDK (not a JRE) of **25 or
newer**, found as `JK_JAVA_HOME`, else `JAVA_HOME`, else `java` on the PATH; it refuses anything
older before it downloads or writes anything. The launcher looks in the same order, except that
the JDK the installer verified ranks above `JAVA_HOME`: jk's own shell hook points `JAVA_HOME` at
the current project's JDK, which may be older than the release the client is compiled for. The
build engine runs on that same JDK unless `[toolchain] jdk` in `~/.jk/config.toml` (or
`JK_ENGINE_JDK`) names another. `JK_CLIENT_OPTS` adds JVM flags to the client's launch.

A JVM starts slower than a native binary — expect a few hundred milliseconds per command rather
than tens — and everything after that is the same engine doing the same work.

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
  lib/          live engine jar, the Maven spy jar (`jk mvn`), installed app jars
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
| `JK_JDK_PROBES` | Comma-separated names of the JDK probes jk may consult (`java-home`, `jk`, `intellij`, `gradle`, `sdkman`, `jbang`, `mise`, `asdf`, `jenv`, `homebrew`, `system`). Unset is all of them; the test tiers run with `java-home,jk`. |
| `JK_AOT_TRAIN=off` | Skip AOT train-on-miss (still **use** existing `.aot` caches). CI / short-lived builds usually set this |
| `JK_WORKER_AOT=off` | Plugin workers: no AOT map and no train |
| `JK_CANCEL_GRACE_MS` | Shared cancel window for forked workers (default **500** ms, max 5000) |
| `JK_M2_INTEGRATION` | `false` skips the Maven local repo for third-party jars (same as `[m2] integration = false`) |
| `JK_M2_INSTALL` | `false` keeps `jk install` under `JK_STORE_DIR/repos/jk-local` instead of the Maven local repo (same as `[m2] install = false`) |
| `JK_CLIENT` | Installer only: `native` or `jvm`. Unset picks the native client where one is hosted and the [JVM client](#the-jvm-client) elsewhere |
| `JK_JAVA_HOME` | The JDK the JVM client (and, by default, its engine) runs on; the launcher checks it before the JDK it was installed with, `JAVA_HOME`, and the PATH |
| `JK_CLIENT_OPTS` | Extra JVM flags for the JVM client's own launch (the engine's are `JK_JVM_ARGS`) |
| `JK_ACCEPT_UNVERIFIED_TOOL` | `1`: `jk mvn` / `jk gradle` install a distribution no checksum vouches for and record its digest — the environment spelling of `--accept-unverified-tool` ([migration](migration.md)) |
| `--cache-dir <dir>` | Same as `JK_CACHE_DIR` for one command; passed to the resident engine |

Every root override must be an absolute path; shell `~` expansion does not occur inside an
environment value.

The installers and `jk activate` write the shell rc block (`# >>> jk installer >>>`) only for the
default home, `$HOME/.jk`. With `JK_HOME` pointing anywhere else they leave `~/.zshrc`,
`~/.bashrc` and `$PROFILE` alone (and, on Windows, the User PATH) and print the line that
activates that install in the current shell — `eval "$("$JK_HOME/bin/jk" activate zsh)"` — so a
scratch build or a hermetic test home never becomes the jk every new shell runs. `bash install.sh
--rc`, `install.ps1 -Rc` (`JK_RC=1` for `irm | iex`) and `jk activate --rc` ask for the block on a
non-default home anyway. The root-specific variables win over `JK_HOME`, and they compose:
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

Bare `jk activate` (and the installer's `--yes`) writes a marker block into every profile it
should own: zsh on macOS, bash on Linux, both PowerShell 7 (`pwsh`) and Windows PowerShell
5.1 (`powershell`) on Windows — plus any other supported rc file that already exists
(`.bashrc` on a zsh Mac, Git Bash, fish, …). Pass a name to print one eval snippet:
`jk activate zsh`. Under a `JK_HOME` other than `$HOME/.jk` the bare form writes nothing and
prints those snippets for that home's bin instead; `jk activate --rc` writes the block anyway.

The installer / `jk activate` writes a marker block:

```text
# >>> jk installer >>>
eval "$("$HOME/.jk/bin/jk" activate zsh)"
# <<< jk installer <<<
```

(bash: `activate bash`; fish: `"$HOME/.jk/bin/jk" activate fish | source`;
PowerShell 7: `activate pwsh`; Windows PowerShell 5.1: `activate powershell`.)

- **PATH** — prepends `~/.jk/bin` so real `jk` / `jkx` resolve. On Windows the same step
  writes the User PATH, so `cmd.exe` and GUI-launched processes see it too
- **PowerShell policy (Windows)** — if a fresh PowerShell would refuse `$PROFILE`
  (`Restricted` / `AllSigned`), sets CurrentUser to `RemoteSigned` (same as `install.ps1`)
- **Hooks** — `jk hook-env` updates `JAVA_HOME` / `GRAALVM_HOME` and swaps only those toolchain
  `bin` dirs onto your live `PATH` when you `cd` (nvm and other PATH edits are left alone). A
  project `jk-lock.toml` `[jdk]` / `[graal]` entry wins over the global default when some installed
  JDK/Graal meets the lock floor (same major or newer).
- **Completions** — bash, zsh, fish, pwsh (sourced from both PowerShell profiles)

`jk doctor` checks the login shell and the shell that launched `jk`, and warns if those
profiles have no installer block. It does not look at unused rc files, and it never writes
them — run `jk activate` to hook a newly installed shell.

## Official URLs

| URL | Role |
|-----|------|
| `https://jumpkick.build/install.sh` | Installer (Linux / macOS) |
| `https://jumpkick.build/install.ps1` | Installer (Windows / PowerShell) |
| `https://jumpkick.build/releases/` | Native clients, the JVM client jar, the engine jar, and the Maven spy jar `jk mvn` fetches on first use |
| `https://jumpkick.build/repo/` | First-party Maven repo (workers, `cc.jumpkick.*`) |

First-party coordinates never resolve from Central — [Repositories](repositories.md).

## Next

[Getting started](getting-started.md) · [Cache](cache.md) · [CI](ci.md) · [Config](config.md)
