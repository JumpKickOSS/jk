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
jk shell                       # subshell with the project JDK
```

Discovery looks at existing installs (IntelliJ shared root, SDKMAN, mise, asdf, Homebrew,
system, `JAVA_HOME`) before downloading from the JetBrains JDK feed. Managed write root:
[Install](install.md) (IntelliJ-shared `~/.jdks` / macOS Library JVMs).

**GraalVM** for [native-image](native.md): `jk jdk` can provision a Graal-capable
distribution when native work needs it.

Shell PATH / `JAVA_HOME` hooks: `jk activate` — [Install](install.md#shell-integration).

MCP: `jk_jdk` (uninstall requires `confirm=true`) — [MCP](mcp.md).
