# jk documentation

Public docs for [jk](../README.md) — a modern build tool for Java, Kotlin, and Groovy.

| Doc | Audience |
|---|---|
| [**User guide**](guide.md) | Day-to-day use: projects, deps, lockfile, platforms, packaging, plugins, JDK, workspaces |
| [**TUI style guide**](tui.md) | CommandWedge, blank envelope, script-mode allowlist, nerd/ansi/plain glyphs |
| [**Machine / agent output**](machine-output.md) | JSONL events, web SSE, verbose, MCP plan, agent recipe |
| [**Architecture**](architecture.md) | How jk is built: client/engine split, modules, resolution, caching |
| [**Plugins**](plugins.md) | Authoring first-party-style build plugins (Spring Boot, Quarkus, Grails, …) |
| [**Feature PRDs**](features/README.md) | Design freezes (catalogs, BOMs, packaging, Giter8, …) |
| [**Releases**](releases.md) | Versioning, install layout, Ed25519 signing, tag CI (JK-1066) |
| [**Host warmup**](install-optimize.md) | Engine self-heal: worker AOT + host calibration (idle / 12 h) |
| [**Hosting & CDN**](hosting.md) | Firebase Hosting + GCS releases, DNS for jumpkick.build |
| [**Maven repo**](maven-repo.md) | Official first-party repo (`/repo/…`); exclusive routing for `cc`/`build.jumpkick` |

Project planning (not product docs): [kanartist](https://github.com/jkbuild/kanartist) project `jk` (`JK-NNNN`).

**Black-box scenarios & adopter examples** (separate repo): [jkbuild/jk-examples](https://github.com/jkbuild/jk-examples) — real projects used for end-to-end validation, benchmarking, and teaching.

Competitive notes (maintainers): [mill-comparison.md](mill-comparison.md) — adversarial gap analysis vs Mill.

Contributing and build instructions: [../CONTRIBUTING.md](../CONTRIBUTING.md).
