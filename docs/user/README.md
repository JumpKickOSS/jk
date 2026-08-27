# JumpKick user documentation

How to **use** JumpKick. Written for humans who have never used `jk`, and for AI coding
agents that need a fast path from “what is this tool?” to a specific flag or file.

These pages will be published under **https://jumpkick.build/documentation**. Each file is
one topic so search and deep-links stay useful. High-level pages stay short and point at
detail pages.

If you are changing JumpKick itself, go to [../contributors/](../contributors/README.md).

## Start here

| Page | When to open it |
|------|-----------------|
| **[Manual](manual.md)** | First stop. Live text is `jk manual` (MCP `jk_manual`). This page maps into every topic. |
| **[Getting started](getting-started.md)** | Install, first project, first build. |
| **[Why JumpKick](why.md)** | Maven vs Gradle vs JumpKick — the product bet. |
| **[Concepts](concepts.md)** | Lockfile is law, `jk.toml` is data, `java =` vs `jdk =`. |

## Accomplish a goal

| Goal | Page |
|------|------|
| Fix a failing build | [Troubleshooting](troubleshooting.md) |
| Add or update a dependency | [Dependencies](dependencies.md), [Lockfile](lockfile.md) |
| Format source | [Format](format.md) |
| Run tests (suites, tags, parallelism) | [Test](test.md) |
| Understand a rebuild | [Explain](explain.md) |
| Create a project from a template | [Templates](templates.md) |
| Import Maven or Gradle | [Migration](migration.md) |
| Use Spring Boot / Quarkus / Grails / … | [Frameworks](frameworks.md) |
| Talk to the engine as an agent | [Agents](agents.md), [MCP](mcp.md) |

## Topic index

### Project shape

- [Install](install.md) — installer, on-disk layout, env
- [Projects and `jk.toml`](projects.md)
- [Workspaces](workspaces.md)
- [Source layout](layout.md)

### Dependencies

- [Dependencies](dependencies.md) — scopes, catalogs, git/path
- [Lockfile](lockfile.md) — `jk lock` / `update` / `outdated` / `tree` / `why`
- [Platform BOMs](platforms.md)
- [Repositories and auth](repositories.md)

### Build, test, run

- [Build](build.md)
- [Test](test.md)
- [Format](format.md)
- [Run, watch, REPL](run.md)
- [Explain and tasks](explain.md)
- [Cache and storage](cache.md)

### Ship

- [Packaging](packaging.md)
- [Native images](native.md)
- [Container images](images.md)
- [Publish and supply chain](publish.md)
- [Dynamic surface](dynamic-surface.md) — R8 keep rules and native-image reachability

### Tooling

- [JDK](jdk.md)
- [Tools (`jkx`)](tools.md)
- [Templates](templates.md)
- [Frameworks](frameworks.md)
- [Plugins](plugins.md) — using plugins (not authoring them)
- [IDE and BSP](ide.md)
- [Web dashboard](web.md)
- [Engine](engine.md)

### Agents, CI, config

- [Agents](agents.md)
- [MCP](mcp.md)
- [Machine output](machine-output.md)
- [CI](ci.md)
- [Config, CLI chrome, env](config.md)
- [Commands](commands.md)
- [Aliases](aliases.md)
- [Migration](migration.md)
- [Wrapper](wrapper.md)
- [Build logic](build-logic.md) — `jk/` or `.jk/`
- [Examples](examples/)

JumpKick is **pre-1.0 (alpha)**. Commands and `jk.toml` keys in these pages describe current
behavior. Schema and protocol versions stay at **1** until 1.0 (additive fields only).
