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
build countdown seed both call `EffortWeights.costFromRunningSteps` + `seedEta` with the same
inputs: dirty running steps, **unit counts** (sources / test methods), project dirs, and test
workers. Explain takes counts from the forecast (`sourceCount` / `testCount`); build takes them
from prepared pipeline ticks (`stepCountsFromPipeline`). Empty counts must never be passed on a
cold host — that prices every `run-tests` as suite-startup only and yields a ~10s countdown next
to a multi-minute explain. The initial countdown figure must match the explain estimate even when
that figure is imperfect.

**Same lock first.** `jk explain` and other lock-dependent read commands refresh a missing/stale
lock before forecasting. Staleness is content-addressed (`manifests-sha256` vs live digests of every
`jk.toml` that feeds the lock). Locks without a valid digest are always stale (one re-lock stamps
them). That gives explain and build an identical dirty set. The engine emits remaining-work ETAs;
the CLI seeds the clock as `elapsed + remaining` so preflight/lock time is not double-counted.

1. **Compose from dirty steps.** For each dirty module, sum measured walls of steps that will run
   (`BuildMetrics` step `ok` averages preferred; residual `StepTimings` / static only when cold,
   scaled by unit counts). Fully-cached modules contribute 0. A cached phase (e.g. compile
   up-to-date, tests dirty) is excluded from that module’s sum.
2. **Schedule like the live graph** (`WorkspaceScheduler`): a module starts only after every dirty
   prereq has *fully* finished, with a rolling concurrency window (list schedule, longest-first).
   Serial (`-j1`) is the plain sum. Serialized cross-module tests also apply a test-step sum floor.
3. **`--redo` / `--force` = treat every module/step as dirty** — same composition formula. Full
   work also floors the estimate at measured full-build walls (`build` / `build:rebuild` invocation
   avgs, weighted toward max when stable) so a consistent ~2m30s rebuild is not estimated as ~1m25s.
   "Full work" = rebuild shape, or ≥ 16 dirty modules — deliberately absolute, not
   workspace-relative (JK-1302): the floor source is keyed by dirty count (`#dN`), so a wide but
   cheap incremental build is floored against builds of its own shape, never against full-rebuild
   walls; the constant only decides when the floor engages.
4. Whole-build history is otherwise a **cold seed** when no step costs exist, plus a one-sided clamp
   of absurd over-estimates (never pull partial work up).
5. **TUI clock freezes the seed** once execute starts. Successful runs teach step metrics +
   invocation walls for the next estimate.

Cold machine with zero step history: bootstrap `Calibration.ensure` (multi-method JUnit Platform
when online) then price dirty steps as **product baseline × host scale × cold bias**. Uncalibrated
hosts use scale=1 (baselines alone). Successful builds refine continuous `learned-*` rates and
per-module step walls for the next estimate.

## Learning / recency (JK-1178)

| Store | What is recorded | Recency |
|---|---|---|
| `StepTimings` (`~/.jk/state/builds/timings.toml`) | Per-step rates from real SUCCESS work (not under CAS cache) | EWMA α=0.4; **near-zero samples dropped** (cache hits must not poison rates); migrates legacy `~/.jk/cache/timings.toml` once |
| `BuildMetrics` (`~/.jk/state/builds/metrics.json`) | Invocation wall under `build` / `build:rebuild` (+ `#dN`) | EWMA α=0.4 on success avg; count capped; failed/cancelled excluded from `ok` |
| Calibration | Bootstrap probe + continuous host rates (`~/.jk/state/builds/calibration.toml`) | `Calibration.ensure` on explain/build ETA (network unless `--offline`); `jk engine calibrate`; learned trimmed means on success |

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
| Continuous host rates | Successful builds → `calibration.toml` `learned-*` trimmed means |
| `FetchTimings` | Successful remote CAS-miss jar downloads only (trimmed mean, drop top/bottom 10%) |
| `LockTimings` | Successful locks: graph-ms/package, materialize-ms/package, fixed overhead (trimmed means); every project trains the host |

Failed steps still land in the `failed` bucket for diagnostics; they never contribute to `ok`
averages. Cold modules without local rates fall back to project/host medians, then host absolute
test-method ms / continuous calibration, then tight static floors.

