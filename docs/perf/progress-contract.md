# Progress contract — open-loop ETA and effort-weight bar

Status: **normative** for TUI / wire progress. Implementations live under
`shared/wire` (`WorkSchedule`, `WorkspaceProgressTracker`, `RemainingWork`) and
`server/engine` (`EffortWeights`, `TestEffort`, `BuildService.seedEta`).

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **Open-loop seed `R0`** — wall-ms estimate at plan start (`jk explain` ≡ `jk build`). Users plan from the start; mid-run residual must not redefine the countdown.
3. **Effort-weight progress bar** — denominator is Σ plan step weights (measured walls preferred). Cache/skip → TOKEN. Bar **never goes backwards**.
4. **Real-work only** — bookkeeping steps do not inflate dirty set or ETA.
5. **Hierarchical learning** — test method → test class → task → stage → module → workspace.
6. **Dual clock** — countdown from open-loop `R0 − elapsed`; dim `+elapsed` count-up from command start.
7. **details.jsonl** carries fine events; the header bar/clock stay run-wide.

## Model

```
R0            = seed wall ms (jk explain ≡ jk build seed)
countdown     = max(0, R0 − elapsed)     # open-loop after execute starts (client freezes R0)
bar           = min(99%, elapsedSinceSeed / R0)   # same open-loop oracle as countdown
weight slices = fallback when R0 unknown; wire annotation / engine calibrate still use weights
residual R(t) = optional wire annotation # does NOT drive bar or countdown
```

**Why open-loop bar (not residual, not Σ weights alone):** residual under-prediction pinned the
bar at 99–100% while work remained. Σ effort weights front-load multi-module builds (parallel
weight mass early, serial long pole late). With a good R0 seed, `elapsed / R0` matches the
countdown so 50% bar ≈ half the wall estimate. Cap at **99%** until settle → 100%. Bar never
goes backwards (peak hold).

| Situation | Seed (R0) | Bar slice |
|-----------|-----------|-----------|
| Dirty real work | priced measured walls | full effort weight |
| Cache/skip in plan | TOKEN in composition | TOKEN / reweight shrink |
| Omitted from plan | absent | absent |

### Packaging cascade (hard rule)

| If dirty… | Then dirty… |
|-----------|-------------|
| **jar** | **native** (when the module builds one) |
| **jar** or **native** | **OCI** (when the module builds an image) |

Impossible: jar dirty + native clean. Impossible: jar/native dirty + OCI clean.
Implemented in `EffortWeights.jarWillChange` / `nativeWillChange` / `ociWillChange` and
`TaskForecaster` native forecast.

Dirty-module prepare/run sets **over-reserve tails** so native/assembly/OCI reserve full
learned walls at plan-start even when an old binary still looks mtime-fresh vs the pre-build
jar. Runtime may **shrink** on cache hit (`RESTORE`); never reweight *up* mid-run.

### HARD INVARIANT: `jk explain` ≡ `jk build` seed `R0`

| Rule | Detail |
|------|--------|
| **One function** | Both call `BuildService.estimateEtaMillis` only for `R0`. |
| **One forecast** | Costs from `TaskForecaster` / `ExplainPlan` only. |
| **Material dirty only** | A module is dirty only if a *material* step (compile/test/package/native/…) is not CACHED — not parse-build / resolve-deps / write-stamp bookkeeping. |
| **Price material steps only** | ETA costs skip bookkeeping steps even when the plan still runs them. |
| **Same concurrency** | `etaConcurrency(...)` matches workspace scheduler clamp. |
| **Open-loop clock** | Client freezes `R0` when execute starts; residual ETA events are not applied mid-run. |
| **Seed quality KPI** | `|R0 − execute_wall| / execute_wall` on success (`jk: eta-seed quality …` when serious or `JK_ETA_SEED_LOG=1`). |
| **Fully-cached fast path** | Empty dirty → `R0 = 0`, skip forecast walk. |

