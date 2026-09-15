# JumpKick manual

The live playbook is **`jk manual`**. It prints markdown for coding agents and people who
have never used JumpKick: what the tool is, what not to do (Maven/Gradle habits), everyday
CLI and MCP recipes, triage via `target/jk-results.md`, and links into the rest of these
pages.

```bash
jk manual
```

Same text on the engine MCP server: tool **`jk_manual`**, resource **`jk://manual`**.

New projects (`jk new` / `jk init` / templates) get an `AGENTS.md` that tells the next
agent to run that command.

This page is the **website map**. The command output is self-contained (absolute links) so
an agent does not have to chase relative paths. Follow a topic link below for flags,
`jk.toml` keys, and limitations.

**Full index:** [README](README.md). **First project:** [Getting started](getting-started.md).

---

## If you are a coding agent, start here

Do not scrape the terminal UI. JumpKick writes a high-level report on every hosted run.

1. Run **`jk manual`** (or MCP **`jk_manual`**) once per session.
2. **Read** `target/jk-results.md` with your file/grep tools after a build or test (same
   markdown as `jk results`). Prefer the file over shelling out.
3. If MCP is configured, prefer **`jk_results`** / **`jk_diagnostics`** over files; bind
   first with **`jk_bind`**. See [Agents](agents.md) and [MCP](mcp.md).
4. Rebuild with `jk build` / `jk test`, or MCP `jk_run`. Format after edits: `jk format`.
5. Need the live event stream? `--output json` / `jsonl`, or `jk results --details`.
   See [Machine output](machine-output.md).

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
| **Newest stable by default** | Scaffolds and `jk add` write today's stable as an exact pin; `jk update` bumps it. | [Lockfile](lockfile.md#jk-update) |
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
- **Run tests** (unit inner loop, `--suite integration` / `e2e`, tags, `-j` / `-w`) — [Test](test.md). Do not `--all` as a habit.
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
- **Report a vulnerability**; trust boundaries — [Security](security.md)

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
| `AGENTS.md` | Points coding agents at `jk manual` |
| `target/` | Build outputs (gitignored). Report: `target/jk-results.md` |
| `.jdk-version` | Optional JDK pin |
| `~/.jk/config.toml` | Machine config |

Layout details: [Install](install.md), [Projects](projects.md).

---

## Commands (canonical)

```bash
jk manual
jk new | init | add | remove
jk lock | sync | outdated | update | tree | why
jk compile | build | test | run | watch | dev | jshell
jk format | explain | tasks | show | inspect
jk assemble | native | image | publish | install | verify
jk audit | deny
jk jdk … | tool … | library …
jk import | export | mvn | gradle | ide | bsp
jk engine status | web | jobs | cancel | results
jk cache … | storage … | clean | doctor
```

Index with one-liners: [Commands](commands.md). Hidden aliases (Maven/Gradle muscle memory):
[Aliases](aliases.md) — product docs always use the canonical name.

---

## Configuration layers

Precedence for most knobs: **CLI flag > environment variable > project `jk.toml` > machine
`~/.jk/config.toml`**. Details: [Config](config.md).

The resident **engine** (capped JVM) does the heavy work; the CLI is a slim native client.
It starts on first use. `jk engine status` / `jk engine stop`. Details: [Engine](engine.md).
