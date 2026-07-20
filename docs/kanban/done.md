# Kanban — Done

Finished tickets (newest at top). Keep one-liners for history; ticket files stay in this
directory as the record of what shipped.

**Board:** [backlog](backlog.md) · [ready](ready.md) · [wip](wip.md) · [blocked](blocked.md) · [done](done.md)

---

## Done Items

1. [ticket-1034](ticket-1034-outdated-deps.md) — `jk outdated` polish (guide, JSON schema, footer, offline note)
2. [ticket-1042](ticket-1042-cli-test-suite-speed.md) — CLI suite speed (~29%: hybrid warm engine)
2. [ticket-1022](ticket-1022-tempdir-engine-cas-cleanup.md) — Drop TempDir `cleanup.mode=never`
3. [ticket-1043](ticket-1043-engine-zombie-resilience.md) — Engine zombie / silent-peer recovery
4. [ticket-1031](ticket-1031-module-task-selectors.md) — Module selectors (`--modules` globs/braces; ∩ with `--affected-since`)
2. [ticket-1039](ticket-1039-build-logic-graph-tasks.md) — Build-logic multi-task independent action cache
3. [ticket-1040](ticket-1040-selective-prepare-run.md) — `jk selective resolve|prepare|run`
4. [ticket-1041](ticket-1041-bsp-import-reliability.md) — BSP per-target compile/deps/sources + reload
5. [ticket-1037](ticket-1037-programmable-escape-hatch-mvp.md) — Project build logic MVP (`.jk-build/` + `[build].logic`)
6. [ticket-1030](ticket-1030-warm-compiler-pool-benchmark.md) — Warm pool measure: **DEFER** (docs/perf/warm-pool-bench.md)
7. [ticket-1028](ticket-1028-bsp-engine-host.md) — BSP install + minimal stdio server via IdeEngineClient
8. [ticket-1029](ticket-1029-incremental-compile-contracts.md) — Incremental compile ABI contracts (+ ClassAbiContractTest)
9. [ticket-1027](ticket-1027-selective-test-plan.md) — `jk test --affected-since` + shared AffectedSelection
10. [ticket-1026](ticket-1026-programmable-escape-hatch-design.md) — Escape hatch design PRD
11. [ticket-1025](ticket-1025-watch-mode.md) — `jk watch` (+ `dev` = `watch run`; one live-loop model)
12. [ticket-1024](ticket-1024-microbench-harness.md) — `scripts/microbench.sh` + docs/perf
13. [ticket-1023](ticket-1023-build-timeline-profile.md) — Chrome timeline profile every build/test
7. [ticket-1021](ticket-1021-cli-wire-test-fixture.md) — CLI wire-test fixture hardening (post-1020)
2. [ticket-1020](ticket-1020-eliminate-cli-engine.md) — Eliminate `:cli-engine`; strict client↔server wire separation
2. [ticket-1018](ticket-1018-self-host-phase2.md) — Self-host phase 2: plugins on workspace + installDist path
2. [ticket-1014](ticket-1014-ide-engine-client.md) — IDE engine client facade (sync/build events)
2. [ticket-1010](ticket-1010-private-plugins.md) — Private plugin jars (path/coord + sha256 pin)
3. [ticket-1007](ticket-1007-bootstrap-jk-on-jk.md) — CI/bootstrap builds jk with jk (phase 1 workspace + CI)
4. [ticket-1015](ticket-1015-demand-memory-registry.md) — Concurrent worker memory (instrument first)
5. [ticket-1013](ticket-1013-affected-since-builds.md) — `jk build --affected-since=<ref>` for monorepos
6. [ticket-1012](ticket-1012-remote-cache-design.md) — Remote cache design (read-only first)
7. [ticket-1011](ticket-1011-windows-engine-field.md) — Windows engine transport field verification
8. [ticket-1009](ticket-1009-explain-rebuild-ux.md) — `jk explain` / why-rebuilt hero UX
9. [ticket-1008](ticket-1008-gradle-version-catalog-import.md) — Gradle version catalog import (fidelity)
10. [ticket-1016](ticket-1016-cli-web-color-sync.md) — CLI theme color sync: adopt the web UI palette
11. [ticket-1006](ticket-1006-cross-package-features.md) — Cross-package feature selection on dependencies
12. [ticket-1005](ticket-1005-resolve-suggestion-engine.md) — Conflict diagnostics: actionable suggestions
13. [ticket-1004](ticket-1004-cas-invariant-tests.md) — Permanent CAS/action-cache invariant tests
14. [ticket-1002](ticket-1002-classifier-package-identity.md) — Solver package identity includes type/classifier
15. [ticket-1001](ticket-1001-pre-1.0-wire-hardening.md) — Pre-1.0 wire protocol + CLI freeze
16. [ticket-1003](ticket-1003-doc-reconsolidation.md) — Public docs cutover: ≤4 product docs + kanban board
