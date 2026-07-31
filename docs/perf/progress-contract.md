# Progress contract — real-work aggregate bar + hierarchical effort

Status: **normative** for TUI / wire progress (JK-1150). Implementations: JK-1151–1154.

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **Real-work denominator** — weight what will actually run; cache/skip → token ticks.
3. **Hierarchical learning** — method → class → step → phase → module → invocation.
4. **Countdown first** — when a seed ETA is trustworthy, the TUI header counts down pure
   wall-clock from that total (`seed − elapsed`); count-up only if truly cold (`+Ns` from
   command start) or wall-clock overrun past the seed (`+Ns` excess). The clock never resets
   on phase/module boundaries.
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

**One routine for explain and build.** `BuildService.estimateEtaMillis` (`jk explain`) and the
build countdown seed both assemble dirty-module costs the same way (shape-memo `weight`+`testWeight`
pair when warm, else pipeline walk) then call `seedEta` (schedule + history prior). The initial
countdown figure must match the explain estimate even when that figure is imperfect.

1. **Compose from dirty steps.** For each dirty module, sum measured walls of steps that will run
   (`BuildMetrics` step `ok` averages preferred; residual `StepTimings` / static only when cold).
   Fully-cached modules contribute 0. A cached phase (e.g. compile up-to-date, tests dirty) is
   excluded from that module’s sum.
2. **Schedule like the live graph** (`WorkspaceScheduler`): a module starts only after every dirty
   prereq has *fully* finished, with a rolling concurrency window (list schedule, longest-first).
   Serial (`-j1`) is the plain sum. Serialized cross-module tests also apply a test-step sum floor.
3. **`--rebuild` / `--force` = treat every module/step as dirty** — same composition formula. Full
   work also floors the estimate at measured full-build walls (`build` / `build:rebuild` invocation
   avgs, weighted toward max when stable) so a consistent ~2m30s rebuild is not estimated as ~1m25s.
4. Whole-build history is otherwise a **cold seed** when no step costs exist, plus a one-sided clamp
   of absurd over-estimates (never pull partial work up).
5. **TUI clock freezes the seed** once execute starts. Successful runs teach step metrics +
   invocation walls for the next estimate.

Cold machine with zero step history: coarse dirty-module floor (JK-1179), then refine as work
runs and teaches the next estimate.

## Learning / recency (JK-1178)

| Store | What is recorded | Recency |
|---|---|---|
| `StepTimings` (`timings.toml`) | Per-step rates from real SUCCESS work | EWMA α=0.4; **near-zero samples dropped** (cache hits must not poison rates) |
| `BuildMetrics` (`metrics.json`) | Invocation wall under `build` / `build:rebuild` (+ `#dN`) | EWMA α=0.4 on success avg; count capped; failed/cancelled excluded from `ok` |
| Calibration | Host ms/weight + component probes | Full multi-probe on fresh install / `jk engine calibrate`; refined on successful runs |

**Cancelled builds must not train ETA.** Ctrl-C / `BUILD_CANCEL` / mid-job EOF / job deadline stamps
the request accumulator as user-cancelled immediately (even if the runner is force-killed before a
pipeline result). That truncated wall-clock goes into the `cancelled` bucket only — never `ok` —
so history priors and explain/build countdown seeds stay calibrated from full successes. Session
cancel also flips `PipelineResult.userCancelled` when the pipeline can finish cooperatively.

**Success-only teaching (estimator hygiene):**

| Layer | Trains on |
|-------|-----------|
| `BuildMetrics` invocation `ok` | Workspace success, not cancelled |
| `BuildMetrics` step `ok` | Full successful workspace only (cancelled runs train no step ok) |
| `StepTimings` rates | Successful non-cancelled module pipelines only |
| Host ms/method prior | Successful `run-tests` suites (`__host__/test-method-ms`) |
| `FetchTimings` | Successful remote CAS-miss downloads only (trimmed mean, drop top/bottom 10%) |

Failed steps still land in the `failed` bucket for diagnostics; they never contribute to `ok`
averages. Cold modules without local rates fall back to project/host medians, then host absolute
test-method ms, then static floors.

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
