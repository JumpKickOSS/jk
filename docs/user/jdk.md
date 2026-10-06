# JDK

JumpKick **requires JDK 25+ to run** and will install one if needed. Project language
level is **`java = N`** (`--release`). A `jdk =` pin is only for choosing a specific
*install*. Read [Concepts](concepts.md) before pinning runtimes. Every JDK or GraalVM jk
installs on your behalf — the engine's own runtime, a pinned JDK a build needs, the GraalVM a
native build links with — renders the same progress bar and "has been installed to" line as
`jk jdk install`, then the command that needed it continues ([machine
output](machine-output.md#toolchain-provisioning) for the `--output json` shape).

Supported **project** floor: **JDK 17+** (no Java 8 or 11). 17 and 21 compile with the
host JDK 25 via `--release` — do **not** download those obsolete runtimes for bytecode.

```bash
jk jdk list
jk jdk install temurin-25
jk jdk pin temurin-25          # writes .jdk-version
jk jdk home
jk jdk uninstall …
jk jdk update
jk jdk verify                  # fingerprint managed trees (`jk jdks verify`)
jk shell                       # subshell with the project JDK
```

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
owning tool runs that tool's uninstall and jk deletes nothing itself; any other source is refused
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
