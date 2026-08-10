# Progress contract — remaining wall-work `R(t)`

Status: **normative** for TUI / wire progress. Implementations live under
`shared/wire` (`RemainingWork`, `WorkSchedule`, `WorkspaceProgressTracker`) and
`server/engine` (`EffortWeights`, `TestEffort`, `BuildService.seedEta`, residual emit).

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **One remaining-work oracle `R(t)`** — schedule of unfinished module costs (with in-flight
   residual). Drives **both** the countdown and the progress bar.
3. **Real-work denominator** — weight what will actually run; cache/skip → token ticks in costs.
4. **Hierarchical learning** — test method → test class → task → stage → module → workspace.
5. **Dual clock** — remaining countdown from `R(t)`; dim `+elapsed` count-up from command start.
6. **details.jsonl** carries fine events; the header bar/clock stay run-wide.

## Remaining-work model

```
R0            = seed wall ms (jk explain ≡ jk build seed; history floors / margins applied)
ideal0        = WorkSchedule.schedule(costs)          # weight units, first-ready admission
weightToMs    = R0 / ideal0                           # bakes floors into residual
R(t)          = WorkSchedule.schedule(residual) × weightToMs
completeFrac  = 1 − R(t) / R0
bar percent   = min(99, 100 × completeFrac)           # until finish → 100
countdown     = R(t)                                  # live residual, not seed − elapsed
```

| Situation | Residual cost |
|-----------|----------------|
| Module not started | full `ModuleWorkCost` |
| Module in flight | `cost.residual(planNum/planDen)` |
| Module complete | absent |
| Task omitted from plan | never in costs |
| Cache/skip still in plan | TOKEN weight in costs |

### HARD INVARIANT: `jk explain` ≡ `jk build` seed `R0`

| Rule | Detail |
|------|--------|
| **One function** | Both call `BuildService.estimateEtaMillis` only for `R0`. |
| **One forecast** | Costs from `TaskForecaster` / `ExplainPlan` only. |
| **Same concurrency** | `etaConcurrency(...)` matches workspace scheduler clamp. |
| **Live residual** | Mid-execute `R(t)` reuses the same `WorkSchedule` + costs; scale preserves `R0`. |
| **Fully-cached fast path** | Empty dirty → `R0 = 0`, skip forecast walk. |

### Schedule admission (ETA ≡ live)

`WorkSchedule` admits **first ready in topo/list order**, full prereq completion, at most
`concurrency` in flight — same policy as bounded `WorkspaceScheduler` (not longest-first).

Serial (`-j1` / concurrency ≤ 1): sum of module weights.  
When `parallelTests == false`: `max(scheduled, Σ testWeight)` as serial test floor.

## Engine owns aggregate math

| Component | Responsibility |
|-----------|----------------|
| `EffortWeights` | Plan-time task weights; TOKEN for skip; dirty-task composition |
| `TestEffort` | `run-tests` pricing |
| `WorkSchedule` / `RemainingWork` | Wall schedule + residual `R(t)` |
| `WorkspaceProgressTracker` | Preflight band + `seedWall`/`setRemaining` → bar % |
| `BuildService` | `R0` seed + `WorkModel` emit |
| Wire `workspace-progress` / `eta` | Snapshots: `progress`, `remainingMs`, `R0` |

## Task pricing ladder (unchanged)

1. Module measured task wall  
2. Host task wall  
3. Residual per-unit rates × count  
4. Host continuous learned rates / calibration × host scale  
5. Tight static floors  

### Test task (`run-tests`)

1. Class walls when complete selection has walls  
2. Else whole `run-tests` task wall  
3. Else methods × method-ms + suite-startup  
4. Else host suite wall / cold baseline  

## Progress bar

Clients paint engine `workspace-progress` only (no client re-sum).

- Preflight: small band before `R0` is known.  
- Execute: pure `1 − R/R0`, display-capped at **99%** until `finish()` → 100%.  
- When residual under-predicts (`R > R0`), tracker grows `R0` to preserve completed work.

## Wire (schema 1, additive fields)

| Event | Fields |
|-------|--------|
| `eta` | `millis` (= remaining), `remainingMs`, `R0` |
| `workspace-progress` | `progress`, `numerator`, `denominator`, `phase`, `modulesComplete`, `modulesTotal`, `remainingMs`, `R0` |

## Learning layout

```
~/.local/state/jk/builds/
  host-metrics.toml
  projects/<hash>/
    project-metrics.toml
    runs/<build-number>/
      record.json
      details.jsonl
      metrics.toml
```

**Cancelled / failed builds do not train.**

## Out of scope

- Method-level live TUI rows  
- Perfect millisecond ETA (residual schedule is the correctness target)  
- Third-party metrics backends  
