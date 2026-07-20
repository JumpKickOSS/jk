# Kanban — Backlog

Tickets that are **not ready to pull**. Ordered top-to-bottom by priority within this column.
Move a one-liner to [ready.md](ready.md) when it is refined enough to start; to [wip.md](wip.md)
when work begins; to [blocked.md](blocked.md) if stuck; to [done.md](done.md) when finished.

| Convention | Rule |
|---|---|
| Columns | `backlog.md` → `ready.md` → `wip.md` → (`blocked.md`) → `done.md` |
| One-liners | Live only in the column files |
| Tickets | `docs/kanban/ticket-NNNN-short-name.md` — detail, acceptance, links |
| Product docs | [docs/README.md](../README.md) — keep the public set small |
| Refinement | Shallow tickets OK here; flesh out before or when moving to ready |

**Board:** [backlog](backlog.md) · [ready](ready.md) · [wip](wip.md) · [blocked](blocked.md) · [done](done.md)

---

## Backlog Items

P0–P1, **P1-depth** Mill-steal, and **P1-infra** (1043/1022/1042) are done. **P2** lives on
[ready.md](ready.md). Remaining:

### Infra follow-ups (from 1043 / 1042)

1. [ticket-1051](ticket-1051-engine-job-deadline-watchdog.md) — Engine job deadline / stream heartbeat (← 1043)
2. [ticket-1052](ticket-1052-engine-release-project-fds.md) — Release project FDs after jobs; drop warm-engine denylist (← 1042)
3. [ticket-1053](ticket-1053-kotlin-worker-closed-flake.md) — Kotlin worker `closed` flake (HiltTransformTest)

### Closed-item follow-ups (Mill depth / polish)

4. [ticket-1044](ticket-1044-build-logic-graph-spi.md) — Build-logic graph SPI / anchors / Kotlin (← 1037/1039)
5. [ticket-1045](ticket-1045-selective-content-hash.md) — Selective content-hash prepare (← 1027/1040)
6. [ticket-1047](ticket-1047-task-inspect-show.md) — Task inspect / show (← 1031 Mill path UX)
7. [ticket-1046](ticket-1046-incremental-zinc-kotlin.md) — Zinc decision + Kotlin incremental contracts (← 1029)
8. [ticket-1048](ticket-1048-bsp-test-run-providers.md) — BSP test/run providers (← 1028/1041)
9. [ticket-1050](ticket-1050-timeline-watch-polish.md) — Timeline + watch polish (← 1023/1025)
10. [ticket-1049](ticket-1049-warm-pool-implementation.md) — Warm compiler pool **if** measure goes go (← 1030)

### Other P3

11. [ticket-1035](ticket-1035-dag-visualize.md) — Module DAG as DOT
12. [ticket-1036](ticket-1036-jshell-repl-sandbox-dx.md) — `jk jshell` + test sandbox docs
13. [ticket-1019](ticket-1019-plugin-cosign-signing.md) — Cosign/Sigstore additive plugin signatures

---

## Notes

- **Mill comparison:** [mill-comparison.md](../mill-comparison.md); tickets through **1053**.
- **P2 order (ready):** 1034 → 1032 → 1038 → 1033 → 1017.
- **Warm pool:** do not implement 1049 without a fresh green measure (1030 / warm-pool-bench.md).
- Prefer separate worktrees per ticket; keep WIP small.
