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

1. [ticket-1001](ticket-1001-pre-1.0-wire-hardening.md) — Pre-1.0 wire protocol + CLI freeze
2. [ticket-1002](ticket-1002-classifier-package-identity.md) — Solver package identity includes type/classifier
3. [ticket-1004](ticket-1004-cas-invariant-tests.md) — Permanent CAS/action-cache invariant tests
4. [ticket-1005](ticket-1005-resolve-suggestion-engine.md) — Conflict diagnostics: actionable suggestions
5. [ticket-1006](ticket-1006-cross-package-features.md) — Cross-package feature selection on dependencies
6. [ticket-1007](ticket-1007-bootstrap-jk-on-jk.md) — CI/bootstrap builds jk with jk
7. [ticket-1008](ticket-1008-gradle-version-catalog-import.md) — `jk import` reads Gradle version catalogs
8. [ticket-1009](ticket-1009-explain-rebuild-ux.md) — `jk explain` / why-rebuilt as signature offline DX
9. [ticket-1010](ticket-1010-private-plugins.md) — Private plugin jars (pin + trust) without a marketplace
10. [ticket-1011](ticket-1011-windows-engine-field.md) — Field-verify engine TCP transport on Windows
11. [ticket-1012](ticket-1012-remote-cache-design.md) — Design local action keys for future remote cache
12. [ticket-1013](ticket-1013-affected-since-builds.md) — `jk build --affected-since=<ref>` for monorepos
13. [ticket-1014](ticket-1014-ide-engine-client.md) — IDE clients over engine wire protocol
14. [ticket-1015](ticket-1015-demand-memory-registry.md) — Revisit concurrent worker memory (instrument first)

---

## Notes

- **Out of scope for this board:** infinite ecosystem long tail (every AGP parity gap, full KMP
  multiplatform, marketplace plugins, RBE). Track those in feature plans when they become north stars.
