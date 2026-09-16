# JumpKick contributor documentation

How JumpKick is **implemented**, and how to change this repository. If you want to *use*
`jk` on a Java/Kotlin/Groovy project, start at [../user/](../user/README.md) instead.

Build instructions for this checkout: [../../CONTRIBUTING.md](../../CONTRIBUTING.md).
Agent protocol for work in this repo: [../../AGENTS.md](../../AGENTS.md).

## Map

| Page | Contents |
|------|----------|
| [Architecture](architecture.md) | Client/engine split, modules, resolve, cache, schema freeze |
| [Per-job VFS](vfs.md) | Job-scoped input tree, `vfs-max-mb` vs `max-heap-mb`, PathUtil memo |
| [Comments and Javadoc](comments.md) | Short by default; no ticket ids in source, tests, or these docs |
| [Plugin authoring](plugins.md) | `jk-plugin.toml`, workers, private pins (first-party + vendored) |
| [Code as Art](code-as-art.md) | Size budgets, Typed Envelope, JSpecify, fluent Lombok |
| [TUI](tui.md) | CommandWedge, envelope, nerd/ansi/plain, script-mode allowlist |
| [CLI terminal](../cli-terminal.md) | `:cli-terminal` TTY session, Style, Width, keys (no JLine) |
| [HTTP server](http.md) | Engine HTTP lifetime, auth, REST, SSE |
| [Web client](webclient.md) | Dashboard SPA (thin renderer over HTTP) |
| [Releases](releases.md) | Versioning, install layout, RSA/SHA-256 signing |
| [Official Maven repo](maven-repo.md) | `jumpkick.build/repo/`, exclusive groups, publish script |
| [Engine warmup](install-optimize.md) | Worker AOT, host calibration |
| [Self-host](self-host.md) | Dogfooding this monorepo with `jk`; trying the tree on another project from a private home |
| [Test suite tiers](test-suite-tiers.md) | `jk test` vs `--profile integration` vs the nightly profiles |
| [Progress contract](progress-contract.md) | ETA / bar semantics |
| [The 1.0 plan](plan-1.0.md) | Priority order to 1.0: the developer's inner loop first; six epics |
| [Compatibility](compatibility.md) | What a minor or major release may change in manifest, lock, results, MCP, SDK, CLI |
| [Plugin census](plugin-census.md) | Maven and Gradle plugin use on GitHub, classified against the batteries register; gaps and non-goals |

## Planning and internal design

Tickets, PRDs, benches, and decision records are **not** in this repo. They live in
[KanArtist](https://github.com/JumpKickOSS/kanartist) project `jk`
(`projects/jk/tickets/`, `projects/jk/docs/`). The one planning page that does live here is
[the 1.0 plan](plan-1.0.md): the priority order, without ticket ids, so a reader of this tree
knows what is being built next. Its tickets carry the label `inner-loop-1.0` on the board.

Out-of-tree black-box scenarios: [JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples).
