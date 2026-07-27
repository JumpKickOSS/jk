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
2. History prior: project shaped key (`build` or `build:rebuild`, optional `#dN` dirty count) →
   bare project dir → host `dir=""` for that kind → host bare `build`.
3. Clamp absurd over-estimates to 2× historical max when count ≥ 3 (one-sided; never clamp up).
4. Live re-project: elapsed + remaining schedule using measured ms/weight.

`--rebuild` / `--force` may distrust shape-memo weights but still seeds ETA early from
**rebuild-shaped** history and a coarse dirty-module floor so the TUI can countdown (JK-1179).

## Learning / recency (JK-1178)

| Store | What is recorded | Recency |
|---|---|---|
| `StepTimings` (`timings.toml`) | Per-step rates from real SUCCESS work | EWMA α=0.4; **near-zero samples dropped** (cache hits must not poison rates) |
| `BuildMetrics` (`metrics.json`) | Invocation wall under `build` / `build:rebuild` (+ `#dN`) | EWMA α=0.4 on success avg; count capped; failed/cancelled excluded from `ok` |
| Calibration | Host ms/weight + component probes | Full multi-probe on fresh install / `jk engine calibrate`; refined on successful runs |

Successful **`jk build --rebuild`** always folds timings + metrics under **`build:rebuild`** (request
flag stored on the accumulator — not ambient session at journal write). Newer successes supersede
older ones via EWMA; multi-year raw averages are not used as the sole ETA prior.

## Host calibration suite (JK-1180)

Offline micro-probes (no network), **pessimistic** aggregates (max of warm samples after one
cold-cache discard). Stored in `~/.jk/state/builds/calibration.toml`:

| Probe | What it stands for in real builds |
|---|---|
| `jvm-fork-ms` | `java -version` process spawn (warm worst-of) |
| `javac-ms` | Micro compile (~12 sources) |
| `disk-io-ms` | 4 MiB write+fsync+read (local I/O) |
| `hash-cpu-ms` | 8 MiB SHA-256 (CPU-bound, CAS-like) |
| `junit-fork-ms` / `junit-run-ms` | Synthetic test-worker JVM + known body work |
| `junit-platform-ms` | Real JUnit Platform Launcher + 1 `@Test` (when Jupiter jars in cache or fetched) |
| `resolve-ms` | HTTP GET of a tiny Central artifact (`--with-network` only) |
| `engine-cold-start-ms` | Client-timed cold engine spawn (`jk engine calibrate`) |
| `ms-per-weight` | Combined wall ÷ static weight model; floored near the historical constant |

Triggers: first `Calibration.ensure` (explain/build when cold), or explicit `jk engine calibrate
[--force] [--with-network]`. Without `--with-network`, resolve is skipped and JUnit Platform runs
only if jars are already local. Per-project `StepTimings` always win once present.

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