### Schedule admission (ETA ≡ live)

`WorkSchedule` admits **first ready in topo/list order**, full prereq completion, at most
`concurrency` in flight — same policy as bounded `WorkspaceScheduler` (not longest-first).

Serial (`-j1` / concurrency ≤ 1): sum of module weights.  
When `parallelTests == false`: `max(scheduled, Σ testWeight)` as serial test floor.

## Engine owns aggregate math

| Component | Responsibility |
|-----------|----------------|
| `EffortWeights` | Plan-time task weights; TOKEN for skip; packaging cascade; dirty over-reserve |
| `TestEffort` | `run-tests` pricing (own suite wall before method product) |
| `WorkSchedule` / `RemainingWork` | Wall schedule + residual annotation `R(t)` |
| `WorkspaceProgressTracker` | Preflight band + calibrated effort-weight bar; peak hold (never backwards) |
| `BuildService` | `R0` seed; dirty prepare uses live `estimatedTotalWeight` (not stale shape-memo) |
| Wire `workspace-progress` / `eta` | Snapshots: `progress`, `numerator`, `denominator`, `phase`, `remainingMs`, `R0` |

## Task pricing ladder

1. Module measured task wall  
2. Host task wall  
3. Residual per-unit rates × count  
4. Host continuous learned rates / calibration × host scale  
5. Tight static floors  

### Test task (`run-tests`)

1. **This module’s** whole `run-tests` task wall (preferred)  
2. Class walls when complete selection has walls  
3. Methods × hierarchical method-ms + suite-startup (cold module with known count)  
4. Host suite wall only when method count is unknown  
5. Cold baseline  

### Metrics hygiene

- `AggregatedMetrics.loadAll` / harvest use **one project home per checkout path** (prefer
  `source=lock`, higher run count).  
- Merge-by-higher-count when keys still collide.  
- Session workspace loads project-scoped metrics for live ETA.  
- **Heavy-step floors:** native-image &lt; 5s and write-image &lt; 3s walls are dropped at journal
  write and harvest (cache-restore noise).  
- Cancelled / failed builds do not train.

## Progress bar

CLI header bar strategies (`cc.jumpkick.cli.tui.progress`):

| Mode | Strategy | When |
|------|----------|------|
| **clock** (default via AUTO) | `ClockProgressStrategy` — `min(99%, elapsedSinceSeed / R0)` | R0 seeded (AUTO) or `JK_PROGRESS_MODE=clock` |
| **weighted** | `WeightedProgressStrategy` — engine Σ effort weights | No R0 (AUTO) or `JK_PROGRESS_MODE=weighted` |

```bash
JK_PROGRESS_MODE=clock      # force open-loop (needs R0; empty bar until seeded)
JK_PROGRESS_MODE=weighted   # force weight slices even when R0 is good
# unset / auto              # clock when R0 > 0, else weighted
```

- **Never go backwards** — peak hold inside each strategy.  
- Settle → 100%.  
- Clock advances on the animator frame (smooth through native-image).  
- Engine still calibrates and emits weight slices for dashboards / wire.

## Wire (schema 1, additive fields)

| Event | Fields |
|-------|--------|
| `eta` | `millis` (= remaining), `remainingMs`, `R0` |
| `workspace-progress` | `progress`, `numerator`, `denominator`, `phase`, `modulesComplete`, `modulesTotal`, `remainingMs`, `R0` |

## Learning layout

```
~/.local/state/jk/builds/
  host-metrics.toml
  projects/<id>/
    identity.toml
    project-metrics.toml
    runs/<build-number>/
      record.json
      details.jsonl
      metrics.toml
```

## Out of scope

- Method-level live TUI rows  
- Perfect millisecond ETA  
- Critical-path / remaining-work bar (Σ weights is the product bar)  
- Third-party metrics backends  
- Allowing the bar to go backwards  
