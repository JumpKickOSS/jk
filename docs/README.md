# jk documentation

Public docs for [jk](../README.md) — a modern build tool for Java and Kotlin.

| Doc | Audience |
|---|---|
| [**User guide**](guide.md) | Day-to-day use: projects, deps, lockfile, JDK, workspaces, migration |
| [**Machine / agent output**](machine-output.md) | JSONL events, web SSE, verbose, MCP plan, agent recipe |
| [**Architecture**](architecture.md) | How jk is built: client/engine split, modules, resolution, caching |
| [**Plugins**](plugins.md) | Authoring first-party-style build plugins |
| [**Feature PRDs**](features/README.md) | Design freezes for tickets (catalogs, BOMs, starters, …) |
| [**Releases**](releases.md) | Versioning, install layout, Ed25519 signing, tag CI (JK-1066) |
| [**Hosting & CDN**](hosting.md) | Firebase Hosting + GCS releases, DNS for jumpkick.build |

Project planning (not product docs): [kanartist](https://github.com/jkbuild/kanartist) project `jk` — local archive pointer [kanban/README.md](kanban/README.md).

Competitive notes (maintainers): [mill-comparison.md](mill-comparison.md) — adversarial gap analysis vs Mill.

Contributing and build instructions: [../CONTRIBUTING.md](../CONTRIBUTING.md).
