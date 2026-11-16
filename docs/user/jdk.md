# JDK

JumpKick **requires JDK 25+ to run** and will install one if needed. Project language
level is **`java = N`** (`--release`). A `jdk =` pin is only for choosing a specific
*install*. Read [Concepts](concepts.md) before pinning runtimes. A pinned JDK a build needs, and
the GraalVM a native build links with, install as part of the run: the download is a row in the
run's Download block with its other downloads, and one line says where it went (`installed JDK
azul-21.0.13.0.3 to ~/.jdks/azul-21.0.13.0.3`). The engine's own runtime, needed before any engine
runs, installs the way `jk jdk install` does. [Machine output](machine-output.md#toolchain-provisioning)
has the `-O jsonl` shape.

A build never needs the network for a JDK it already has. When nothing is pinned and discovery finds
no JDK, the build installs the latest LTS; if the feed or the download cannot be reached (offline, a
proxy or deny list, an HTTP error), it builds on the JDK JumpKick itself runs on, when that JDK is new
enough for `java = N`, and says so in a warning. A cached feed past its 12 h refresh is still used
when the refresh fails. A pinned JDK that is not installed and cannot be fetched fails the build with
the JDK's name and the `jk jdk install …` command.

Supported **project** floor: **JDK 17+** (no Java 8 or 11). 17 and 21 compile with the
host JDK 25 via `--release` — do **not** download those obsolete runtimes for bytecode. A `jdk`
pin also chooses the compiler: `jdk = 21` compiles with JDK 21's javac (see
[Concepts](concepts.md#java--vs-jdk-)).

```bash
jk jdk list
jk jdk install temurin-25
jk jdk pin temurin-25          # writes .jdk-version
jk jdk home
jk build --jdk /opt/jdk-25     # a JDK named by its home: that JDK, nothing looked up or installed
jk jdk uninstall …
jk jdk update
jk jdk verify                  # fingerprint managed trees (`jk jdks verify`)
jk shell                       # subshell with the project JDK
```

## Unpinned modules prefer this release's JDK

A module with no JDK pin (no `jdk` in `jk.toml`, no `.jdk-version`, no `required-*` in the lock,
no `--jdk` or `JK_JDK`) builds on any installed JDK that satisfies its `java` level. Among those it
prefers the build this JumpKick release names for its major (the latest LTS, or its `java` level when
that is newer), when that build is installed or is the shell's `JAVA_HOME`:

| Major | Preferred build |
|---|---|
| 17 | `temurin-17.0.20.1` |
| 21 | `temurin-21.0.12.1` |
| 25 | `temurin-25.0.4.1` |
| 26 | `temurin-26.0.2.1` |
| 27 | `temurin-27.0.1.0.2` |

The preference is never a requirement: JumpKick does not download the preferred build, a build never
fails for want of it, under `CI` or not, and an organisation's newer patch is used as it is. JumpKick
installs a JDK only when nothing installed satisfies the module, and then the newest build of the
major. The engine prefers the build for its own major the same way when `[toolchain] jdk` names no
other. A pin still wins.

Cache keys name the JDK build that ran ([Cache](cache.md)), so two hosts on the same build share a
module's compile and test results and hosts on different builds do not share those steps; nothing
built with one JDK is served to another. `jk doctor`'s `jdk-patch` row says, as a note, which build a
project uses here, and `jk explain` names both builds when a step reruns because the JDK moved.

Discovery looks at existing installs before downloading from the JetBrains JDK feed (or a mirror of
it and its vendor hosts: [Config § Tool mirrors](config.md#tool-mirrors)). Each JDK `jk jdk list`
shows carries the source that found it:

| Source | Where it looks | `jk jdk uninstall` |
|---|---|---|
| `jk` | The managed write root below | Deletes it |
| `intellij` / `jdks` | IntelliJ's shared root; `intellij` when an IDE registered the JDK | `intellij`: refused; `jdks`: deletes it under the managed root |
| `gradle` | `$GRADLE_USER_HOME/jdks`, default `~/.gradle/jdks` (Gradle's provisioned JDKs) | Refused: outside the managed root |
| `sdkman`, `jbang`, `mise`, `asdf`, `jenv`, `homebrew` | That tool's install directory | Runs that tool's uninstall; never deletes what the tool leaves outside the managed root (`jenv remove` only unregisters) |
| `jabba` | `$JABBA_HOME/jdk`, else `~/.jabba/jdk` | Runs `jabba uninstall`; never deletes what Jabba leaves |
| `coursier` | `$COURSIER_JVM_CACHE`, else Coursier's JVM cache: `~/.cache/coursier/jvm` (Linux), `~/Library/Caches/Coursier/jvm` (macOS), `~\AppData\Local\Coursier\Cache\jvm` (Windows) | Refused: Coursier has no uninstall |
| `system` | Linux `/usr/lib/jvm`, `/usr/java`, `/usr/lib64/jvm`, `/usr/local/java`, `/opt/java`; macOS `/Library/Java/JavaVirtualMachines` and `/usr/libexec/java_home -V`; the Windows registry (JavaSoft `JavaHome`, AdoptOpenJDK / Eclipse Adoptium / Eclipse Foundation `hotspot\MSI` `Path`) | Refused |
| `maven-toolchains` | Every `jdkHome` of type `jdk` in `~/.m2/toolchains.xml`, `${env.NAME}` expanded | Refused: a pointer |
| `gradle-properties` | Homes named by `org.gradle.java.installations.paths` and `fromEnv`, and the `toolchains.xml` named by `maven-toolchains-file`, in `$GRADLE_USER_HOME/gradle.properties` (default `~/.gradle`) and, during a build, the build root's `gradle.properties`. `auto-detect=false` turns nothing off | Refused: a pointer |
| `jdk-paths` | Homes named by `JK_JDK_PATHS` and by the variables `JK_JDK_FROM_ENV` names ([Install](install.md)) | Refused: a pointer |
| `path` | `JAVA_HOME` when no source above owns it | Deletes it under the managed root; refused elsewhere |

`jk jdk uninstall` deletes a directory only when its real path, symlinks resolved, is under the
managed write root (`~/.jdks`, macOS `~/Library/Java/JavaVirtualMachines`, or `JK_JDKS_DIR`). A
JDK there uninstalls whichever source reports it, `JAVA_HOME` included. Outside it, a source with an
owning tool runs that tool's uninstall and JumpKick deletes nothing itself; any other source is refused
with its name and path, and nothing is deleted.

`JK_JDK_PROBES` ([Install](install.md)) narrows discovery to the named sources. Managed write root:
[Install](install.md) (IntelliJ-shared `~/.jdks` / macOS Library JVMs). JumpKick's inventory of
those trees (defaults + SHA-256 fingerprints) lives in **`$JK_STATE_DIR/jk-jdks.toml`**
(`~/.jk/state/jk-jdks.toml` on Linux), not next to the installs — the jdks directory is
shared with IntelliJ. `jk jdk verify` (alias `jk jdks verify`) recomputes fingerprints including
the `.jk-owned` marker. There is no on-disk “current” JDK pointer: `JAVA_HOME` / `GRAALVM_HOME`
are whatever the [shell hook](install.md#shell-integration) last exported. When inventory
defaults are unset, the hook still activates a de-facto JDK (`DefaultJdkPolicy`) and, if any
GraalVM is installed, a de-facto Graal (`DefaultGraalPolicy` — preferred newest Oracle GraalVM,
then GraalVM CE). A project `jk-lock.toml` `[jdk]` / `[graal]` entry overrides those defaults when
satisfiable — `suggested-*` floors on the major, `required-*` must match exactly
([lockfile](lockfile.md#toolchain-pins)).

**GraalVM** for [native-image](native.md): `jk jdk` can provision a Graal-capable
distribution when native work needs it. The first installed Graal becomes the native default
without a prompt; `jk jdk graal` sets it explicitly when several exist.

Shell PATH / `JAVA_HOME` hooks: `jk activate` — [Install](install.md#shell-integration).

MCP: `jdk` (uninstall requires `confirm=true`) — [MCP](mcp.md).
