# Engine heap monorepo measure (JK-1075)

**Date:** 2026-07-21 (UTC)  
**Binary:** `jk 0.10.1` (local install)  
**Harness:** `scripts/heap-monorepo-measure.sh` + polled `jk engine status --output json`  
**Fixture:** 200 independent modules, one-line `Lib.java` each, workspace root, `jk build --skip-tests`  
**Engine cap:** default `max-heap-mb = 256` (`heapMaxBytes` ≈ 247.5 MiB)

## Results (no dashboard client; single concurrent build)

| metric | MiB |
|--------|----:|
| Idle heapUsed (fresh start) | ~8.5 |
| **Peak heapUsed** (200-module build) | **36.4** |
| Peak heapCommitted | 40.9 |
| Final heapUsed (after build) | 28.1 |
| heapMax | 247.5 |
| **Peak used / max** | **~14.7%** |
| Build wall | ~23 s for 200 modules |

RSS via status was unobservable on this host (`rssBytes = -1`); heap figures come from the JVM MX bean (same source as `jk engine status`).

## Decision (JK-1075 gate)

| Gate | Threshold | Observed |
|------|-----------|----------|
| Headroom OK | peak &lt; ~150 MiB and &lt; 70% of max | **36.4 MiB, ~15%** |

**Outcome: headroom_ok**

- Keep default engine heap at **256 MiB**.
- Do **not** promote JK-1083 (accumulator bounds) on this evidence.
- Do **not** promote JK-1085 (256→512 default).
- Add a regression guard so a future change that blows the default max fails tests.

## Not measured here (still fine for decision)

- Dashboard SSE attached during build (status polling already stresses the same accumulator path lightly; full UI client is optional follow-up if we ever see journal growth bugs).
- 2–3 concurrent builds into one engine (would raise peak but unlikely to 5× toward 256 MiB given 36 MiB single-build).

## Re-run

```bash
# regenerate + lock + measure (slow first lock: default test deps × modules)
MODULES=200 ./scripts/heap-monorepo-measure.sh

# or reuse fixture under build/heap-monorepo-fixture after first lock
```

## Product note

Heavy work remains in **worker JVMs** under `HeapPlan`. The engine is a thin coordinator; these numbers support keeping that identity.
