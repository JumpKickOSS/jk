# Progress contract — real-work aggregate bar + hierarchical effort

Status: **normative** for TUI / wire progress (JK-1150). Implementations: JK-1151–1154.

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **Real-work denominator** — weight what will actually run; cache/skip → token ticks.
3. **Hierarchical learning** — method → class → step → phase → module → invocation.
4. **Countdown first** — remaining time/work when any prior is trustworthy; count-up only if
   truly cold or wall-clock overrun (`+Ns`).
5. **details.jsonl** carries fine timings; the header bar/clock stay run-wide.

## Engine owns aggregate math

| Component | Responsibility |
|-----------|----------------|
| `EffortWeights` | Plan-time step weights; `TOKEN` for skip/check paths; learned rates |
| `StepTimings` / `BuildMetrics` | Durable learning stores (per-unit + avg ms) |
| `BuildService.seedEta` | Schedule-aware wall ETA + history priors (project then host) |
| `WorkspaceProgressTracker` | Preflight band + calibrated Σ module slices; peak-hold |
| Wire `workspace-progress` / `eta` | Snapshots clients must paint, not re-sum |

Clients (CLI, SSE, MCP) apply snapshots only (JK-1120/1121).

## Token vs real work

| Situation | Weight |
|-----------|--------|
| Step will do real compile/test/package work | Learned or static real weight |
| Stamp/CAS/action-cache skip (step still in pipeline) | **`TOKEN` (≥1)** — not full static, not silent 0 forever |
| Step omitted from pipeline | 0 (absent) |
| Fully-cached module always-run checks | Sum of tokens / `W_CACHED_TOUCH` |

At least one tick per phase that appears in the live tree.

## ETA seeding order

1. Schedule over dirty-module costs (warm dirs at `MS_PER_WEIGHT`, cold at host calibration or
   static `MS_PER_WEIGHT` so base is rarely zero).
2. History prior: project `build` invocation avg → host `dir=""` avg.
3. Clamp absurd over-estimates to 2× historical max when count ≥ 3 (one-sided; never clamp up).
4. Live re-project: elapsed + remaining schedule using measured ms/weight.

`--rebuild` / `--force` may distrust shape-memo weights but still seeds ETA early from history
and schedule when costs are known.

## Hierarchical lookup (effort prediction)

For a variable step (e.g. `run-tests`):

1. Module learned per-unit × planned count  
2. Project median of modules  
3. Host median  
4. `BuildMetrics` flat step avg  
5. Static constants  

Phase/module costs roll up child steps. Workspace ETA schedules **module** costs.

## Monorepo continuity

- Calibrate once with Σ real-work (+ token) weights for **dirty** modules.
- Module complete folds its slice into `completedBase`; next module must not zero numerator.
- Tree may show the active module; bar/ETA remain run-wide.

## Out of scope here

- Method-level live TUI rows  
- Perfect millisecond ETA  
- Third-party metrics backends  
