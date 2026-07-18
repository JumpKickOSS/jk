# User guide

jk is a declarative, lockfile-first build tool for **Java and Kotlin** (JDK 17+).
This guide covers the commands and files you touch every day.

## Install

```bash
# From a release (see the project README for current install options)
curl -fsSL https://…/install.sh | sh   # or use the published binary

jk --help
```

Developer builds from this repo: see [CONTRIBUTING.md](../CONTRIBUTING.md).

State and cache live under `~/.jk/` (`JK_HOME` relocates the whole tree).

## Projects and `jk.toml`

```bash
jk init my-app          # or: jk new my-app
cd my-app
```

A minimal manifest:

```toml
[project]
group   = "com.example"
name    = "my-app"
version = "0.1.0"
jdk     = 25

[dependencies]
jackson = "2.18.2"   # caret by default: ^2.18.2

[test-dependencies]
junit = "5.11.0"
```

- **TOML is data** — no embedded scripts. Safe for `jk add` / `jk remove` to edit.
- Version strings: bare `"1.2.3"` means `^1.2.3`; use `"=1.2.3"` for exact, `"~1.2.3"` for patch-only, or ranges like `">=1.2,<2"`.
- Dependency scopes: `[dependencies]`, `[test-dependencies]`, `[provided-dependencies]`,
  `[runtime-dependencies]`, `[processor-dependencies]`, `[platform-dependencies]` (BOMs),
  `[export-dependencies]`, plus optional `[dev-dependencies]` / `[test-dev-dependencies]`.

Optional features (local to this project):

```toml
[dependencies]
postgres = { group = "org.postgresql", name = "postgresql", version = "42.7.4", optional = true }

[features]
default = ["postgres"]
[features.postgres]
deps = ["postgres"]
```

Profiles change *how* you compile (flags, JVM args), not *what* deps you have.
Variants change *which product* you build (sources, deps, plugin config) — see
`[variants]` and `--variant` / `--release` on artifact-producing commands.

## Lockfile

`jk.lock` is **canonical**. Commit it.

| Command | Role |
|---|---|
| `jk lock` | Resolve and write `jk.lock` |
| `jk sync` | Materialize cache / offline prep (`--offline-prepare`) |
| `jk update` | Re-resolve within declared constraints |
| `jk build` | Builds from the lock — does not re-resolve |
| `jk tree` / `jk why` | Inspect the graph offline |

Platform BOMs (`[platform-dependencies]`) are **recommendations** (Gradle `platform()` style):
the pin is preferred first; a stricter transitive floor may lift past it. Use an exact or
caret/tilde version on the BOM itself — not `latest`.

Resolution is **highest-version-wins** (not Maven nearest-wins), with PubGrub prose on conflict.
Main, test, and processor graphs are solved separately so annotation-processor constraints
do not force main classpath versions.

## Common commands

```bash
jk add g:a:v                 # or catalog short name: jk add jackson
jk remove <coord>
jk compile                   # type-check
jk build                     # package
jk test
jk run -- args…
jk clean
jk explain                   # forecast / cache status
jk format
jk audit                     # OSV
jk deny                      # license / source / yanked policy
jk publish                   # optional --sign / --sigstore / --slsa / --sbom
jk image                     # OCI (daemonless)
jk native                    # GraalVM native-image
jk verify                    # rebuild in a scratch dir and compare hashes
```

Machine-readable output: `--output json` (or `jsonl`) on commands that support it.

### Migration aliases

Hidden shortcuts map familiar verbs (`package` → `build`, etc.). See `jk --help` for the
canonical set; aliases are for muscle memory only.

## Workspaces

```toml
# root jk.toml
[workspace]
modules = ["libs/*", "services/*"]

[workspace.dependencies]
jackson-databind = { group = "com.fasterxml.jackson.core", name = "jackson-databind", version = "2.18.2" }
```

```toml
# services/api/jk.toml
[project]
name = "api"
# …

[dependencies]
jackson-databind.workspace = true   # shared external
widget-core.workspace = true        # sibling module (matches [project].name)
```

- **One `jk.lock` at the workspace root**
- `jk new path/to/mod` and `jk add ./path` register modules for you

## Git and path dependencies

```toml
[dependencies]
mylib = { git = "https://github.com/acme/mylib", tag = "v1.4.0" }
# or: branch / rev; path = "subdir" inside the repo
local = { path = "../sibling" }   # local project with its own jk.toml
```

The lock pins the resolved git SHA. Tag moves fail loudly until `jk update`.

## JDK and shell

```bash
jk jdk install temurin-25
jk jdk pin temurin-25          # writes .jdk-version
jk jdk list
eval "$(jk activate bash)"     # directory-aware JAVA_HOME (bash/zsh/fish/pwsh)
jk shell                       # subshell with project JDK
```

jk discovers existing installs (IntelliJ, SDKMAN, mise, asdf, Homebrew, system, …)
and can install from the JetBrains JDK feed. Supported project floor: **JDK 17+**.

## Migration from Maven / Gradle

```bash
jk mvn package                 # real Maven (wrapper-aware), managed by jk
jk gradle build                # real Gradle
jk import pom.xml              # → jk.toml + fidelity report
jk import build.gradle.kts     # declarative only (no script evaluation)
jk export maven                # publishable POM
jk export idea | vscode
```

POM import is the high-fidelity path. Gradle import is honest about limits: it does not
execute build scripts. Keep `jk gradle` for modules that still need full Gradle.

Single-file scripts (JBang-compatible headers): `jk tool run script.java` / `jkx`.

## Auth and repositories

```bash
jk auth login                  # GitHub / GitLab / Gitea / Bitbucket
# repositories in jk.toml or ~/.jk/config.toml — credentials via env / keychain / settings.xml
```

Maven Central is default. Corporate mirrors, forge package registries, S3/MinIO, and GCS
are supported. Prefer `auth = "env:TOKEN"` over secrets in TOML.

## Wrapper

```bash
jk wrapper                     # emit ./jk + jk.bat, pin-on-first-use
jk wrapper update
```

## Engine (brief)

Build work runs in a **resident engine** (JVM, memory-capped), started automatically on first
build. The CLI stays a slim native client.

```bash
jk engine status
jk engine stop
```

Details: [architecture.md](architecture.md#the-engine).

## Layout reference

| Path | Role |
|---|---|
| `jk.toml` | Project / workspace manifest |
| `jk.lock` | Locked graph (commit this) |
| `.jdk-version` | Optional pin (`temurin-21`) |
| `target/` | Build outputs (gitignored) |
| `.jk/` | Generated project state (gitignored) |
| `~/.jk/` | Global cache, JDKs, engine, tools |

## Status

jk is **pre-1.0 (alpha)**. APIs and lock schema may still change. See the repository README
for the current milestone focus.
