# JumpKick manual

A short map of the product. Use this page to learn what JumpKick can do, then follow a
link when you need flags, `jk.toml` keys, or limitations.

JumpKick (CLI: **`jk`**) is a lockfile-first build tool for **Java, Kotlin, and Groovy**.
You declare a project in TOML. JumpKick writes a real lockfile. Builds skip work the cache
can prove is already done. Coordinates come from Maven Central (and friends) — not a new
package ecosystem.

This page is the intended source for a future `jk` command that prints a high-level manual
for people and coding agents.

**Full index:** [README](README.md). **First project:** [Getting started](getting-started.md).

---

## If you are a coding agent, start here

Do not scrape the terminal UI. JumpKick writes a high-level report on every hosted run.

1. **Read** `{project}/target/jk-results.md` (compile errors, test failures, package outcomes).
2. If MCP is configured, prefer **`jk_results`** / **`jk_diagnostics`** over files; bind first
   with **`jk_bind`**. See [Agents](agents.md) and [MCP](mcp.md).
3. Rebuild with `jk build` / `jk test`, or MCP `jk_run`.
4. Need the live event stream? `--output json` / `jsonl`, or `details.jsonl` next to the
   journal copy of the report. See [Machine output](machine-output.md).

**Fix a failing build** is spelled out in [Troubleshooting](troubleshooting.md).

---

## Install and first project

```bash
curl -fsSL https://jumpkick.build/install.sh | bash
jk new my-app
cd my-app
jk add jackson3-databind
jk build
```

Details: [Install](install.md), [Getting started](getting-started.md), [Templates](templates.md).

---

## Mental model

| Idea | Meaning | Details |
|------|---------|---------|
| **`jk.toml` is data** | No build scripts. `jk add` / `jk remove` edit the file. | [Projects](projects.md) |
| **`jk-lock.toml` is law** | Commit it. `jk build` does not re-resolve. | [Lockfile](lockfile.md) |
| **`java = N` not `jdk =`** | Language/bytecode (`--release`). The CLI already needs JDK 25+. | [Concepts](concepts.md), [JDK](jdk.md) |
| **One lockfile per workspace** | Never per-module. Output is `target/<module>/`. | [Workspaces](workspaces.md) |
| **Newest stable by default** | Scaffolds and `jk update` prefer current stables. | [Lockfile](lockfile.md) |
| **Cache, don’t recompute** | Content-addressed store + action cache. | [Build](build.md), [Cache](cache.md) |

Why this shape exists: [Why JumpKick](why.md).

---

## What you can do

Each line is a capability. Follow the link for the full topic (flags, config, limits).

### Everyday development

- **Create a project** (`jk new` / `jk init`), including Giter8 templates — [Templates](templates.md)
- **Declare a module** in `jk.toml` (identity, Java/Kotlin/Groovy, source layout) — [Projects](projects.md), [Layout](layout.md)
- **Add and remove dependencies** (catalog short names, GAV, git, path) — [Dependencies](dependencies.md)
- **Lock, update, and inspect the graph** (`jk lock`, `outdated`, `update`, `tree`, `why`) — [Lockfile](lockfile.md)
- **Use platform BOMs** (Spring, Quarkus, …) as enforced pins — [Platforms](platforms.md)
- **Compile and package** (`jk compile`, `jk build`) — [Build](build.md)
- **Run tests** (suites, JUnit tags, `-j` / `-w`) — [Test](test.md)
- **Format source** (`jk format`, Spotless + import hygiene) — [Format](format.md)
- **Run the app** (`jk run`, `jk watch`, `jk dev`, `jk jshell`) — [Run](run.md)
- **See why a rebuild will happen** (`jk explain`) — [Explain](explain.md)

### Ship and operate

- **Thin, fat, and minified jars** — [Packaging](packaging.md)
- **GraalVM native-image** — [Native](native.md)
- **OCI images** (daemonless) — [Images](images.md)
- **Publish** with optional signing, Sigstore, SLSA, SBOM — [Publish](publish.md)
- **Audit and deny** (OSV, source-host denylist) — [Publish](publish.md)
- **Manage JDKs** (discover, install, pin, shell hooks) — [JDK](jdk.md)
- **Run one-off tools** (`jk tool run` / `jkx`, JBang-compatible scripts) — [Tools](tools.md)
- **IDE import** (IntelliJ, VS Code, BSP) — [IDE](ide.md)
- **Web dashboard** (`jk web`) — [Web](web.md)

### Frameworks (first-party plugins)

Spring Boot, Quarkus, Grails, Micronaut, Android, protobuf, and more — [Frameworks](frameworks.md).
How plugins are *used* (not authored): [Plugins](plugins.md).

### Adopt an existing build

- Run the **real** Maven or Gradle build: `jk mvn` / `jk gradle`
- **Import** `pom.xml` / `build.gradle.kts` into `jk.toml`
- **Export** Maven / Gradle / IDE files / a BOM POM

Details: [Migration](migration.md).

---

## Files you will touch

| Path | Role |
|------|------|
| `jk.toml` | Project or workspace manifest |
| `jk-lock.toml` | Locked graph + checksums — **commit this** |
| `jk-libs.toml` | Optional workspace catalog (short name → `group:artifact`) |
| `target/` | Build outputs (gitignored). Report: `target/jk-results.md` |
| `.jdk-version` | Optional JDK pin |
| `~/.config/jk/config.toml` | Machine config |

Layout details: [Install](install.md), [Projects](projects.md).

---

## Commands (canonical)

```bash
jk new | init | add | remove
jk lock | sync | outdated | update | tree | why
jk compile | build | test | run | watch | dev | jshell
jk format | explain | tasks | show | inspect
jk assemble | native | image | publish | install | verify
jk audit | deny
jk jdk … | tool … | library …
jk import | export | mvn | gradle | ide | bsp
jk engine status | web | jobs | cancel
jk cache … | storage … | clean | doctor
```

Index with one-liners: [Commands](commands.md). Hidden aliases (Maven/Gradle muscle memory):
[Aliases](aliases.md) — product docs always use the canonical name.

---

## Configuration layers

Precedence for most knobs: **CLI flag > environment variable > project `jk.toml` > machine
`~/.config/jk/config.toml`**. Details: [Config](config.md).

The resident **engine** (capped JVM) does the heavy work; the CLI is a slim native client.
It starts on first use. `jk engine status` / `jk engine stop`. Details: [Engine](engine.md).
