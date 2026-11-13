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

The installers and the [wrapper](wrapper.md) ask the release which client to install: when its
signed `SHA256SUMS` lists a native client for your OS and architecture (today Linux on x86_64 and
aarch64, Windows on x86_64 and macOS on Apple silicon), you get that. Every other host — macOS on
Intel, Windows on ARM, 32-bit ARM or a Raspberry Pi, Solaris, AIX, FreeBSD, anything else a JDK 25
runs on — gets the [JVM client](#the-jvm-client).

Remote installs authenticate the exact `SHA256SUMS` bytes with the built-in RSA-3072 key,
require one exact checksum entry, and hash the archive before replacing or executing anything.
Unix needs stock-compatible OpenSSL; Windows uses .NET RSA in PowerShell 5.1. Positional local
file installs remain available for development and do not require release evidence.

`JK_ARCHIVE_URL` is an advanced remote override and requires `JK_VERSION`; checksum and signature
evidence comes from `JK_RELEASES_URL/<version>/`, so an arbitrary archive URL cannot choose its
own trust metadata.

Windows PATH install dir: `%USERPROFILE%\.jk\bin` — the same `bin/` every other platform
uses. `install.ps1` prepends it to your **User PATH** (visible from cmd and PowerShell) and runs
`jk activate -y` for profile hooks. When a new session's execution policy would be
`Restricted`/`AllSigned` (so `$PROFILE` hooks cannot load), the installer prints
`Set-ExecutionPolicy -Scope CurrentUser RemoteSigned` for you to run; it applies that command
itself only with `-SetExecutionPolicy` or `JK_SET_EXECUTION_POLICY=1`, because a persistent
policy change is nothing an uninstall reverts. Group Policy that locks the policy is reported
with a note — ask an admin.

Local dogfood from this repository needs an installed JumpKick to build it; the hosted `jk.exe` the installer
puts in place is one. With it installed, `jk build -S` writes `target\dist\jk.exe` and
`.\install.cmd target\dist\jk.exe` installs it (Smart App Control blocks an unsigned `jk.exe`;
released natives are signed).

(`.\install.ps1` alone often fails under the default **Restricted** execution policy;
`install.cmd` and `irm | iex` do not.) Installing `jk.bat` parks a leftover `jk.exe` so
`jk` does not keep launching the blocked PE. See [Contributing](../../CONTRIBUTING.md).

Self-update of an installed binary: `jk self update` (verifies the release); JumpKick also
[updates itself](#updates). Release layout and signing: [contributor
releases](../contributors/releases.md). Reporting a signature or install defect:
[Security](security.md).

## Updates

JumpKick keeps itself current. The engine checks the signed latest-release pointer at most every
12 hours (a conditional request, so an unchanged pointer costs one round trip and no download), when it has had no build for 10 minutes or after the third build in a row that never left it
that quiet. When there is a newer release it downloads and verifies it, installs it, and starts the
new engine; builds still running finish on the old one. A build that starts in the middle pauses
the update until the next quiet spell (or three more builds). Your next command runs the new release.

```bash
jk self update --check      # is there a newer release? installs nothing
jk self update --download   # fetch and verify it now; install later
jk self update              # install the latest (a download already made is reused)
jk self update 0.16.2       # go back to a release, and hold there
jk self rollback            # put back the engine and client the last install replaced
```

`jk self rollback` needs no network and no running engine: the store keeps the engine and client
the last install replaced, so an install that left a JumpKick that fails every command can still be
undone. A second rollback undoes the first.

`self-update` in `~/.jk/config.toml` (or env `JK_SELF_UPDATE`, which wins) picks the behaviour:

| Value | What happens |
|-------|--------------|
| `"auto"` | Updates as above, without asking. The default on CI and wherever no one answers a terminal (coding agents, scripts) |
| `"ask"` | The engine downloads and waits; the offer below is the only way in. The default on a machine where someone uses JumpKick at a terminal |
| `"never"` | No checks and no downloads. `jk self update` still updates by hand |

Once a newer release is downloaded, the next command you run at a terminal offers it after its
own output:

```text
 ↑ Update  A new version of JumpKick is available. Update Now?  Yes  No
```

Yes (or Enter) installs it now; running builds finish on the old engine. No asks again after the
next check, 12 hours at most, or as soon as a newer release is downloaded; it quiets the same offer
in the web console and in MCP clients too. Commands that failed, print machine output (`--json`,
`-O`, `--agent`) or run without a terminal never ask.

After an update, the web console's bell says "JumpKick was updated to X (from Y)" once and opens
a **What's new** dialog with the highlights of every release you moved across
([What's new](whats-new.md)); the first command you run at a terminal prints the same line with a
link to the release notes.

Before 1.0 updates are compulsory, and the engine installs a downloaded release at its next quiet
spell whatever you answered; Yes only gets it sooner. `"ask"` and `"never"` in the config file are ignored (JumpKick says
so once), and only `JK_SELF_UPDATE=never` turns them off. Development builds (`-SNAPSHOT`) never
update themselves. On CI, a JumpKick home less than a day old (a fresh runner) does not update itself;
the installer or wrapper that created it already fetched the latest release.

`jk self update <older>` holds this JumpKick home at that release: nothing checks, downloads or offers an
update until a plain `jk self update` (or any update to the same or a newer release) clears the
hold. `jk engine status` shows the hold, the last check, and a download waiting to install.

A command started by the release that was just replaced hands itself to the new one. A running
`jk mcp` keeps serving its agent across the update: it reconnects to the new engine and tells the
agent's host to read the tool list again. (A `jk mcp` started by a release before 0.51.0 predates this
and stops once, asking to be started again.) Other long-running clients from before the update (a
watch) stop and ask to be started again rather than displace the newer engine.

An MCP client whose host shows elicitations is asked once per session, before its next tool call:
"A new version of JumpKick (X) is downloaded. Update now?" Accept installs it (that call still
finishes); decline is the same "not now" as everywhere else. A client that cannot be asked gets one
line in that call's result telling the user how to install it; the agent itself is never offered a
way to install.

## The JVM client

JumpKick's client is a plain JVM program; the native binary is that program compiled ahead of time
for the hosts a release builds it for. On any other host the installers install the program
itself: `jk-<version>.jar`, one platform-neutral jar published beside the native clients and
verified against the same signed `SHA256SUMS`, plus the engine jar. `install.sh`, `install.ps1`
and the wrapper pick it whenever that `SHA256SUMS` lists no `jk-<os>-<arch>-<version>` client for
the host. `JK_CLIENT=jvm` (or `install.ps1 -Jvm`) asks for it where a native client exists too;
`JK_CLIENT=native` refuses to fall back.

```bash
# macOS on Intel, FreeBSD, Solaris, … — the same command
curl -fsSL https://jumpkick.build/install.sh | bash
```

```powershell
# Windows on ARM64 — the same command, on an ARM64 JDK
irm https://jumpkick.build/install.ps1 | iex
```

What lands is the jar under `~/.jk/lib/jk/jk-<version>.jar`, the engine under
`~/.jk/lib/jk-engine/` as always, and a launcher on the PATH in place of the binary: `~/.jk/bin/jk`
(POSIX `sh`) or `%USERPROFILE%\.jk\bin\jk.bat`. `jkx` comes with it. Everything else — `jk
activate`, the engine, `jk self update` — is the same; the update replaces the jar and rewrites
the launcher instead of swapping a binary. Once a release lists a native client for the host,
`jk self update` installs that in place of the JVM client (`JK_CLIENT=jvm` keeps the JVM client).

**You bring the JDK.** JumpKick downloads JDKs only for hosts the JDK feed covers, and a host on this
path is by definition one it does not. The installer needs a full JDK (not a JRE) of **25 or
newer**, found as `JK_JAVA_HOME`, else `JAVA_HOME`, else `java` on the PATH; it refuses anything
older before it downloads or writes anything. The launcher looks in the same order, except that
the JDK the installer verified ranks above `JAVA_HOME`: JumpKick's own shell hook points `JAVA_HOME` at
the current project's JDK, which may be older than the release the client is compiled for. The
build engine runs on that same JDK unless `[toolchain] jdk` in `~/.jk/config.toml` (or
`JK_ENGINE_JDK`) names another. `JK_CLIENT_OPTS` adds JVM flags to the client's launch.

A JVM starts slower than a native binary — expect a few hundred milliseconds per command rather
than tens — and everything after that is the same engine doing the same work.

## On-disk layout

Everything JumpKick owns lives under **one directory**, `$HOME/.jk`, and it is the same tree on
Linux, macOS and Windows. There is no platform table to consult and no environment variable
to decode: if you want to know where a file is, it is under `~/.jk`.

```
~/.jk/
  config.toml   your settings
  bin/          PATH launchers: jk, jkx, and `jk tool install` shims
  cache/        action cache + cache CAS (rebuildable; costs local CPU)
  changes/      what JumpKick changed outside your projects, and who asked
  config/       per-app config, <bin>/config.toml
  creds/        forge tokens (creds/forge) and per-repo credentials (creds/repo)
  lib/          live engine jar, the Maven spy jar (`jk mvn`), installed app jars
  state/        engine socket, build history, JDK inventory, scratch
  store/        artifact store: repos/, tools/, templates/, completions/, jdks.json
```

On Windows that is `%USERPROFILE%\.jk`, with the same children.

**store vs cache** is the one distinction worth learning, because it decides what a delete
costs you. `cache/` holds bytes JumpKick can rebuild with local CPU. `store/` holds bytes it would
have to re-download from Maven Central, which enforces a per-IP quota. So:

```bash
rm -rf ~/.jk/cache     # rebuild locally, cheap
rm -rf ~/.jk/store     # re-download everything, slow
```

`creds/`, `bin/` and `lib/` are separate roots on purpose: they are the deletion units, so
`jk self nuke -S` cannot log you out or remove the engine you are running. `changes/` is one too,
so the record of a nuke survives it ([what JumpKick changed](config.md#what-jumpkick-changed)).

The live engine jar is `~/.jk/lib/jk-engine/jk-engine-<version>.jar` (or
`jk-engine-<version>.<epoch>.jar` when that name is already occupied), with the live pointer
in `~/.jk/lib/jk-engine/jk-engine.toml`. `jk-shelf.toml` beside it names, by sha256, the
first-party worker jars and POMs that engine was installed with and the checkout they came from;
the engine launches its workers by those shas, so a later install from another checkout cannot
swap a worker or its dependency list under it.

**Managed JDKs are the one exception** and stay outside `~/.jk`: they use the IntelliJ shared
root (`~/.jdks`, or `~/Library/Java/JavaVirtualMachines` on macOS) so the IDE and JumpKick
share runtimes. JumpKick records those installs in `~/.jk/state/jk-jdks.toml` (defaults +
fingerprints). Access times are not tracked: JumpKick never removes a JDK on its own, so
`jk jdk uninstall` is the only path. Discovery still picks up JDKs other tools installed or point at (SDKMAN, mise,
Jabba, Coursier, Maven `toolchains.xml`, Gradle properties, system installs, and more) before
downloading. See [JDK](jdk.md).

## Environment overrides

Five names, and `JK_HOME` is the only one most people need.

| Env / flag | Effect |
|------------|--------|
| `JK_HOME` | JumpKick's home directory. Default `$HOME/.jk`; relocating it moves the whole tree. Hermetic tests, cold CI roots, installing to `/opt`. Does **not** move the JDK root. |
| `JK_STORE_DIR` | Store root only — the big, network-expensive one. For putting the artifact store on another filesystem. |
| `JK_CACHE_DIR` | Cache root only. Same reason, plus isolating the action cache without forcing a cold store. |
| `JK_STATE_DIR` | State root only (engine sockets, build history, JDK inventory). |
| `JK_JDKS_DIR` | Managed JDK **write** root. Set it with `JK_HOME` for hermetic JDK isolation. |
| `JK_JDK_PROBES` | Comma-separated names of the JDK probes JumpKick may consult (`java-home`, `jk`, `intellij`, `gradle`, `sdkman`, `jbang`, `mise`, `asdf`, `jenv`, `homebrew`, `jabba`, `coursier`, `system`, `maven-toolchains`, `gradle-properties`, `jdk-paths`). Unset is all of them; the test tiers run with `java-home,jk`. |
| `JK_JDK_PATHS` | Comma-separated JDK homes discovery adds (source `jdk-paths`); each is the home itself, not a directory to search. A missing path is skipped. |
| `JK_JDK_FROM_ENV` | Comma-separated names of environment variables whose values are JDK homes discovery adds (source `jdk-paths`) |
| `JK_WORKER_AOT=off` | The java-compiler, kotlinc, groovyc, formatter and graph workers neither map nor record their JEP 514 startup cache (CI and short-lived engines) |
| `JK_WORKER_LINGER_MIN` | How long a warm worker (the Java, Kotlin and Groovy compilers, the formatter, the graph worker, the build-script host) stays up after its last request, in minutes. Default 30; `0` starts a fresh worker every time. A warm worker still exits at once when another fork needs its memory. On CI (`CI` true) the most recently used Java compiler and kotlinc workers ignore it and stay up for the life of the engine |
| `JK_ENGINE_AOT=off` | The engine neither maps nor records its own JEP 514 startup cache. Otherwise an idle engine records one per install (about 10 s, once, skipped under `CI`) and every later engine maps it: a build on a fresh engine starts ~0.4 s sooner |
| `JK_CANCEL_GRACE_MS` | Shared cancel window for forked workers (default **500** ms, max 5000) |
| `JK_M2_INTEGRATION` | `false` skips the Maven local repo for third-party jars (same as `[m2] integration = false`) |
| `JK_INSTALL_DESTINATION` | `m2` (the Maven local repo, default) or `jk` (`JK_STORE_DIR/repos/jk-local`): where `jk install` puts a project that names no target (same as `[install] destination` in `config.toml`) |
| `JK_CLIENT` | Installers and wrapper only: `native` or `jvm`. Unset picks the native client when the release lists one for the host, else the [JVM client](#the-jvm-client) |
| `JK_JAVA_HOME` | The JDK the JVM client (and, by default, its engine) runs on; the launcher checks it before the JDK it was installed with, `JAVA_HOME`, and the PATH |
| `JK_CLIENT_OPTS` | Extra JVM flags for the JVM client's own launch (the engine's are `JK_JVM_ARGS`) |
| `JK_ACCEPT_UNVERIFIED_TOOL` | `1`: `jk mvn` / `jk gradle` / `jk tool install` install a distribution no checksum vouches for and record its digest — the environment spelling of `--accept-unverified-tool` ([migration](migration.md)) |
| `--cache-dir <dir>` | Same as `JK_CACHE_DIR` for one command; passed to the resident engine |

Every root override must be an absolute path; shell `~` expansion does not occur inside an
environment value.

The installers and `jk activate` write the shell rc block (`# >>> jk installer >>>`) only for the
default home, `$HOME/.jk`. With `JK_HOME` pointing anywhere else they leave `~/.zshrc`,
`~/.bashrc` and `$PROFILE` alone (and, on Windows, the User PATH) and print the line that
activates that install in the current shell — `eval "$("$JK_HOME/bin/jk" activate zsh)"` — so a
scratch build or a hermetic test home never becomes the `jk` every new shell runs. `bash install.sh
--rc`, `install.ps1 -Rc` (`JK_RC=1` for `irm | iex`) and `jk activate -R` ask for the block on a
non-default home anyway. The root-specific variables win over `JK_HOME`, and they compose:
`JK_HOME=/tmp/cold JK_STORE_DIR=$HOME/.jk/store` gives you a scratch everything with a warm store.
Such a home builds against that store but never installs into it: its `jk install`, and the
installer's shelf step for a local dist, refuse to write the default home's `repos/jk-local`,
whose pins this home cannot record.

Cold resolve without wiping your real store:

```bash
COLD=$(mktemp -d /tmp/jk-cold-XXXX)
jk lock -d "$COLD"
```

The engine process is keyed by **state directory + store**; isolating only the action
cache leaves CAS reuse intact.

## `jk env`

Build-visible environment is layered (lowest → highest): workspace `.env`, module `.env`,
then the real process environment. The **shell always wins** over files. The same layers reach
what JumpKick starts: the app and sidecars of `jk run` / `jk dev` (from the module's files),
`jk tool run` and an installed tool's launcher (from the directory they run in — the workspace
root's `.env`, then that directory's).

```bash
jk env                 # .env keys + JK_* from the process
jk env -v              # also show file values shadowed by the shell
jk env -A              # every process env key (noisy)
jk env -O jsonl
eval "$(jk env --export)"   # set the .env values this shell lacks (cmd.exe: `set` lines)
```

Values that came from a `.env` file are treated as secrets: shown as `***` and labeled
`secret`.

## Shell integration

```bash
jk activate -y              # PATH + hooks + completions in your shell rc
jk activate bash               # print the eval snippet
jk deactivate                  # how to drop session env or remove the marker block
jk completion                  # refresh completion files
```

Bare `jk activate` (and the installer's `--yes`) writes a marker block into every profile it
should own: zsh on macOS, bash on Linux, both PowerShell 7 (`pwsh`) and Windows PowerShell
5.1 (`powershell`) on Windows — plus any other supported rc file that already exists
(`.bashrc` on a zsh Mac, Git Bash, fish, …). Pass a name to print one eval snippet:
`jk activate zsh`. Under a `JK_HOME` other than `$HOME/.jk` the bare form writes nothing and
prints those snippets for that home's bin instead; `jk activate -R` writes the block anyway.

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

## Windows host checks

On Windows, `jk doctor` adds one row per host condition that slows or blocks JumpKick. Every row
is read as a standard user, with no UAC prompt, and the section answers within a second. Each
warning names its fix; `jk doctor -O jsonl` and the MCP `doctor` tool carry the same rows as a
`windows` array of `{id, label, status, detail, fix}`.

| Row | Warns when |
|---|---|
| `smart app control` | Smart App Control is on or in evaluation: it blocks binaries you build (native images, `jpackage` apps, test fixtures, JNI DLLs). Turn it off in Windows Security; it can be turned back on |
| `powershell` | A new Windows PowerShell or `pwsh` would refuse the JumpKick profile block (`Restricted`, `AllSigned`), naming Group Policy when that is who set it |
| `defender` | Defender scans `JK_HOME` or this project: a check found it not excluded, or you declined the exclusion. A standard user cannot read Defender's exclusions, so before any check the row only says they are unchecked |
| `folders` | `JK_HOME` or the project is under OneDrive, or under a folder Controlled Folder Access guards |
| `long paths` | Windows' `LongPathsEnabled` or git's `core.longpaths` is off |
| `volumes` | The project and `JK_HOME` are on different volumes, so the cache copies files instead of linking them; the row also names the filesystem (NTFS, ReFS) |
| `arch` | An x64 `jk` or JDK runs under ARM64 emulation |

### `jk doctor --fix`: set up the machine once

`jk doctor --fix` puts the recommended changes to you as one decision. Each change is a row with
its own checkbox, ticked to start, saying what it does, why, and its effect: Defender exclusions for
the folders where you keep code (with what the scan costs there, measured by writing and reading
back a batch of build-sized files), Windows and git long paths, and the PowerShell policy the
profile block needs. Approve once, and Windows shows one UAC prompt for the changes that need an
administrator. Afterwards the same probe runs again, and `jk doctor` shows the before and after.
`-y` takes every change without asking.

JumpKick finds the folders itself: `JK_HOME`, the projects it has built, IntelliJ's and VS Code's
recent projects, and the usual container folders (`IdeaProjects`, `source\repos`, `src`, `dev`,
`git`, `<drive>:\src`, …) that hold projects within two levels. It proposes the container, not
each project. It never proposes a drive root, your profile, Documents or Desktop themselves,
Downloads, AppData (except `JK_HOME`), Windows or the program folders. The elevated script
enforces the same rules again, adds each folder as a literal path, and then asks Defender which
folders it actually excludes.

The decision opens in the engine's queue, so it shows wherever you are: the terminal that ran
`jk doctor --fix`, the dashboard's bell (as a dialog listing the changes), a desktop notification
that opens that dialog, `jk decide`, or the agent's host when an agent raised it through the MCP
`doctor` tool with `fix=true`. Any of them may answer; the UAC prompt is Windows' own consent.

You don't have to run it to be asked. After your first successful `jk build` at a terminal, the
same decision arrives once as a notification, never as a prompt in the build's output. A run by
an agent, with `-a`, with `-O jsonl` or without a terminal never raises it. If it expires
unanswered, that counts as declined.

To answer in advance, for a managed or scripted machine, set it in `~/.jk/config.toml`:

```toml
[windows]
fix = "apply"   # ask (default) | apply | never
```

`apply` takes every recommended change without asking; Windows still shows its one UAC prompt.
`never` records a decline, and `jk doctor --fix` then asks only with `-y`.

The answer is remembered for this machine in `~/.jk/state/windows-host.toml`. Accepted or
declined, an agent does not raise it again; run `jk doctor --fix` to change your mind. The
exclusions are a strong recommendation, never a requirement: if you decline (many people build
inside a WSL guest instead), JumpKick works as before, and `jk doctor` and the dashboard's Status
view show one quiet `slower: Defender scans …` line.

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