Successful **`jk build --redo`** always folds timings + metrics under **`build:rebuild`** (request
flag stored on the accumulator — not ambient session at journal write). Newer successes supersede
older ones via EWMA; multi-year raw averages are not used as the sole ETA prior.

## Host calibration suite (JK-1180 + continuous learning)

**Bootstrap probes** (pessimistic: max of warm samples after one cold-cache discard) measure how
*this host* compares to a **reference laptop**. They do **not** become absolute cold method costs —
empty Platform `@Test`s run in a few ms and under-shoot real suites by an order of magnitude.

**Cold ETA model:** product **baselines** (realistic unit-test / compile / package guesses) ×
**host scale** (probe wall ÷ reference wall, clamped) × thin **cold bias** (~1.10 — prefer slight
over-estimate; not the primary fit knob). Cold ETA does not credit within-module {@code -w}
speedup (runtime still parallelizes). Full dirty monorepos without invocation history use ~75% of
job concurrency and a thin contention margin. Baselines/schedule are provisional (fit on the jk
monorepo); re-fit when multi-project OSS ports exist.

**Continuous learning** folds successful-build absolute walls into trimmed-mean rings; those win
over baselines when present. Stored in `~/.jk/state/builds/` next to step timings and metrics
(survives `jk clean` and cache GC):

| Field | What it stands for |
|---|---|
| `jvm-fork-ms` | `java -version` process spawn (warm worst-of) — fork scale |
| `javac-ms` | Micro compile (~12 sources) — CPU scale |
| `disk-io-ms` | 4 MiB write+fsync+read (local I/O) — I/O scale |
| `hash-cpu-ms` | 8 MiB SHA-256 (CPU-bound, CAS-like) — CPU scale |
| `junit-fork-ms` / `junit-run-ms` | Synthetic test-worker JVM + known body work |
| `junit-platform-ms` | Real JUnit Platform Launcher + trivial `@Test`s — fork scale |
| `resolve-ms` | HTTP GET of a tiny Central artifact (network RTT scale only — **not** a full PubGrub lock) |
| `probe-test-*-ms` / `probe-compile-per-source-ms` | Diagnostic residuals only (not absolute cold ETA) |
| `learned-*` sample rings | Continuous host rates from real builds (trimmed mean) |
| `engine-cold-start-ms` | Client-timed cold engine spawn (`jk engine calibrate`) |
| `ms-per-weight` | Diagnostic host anchor (not the primary ETA scale) |

**Lock ETA is size-aware, not a single whole-lock average.** Bootstrap calibration does **not** run
PubGrub or lock a real project — only a micro HTTP probe (`resolve-ms`) for network scale. Real lock
learning is continuous: every successful `jk lock` / auto-freshen / conservative re-lock records
atomized rates into `~/.jk/state/builds/lock-timings.toml`. Estimate:

```
overhead + packages × graph_per_package + packages × materialize_per_package
```

`packages` comes from the existing lock when present, else `declared_roots × ~10`. A 200-dep graph
and a 2-dep graph therefore scale differently; a workspace union of hundreds of modules uses the
merged package count (one lock), not `modules × deps`. Remote jar misses additionally train
`FetchTimings` (absolute download walls).

**Triggers:** `Calibration.ensure` at the start of every explain/build ETA assembly (no-op when a
current measured file exists), or explicit `jk engine calibrate [--force]`. Network is **on by
default**; opt out with global **`--offline`**.

## Hierarchical lookup (effort prediction)

For a variable step (e.g. `run-tests`):

1. Module measured step wall (`BuildMetrics` ok)  
2. Module / project residual rates (`StepTimings`)  
3. Host residual median + `__host__/test-method-ms`  
4. Continuous `calibration.toml` learned rates (alien project)  
5. Product baseline × host scale (probe vs reference) × cold bias  
6. Uncalibrated product baselines (same as scale=1)  

Phase/module costs roll up child steps. Workspace ETA schedules **module** costs.

## Monorepo continuity

- Calibrate once with Σ real-work (+ token) weights for **dirty** modules.
- Module complete folds its slice into `completedBase`; next module must not zero numerator.
- Tree may show the active module; bar/ETA remain run-wide.

## Out of scope here

- Method-level live TUI rows  
- Perfect millisecond ETA  
- Third-party metrics backends  
