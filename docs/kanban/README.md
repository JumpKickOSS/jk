# Kanban archive (frozen)

**This directory is historical.** The live JumpKick engineering board lives in the org
planning repo:

| | |
|---|---|
| **Repo** | [jkbuild/kanartist](https://github.com/jkbuild/kanartist) |
| **Project key** | `jk` |
| **Ticket ids** | `JK-NNNN` (same numbers as the old `ticket-NNNN` files) |
| **Tickets** | `projects/jk/tickets/JK-NNNN-*.md` |
| **Agent protocol** | [kanartist `AGENTS.md`](https://github.com/jkbuild/kanartist/blob/main/AGENTS.md) |
| **This product repo** | Claim/work rules + **Done criteria** (tests, reinstall, smoke) stay in root [`AGENTS.md`](../../AGENTS.md) |

Do **not** move one-liners between column files or create new `ticket-*.md` here.
Create and claim work with `ka` in the kanartist checkout (typically `../kanartist`).

## What remains here

| Path | Role |
|---|---|
| `ticket-NNNN-*.md` | Snapshot of pre-migration ticket bodies (read-only archive; git history is authoritative) |
| `backlog.md` · `ready.md` · `wip.md` · `blocked.md` · `done.md` | Frozen stubs — not the live board |

Imported into kanartist on **2026-07-20** (`JK-1001` … `JK-1055`). See kanartist
`docs/jk-migration.md`.
