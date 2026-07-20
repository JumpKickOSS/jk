# Kanban — Ready

Tickets **refined enough to start** (acceptance clear, dependencies known). Pull from the top
into [wip.md](wip.md). Prefer WIP limit of a few active tickets.

**Board:** [backlog](backlog.md) · [ready](ready.md) · [wip](wip.md) · [blocked](blocked.md) · [done](done.md)

---

## Ready Items

### P2 (pull top-first)

Ordered for **impact ÷ cost** after Mill P0/P1/depth:

| # | Ticket | Why this order |
|---|---|---|
| 1 | [ticket-1034](ticket-1034-outdated-deps.md) — `jk outdated` polish | Smallest; locks the update story users already have |
| 2 | [ticket-1032](ticket-1032-assembly-packaging-depth.md) — Assembly + R8 productization | Fat-jar rules + shrink discoverability; shadow-jar already exists |
| 3 | [ticket-1038](ticket-1038-showcase-monorepo-ci.md) — Showcase monorepo CI | Public dogfood / credibility after packaging matrix |
| 4 | [ticket-1033](ticket-1033-lint-matrix-research.md) — Lint matrix research → thin path | Research-first; one Java tool, Kotlin optional defer |
| 5 | [ticket-1017](ticket-1017-ide-marketplace-plugins.md) — Marketplace IDE plugin (one track) | After BSP 1028/1041; **one** of VS Code or IntelliJ MVP |

### P1-depth (done this branch)

1031 selectors · 1039 multi-task build-logic · 1040 selective prepare/run · 1041 BSP import — see [done.md](done.md).
