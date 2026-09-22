# JumpKick documentation

This tree is **product documentation**. It will be converted to HTML and published at
[https://jumpkick.build/documentation](https://jumpkick.build/documentation). Pages are
written so they can be indexed and searched independently: a high-level page introduces a
capability and links to a deeper page for flags, configuration, and limitations.

Two audiences, two directories. Do not mix them.

| Directory | Audience | Question it answers |
|-----------|----------|---------------------|
| **[user/](user/README.md)** | People installing JumpKick, and AI coding agents that have never seen it | How do I *use* `jk`? What can it do? How do I accomplish a goal? |
| **[contributors/](contributors/README.md)** | People and agents working *on* the JumpKick codebase | How is JumpKick built? How do I change it? |

Root files that stay at the repository root (GitHub convention, not this tree):
[README.md](../README.md) (pitch), [CONTRIBUTING.md](../CONTRIBUTING.md) (how to build this
repo), [AGENTS.md](../AGENTS.md) (protocol for agents contributing here).

## Start here

- **Using JumpKick:** `jk manual` (CLI) prints the agent/human playbook. The website map is
  [user/manual.md](user/manual.md).
- **Against Maven and Gradle:** [user/comparison.md](user/comparison.md).
- **Coding agents (one recipe):** [user/troubleshooting.md](user/troubleshooting.md) — fix a
  failing build (`jk results` or MCP `jk_results`).
- **Contributing to JumpKick:** [contributors/README.md](contributors/README.md).

## Future HTML

When these pages are published, keep the markdown filenames as slugs:

| Markdown | URL |
|----------|-----|
| `docs/user/README.md` | `https://jumpkick.build/documentation` |
| `docs/user/manual.md` | `https://jumpkick.build/documentation/manual` |
| `docs/user/<topic>.md` | `https://jumpkick.build/documentation/<topic>` |
| `docs/contributors/<topic>.md` | `https://jumpkick.build/documentation/contributors/<topic>` |

`jk manual` prints a self-contained playbook (absolute GitHub / jumpkick.build links) so
coding agents do not have to chase relative paths. [user/manual.md](user/manual.md) is the
website map into topic pages.

Old paths under `docs/*.md` (for example `docs/guide.md`) are **stubs** that point here, so
existing `jk://docs/…` ticket links and in-code `docs/architecture.md` citations still resolve.

## What does *not* live here

Internal design records, PRDs, benches, and ticket-linked decision essays live in the
[KanArtist](https://github.com/JumpKickOSS/kanartist) project `jk` under
`projects/jk/docs/` — not in this repository. Black-box adopter scenarios live in
[JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples).
