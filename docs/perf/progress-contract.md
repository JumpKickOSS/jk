# Progress contract — real-work aggregate bar + hierarchical effort

Status: **normative** for TUI / wire progress. Implementations live under
`server/engine` (`EffortWeights`, `TestEffort`, `TaskPhases`, `BuildService.seedEta`,
`WorkspaceProgressTracker`, metrics harvest).

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **Real-work denominator** — weight what will actually run; cache/skip → token ticks.
3. **Hierarchical learning** — test method → test class → task → phase → module → workspace.
4. **Dual clock** — when a seed ETA is trustworthy, TUI / web show dim italic remaining countdown
   and dim `+elapsed` count-up from command start. With no seed, yellow `+elapsed` only.
5. **details.jsonl** carries fine events; the header bar/clock stay run-wide.

## Engine owns aggregate math

| Component | Responsibility |
|-----------|----------------|
| `EffortWeights` | Plan-time task weights; `TOKEN` for skip/check; dirty-task composition |
| `TestEffort` | `run-tests` pricing (class walls or method product) |
| `BuildStage` / `TaskPhases` | Closed stage taxonomy for rollup (UI fold + ETA; not a lifecycle scheduler). Prefer `BuildStage`. |
| Project runs + harvest | Per-run `metrics.toml` → `project-metrics.toml` + `host-metrics.toml` |
| `BuildService.seedEta` | Schedule-aware wall ETA + history floors |
| `WorkspaceProgressTracker` | Preflight band + calibrated Σ module slices |
| Wire `workspace-progress` / `eta` | Snapshots clients paint (no client re-sum) |

## Token vs real work

| Situation | Weight |
|-----------|--------|
| Task will do real compile/test/package work | Learned or cold real weight |
| Stamp/CAS skip (task still in plan) | **`TOKEN` (≥1)** |
| Task omitted from BuildPlan (not on target closure) | Absent (0) |

Only tasks **present in the BuildPlan** for the invocation can contribute to ETA and train rates.

## ETA composition

```
workspace ETA  = schedule(module costs)   # list-schedule + concurrency; serial-test floor when needed
module ETA     = Σ phase ETAs
phase ETA      = Σ dirty forecast task ETAs mapped to that phase (TaskPhases.of)
run-tests ETA  = TestEffort (below)
```

### HARD INVARIANT: `jk explain` ≡ `jk build` countdown seed

The number printed by `jk explain` and the **seed** ETA that drives the live countdown on
`jk build` **must match exactly** for the same workspace and the same flags/env/defaults.

| Rule | Detail |
|------|--------|
| **One function** | Both call `BuildService.estimateEtaMillis` only. No second cost assembly on the build path. |
| **One forecast** | Costs come only from `TaskForecaster` / `ExplainPlan` (`etaCostsFromExplainPlan`). Never from prepared `BuildPlan` weights, shape-memo rows, or history-only shortcuts for the seed. |
| **Same defaults** | Bare `jk explain` and bare `jk build` use the same `-w` (0 = auto), `-j` / jobs, and parallel-tests defaults. |
| **Same concurrency** | `etaConcurrency(maxReadyWidth, workers, parallelTests, maxModuleConcurrency)` — identical clamp as the workspace scheduler. |
| **If explain is wrong, build is equally wrong** | Never “fix” the build countdown with a different oracle. |
| **Fully-cached fast path** | When the dirty memo reports an empty dirty set, **both** `jk build` and `jk explain` skip `TaskForecaster` (seed / estimate is `0` / &lt;1s). A monorepo walk is multi-second and would regress the ≲100ms up-to-date path. `explain --redo` / `--force` still walk. |

Workspace ETA is **schedule-aware** (not a pure sum when module concurrency &gt; 1). Serial (`-j1`)
is the plain sum of module costs.

### Task pricing ladder

1. This module’s measured task wall (`module.<dir>.task.<name>.wall-ms`)
2. Host task wall (`task.<name>.wall-ms`)
3. Residual per-unit rates × count (compile sources, etc.)
4. Host continuous learned rates / calibration baselines × host scale
5. Tight static floors

### Test task (`run-tests`)

1. **Class walls (preferred):** when every FQCN in the selection has a measured
   `module.<dir>.test-class.<FQCN>.wall-ms`, ETA = suite-startup + Σ class walls.
   **No method count is used.**
2. Else this module’s whole `run-tests` task wall.
3. Else methods × method-ms (module residual → project median → host absolute → calibration)
   + suite-startup. Method count comes from plan ticks / discovery totals already on hand —
   never a class-file walk for ETA alone.
4. Else host suite wall / cold startup baseline.

Class walls are recorded from runner **CONTAINER** `finished` events (`duration_ms`) on green
runs; written into run `metrics.toml` and harvested.

## Learning layout

```
~/.local/state/jk/builds/
  host-metrics.toml
  projects/<hash(coord+\0+path)>/
    project-metrics.toml
    runs/<build-number>/
      record.json
      details.jsonl
      metrics.toml
```

| Key pattern | Meaning |
|-------------|---------|
| `task.<name>.wall-ms` | Host mean for a task |
| `module.<dir>.task.<name>.wall-ms` | Module task wall |
| `phase.<name>.wall-ms` | Host mean for a phase |
| `module.<dir>.phase.<name>.wall-ms` | Module phase wall (sum of task walls that ran) |
| `module.<dir>.test-class.<FQCN>.wall-ms` | Successful class container wall |
| `host.*` / continuous learned rates | Method-ms, suite-startup, compile-per-source, … |

**Cancelled / failed builds do not train.** Only `SUCCESS` tasks on a successful plan finish
update rates. Tasks not in the plan never update.

## Host calibration

Bootstrap probes measure host scale vs a reference machine. Continuous learning folds successful
absolute walls into trimmed means. Cold ETA uses product baselines × host scale when no local
history exists.

## Progress bar

Clients apply engine snapshots only. Tree may highlight the active module/task; bar and ETA stay
run-wide. Cached tasks still show token ticks so the denominator does not collapse.

## Out of scope

- Method-level live TUI rows  
- Perfect millisecond ETA  
- Third-party metrics backends  
