# JDK

JumpKick **requires JDK 25+ to run** and will install one if needed. Project language
level is **`java = N`** (`--release`). A `jdk =` pin is only for choosing a specific
*install*. Read [Concepts](concepts.md) before pinning runtimes.

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

Discovery looks at existing installs (IntelliJ shared root, SDKMAN, mise, asdf, Homebrew,
system, `JAVA_HOME`) before downloading from the JetBrains JDK feed. Managed write root:
[Install](install.md) (IntelliJ-shared `~/.jdks` / macOS Library JVMs). JumpKick's inventory of
those trees (defaults + SHA-256 fingerprints) lives in **`$JK_STATE_DIR/jk-jdks.toml`**
(`~/.local/state/jk/jk-jdks.toml` on Linux), not next to the installs — the jdks directory is
shared with IntelliJ. `jk jdk verify` (alias `jk jdks verify`) recomputes fingerprints including
the `.jk-owned` marker. There is no on-disk “current” JDK pointer: `JAVA_HOME` / `GRAALVM_HOME`
are whatever the [shell hook](install.md#shell-integration) last exported.

**GraalVM** for [native-image](native.md): `jk jdk` can provision a Graal-capable
distribution when native work needs it.

Shell PATH / `JAVA_HOME` hooks: `jk activate` — [Install](install.md#shell-integration).

MCP: `jk_jdk` (uninstall requires `confirm=true`) — [MCP](mcp.md).
