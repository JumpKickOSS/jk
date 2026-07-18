# Kanban — Backlog

Tickets that are **not ready to pull**. Ordered top-to-bottom by priority within this column.
Move a one-liner to [ready.md](ready.md) when it is refined enough to start; to [wip.md](wip.md)
when work begins; to [blocked.md](blocked.md) if stuck; to [done.md](done.md) when finished.

| Convention | Rule |
|---|---|
| Columns | `backlog.md` → `ready.md` → `wip.md` → (`blocked.md`) → `done.md` |
| One-liners | Live only in these column files |
| Tickets | `docs/kanban/ticket-NNNN-short-name.md` — detail, acceptance, links |
| Product docs | [docs/README.md](../README.md) — keep the public set small |
| Refinement | Shallow tickets OK here; flesh out before or when moving to ready |

**Board:** [backlog](backlog.md) · [ready](ready.md) · [wip](wip.md) · [blocked](blocked.md) · [done](done.md)

---

## Backlog Items

1. [ticket-1007](ticket-1007-bootstrap-jk-on-jk.md) — CI/bootstrap builds jk with jk
2. [ticket-1010](ticket-1010-private-plugins.md) — Private plugin jars (pin + trust) without a marketplace
3. [ticket-1014](ticket-1014-ide-engine-client.md) — IDE clients over engine wire protocol

---

## Notes

- **Out of scope for this board:** infinite ecosystem long tail (every AGP parity gap, full KMP
  multiplatform, marketplace plugins, RBE). Track those in feature plans when they become north stars.
- **Ready queue:** see [ready.md](ready.md). Prefer separate worktrees per ticket; keep WIP small.
