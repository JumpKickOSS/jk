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

- **Using JumpKick:** `jk skill` (CLI) prints the agent skill. The website page is
  [user/skill.md](user/skill.md).
- **Against Maven and Gradle:** [user/comparison.md](user/comparison.md).
- **Coding agents (one recipe):** [user/troubleshooting.md](user/troubleshooting.md) — fix a
  failing build (`jk results` or MCP `run`).
- **Contributing to JumpKick:** [contributors/README.md](contributors/README.md).

## Future HTML

When these pages are published, keep the markdown filenames as slugs:

| Markdown | URL |
|----------|-----|
| `docs/user/README.md` | `https://jumpkick.build/documentation` |
| `docs/user/skill.md` | `https://jumpkick.build/documentation/skill` |
| `docs/user/<topic>.md` | `https://jumpkick.build/documentation/<topic>` |
| `docs/contributors/<topic>.md` | `https://jumpkick.build/documentation/contributors/<topic>` |

`jk skill` prints the core; `jk skill <topic>` prints one page. [user/skill.md](user/skill.md)
is the same entry on the website. Longer topic pages stay under `docs/user/`.

Old paths under `docs/*.md` (for example `docs/guide.md`) are **stubs** that point here, so
existing `jk://docs/…` ticket links and in-code `docs/architecture.md` citations still resolve.

## What does *not* live here

Internal design records, PRDs, benches, and ticket-linked decision essays live in the
[KanArtist](https://github.com/JumpKickOSS/kanartist) project `jk` under
`projects/jk/docs/` — not in this repository. Black-box adopter scenarios live in
[JumpKickOSS/jk-examples](https://github.com/JumpKickOSS/jk-examples).
