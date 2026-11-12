# Progress contract — step-priced ETA, residual countdown, likely range, holds

Status: **normative** for TUI / wire progress. Implementations live under
`shared/wire` (`WorkSchedule`, `RemainingWork`, `StepCost`, `WorkspaceProgressTracker`) and
`server/engine` (`StepEta`, `StepLedger`, `SuiteClassProgress`, `MemoryHolds`, `BuildService`).

## Goals

1. **One aggregate** for the whole request (single module, selection subset, or monorepo).
2. **Seed `R0` in milliseconds** — the schedule of every step the build will run, each priced at
   its own recent wall (`jk explain` ≡ `jk build`). No learned scale sits on top of it.
3. **A countdown that follows the work** — what remains is the schedule of the steps still to
   finish; a running step counts down by its clock, a running suite by its classes.
4. **An honest precision** — every estimate carries a likely range that the truth falls inside
   about four runs in five; a wide range is shown as the range.
5. **Pauses are not work** — a build waiting for memory is on hold: shown as such, its countdown
   stands still, and the hold teaches no timing.
6. **The bar never goes backwards** and never runs ahead of the estimate.
7. **Measured** — `bench/eta` scores every change against a corpus of real builds before it lands.

## Model

```
price(step)   = median wall of the step's latest samples (StepLedger), first rung that has any:
                same shape + same work set, same shape, compatible shape + same load + same work set,
                compatible shape + same load, other load × load ratio, (a suite) default profile as a floor;
                of an even count's middle two, the newer; a suite's samples read at its newest's workers
module(t)     = prefix + max(suite, packaging tails, guard chain)
R0            = WorkSchedule of the scheduled modules' step prices, in ms
R(t)          = WorkSchedule of what remains:
                  finished step       → 0
                  not started         → its price
                  running step        → max(5% of price, price − elapsed)
                  running suite       → price × its workers' scale (StepCost.scale) once it starts;
                                        f = its class-wall share read through its curve (SuiteCurve);
                                        price × (1 − f), then past half done the wall its rate
                                        projected at the last class (t_f / f) less elapsed, ≥ 5% of price,
                                        both on the wall clock; never less than a running class's
                                        recorded wall less the time it has run
range         = R(t) scheduled at each unfinished step's low, then high bound
countdown     = R_anchor − Δwork-time   # re-anchored on every engine sample, at least once a second
bar           = min(99%, work-elapsed / (work-elapsed + R(t)))
```

A step's **shape** is the run's test profile, whether it is a full rebuild and whether a class
filter narrowed its suites; the **work set** is the set of modules the forecast runs any step in,
and a run is recorded under the set its estimate was priced with, so the next run the forecast sees
the same way finds it: what runs beside a step decides its wall as much as the step does (the
engine's suite took 43 s beside a build's packaging and 56 s beside the CLI's suite). A full
rebuild is a forced one (`-F`/rebuild) or one that compiles at least half of its modules (four or
more): a first build on an empty cache does a forced build's work beside a forced build's load.
The whole shape comes first because it carries the run's load as well as its work: a guard beside
every compile of a full rebuild is not the guard of a filtered test run. Where the whole shape has
no history, the step keeps only what changes its own work (a suite keeps to its profile and is never
priced from a filtered run; a compile or native image keeps to a full rebuild or not) and prefers
runs under its own load. A step seen only under the other load is read at the **load ratio**: the
median, over the steps that ran under both, of their light wall over their full-rebuild wall (0.5
until three steps have; suites do not teach it, their light runs being faster mostly for the test
JVMs those runs give them). A suite under a profile it never ran in borrows its default profile's
wall as a floor, with a range up to three times it. A compile is never read across loads: with no
wall of its own under this load, a full compile reads its full rebuild's at the load ratio and a
partial one the median of the project's compiles under this load, never above its own full compile.

**Test JVMs.** The executor picks a suite's JVMs as it dispatches, the jobs budget over the modules
in flight then. A suite priced from runs of another shape or work set ran on the JVMs that work left
it; the schedule says how many modules are in flight at its dispatch, and the suite is read at that
share through its class walls (`StepCost.scale`) and priced for it.

**A first build.** A step the ledger knows nothing of is priced from the host's calibration: a
compile at a fixed cost plus a cost per source, a suite at its JVM's fixed cost plus a cost per
method on one JVM, then read at its dispatch share (`Calibration.coldCompileMs`, `coldSuiteMs`,
fitted to JumpKick's own first build).

A module's steps run in three branches after its prefix (the compiles and the jar): the suite, the
packaging tails, and the guard chain (`compile-guard`, `guard`), which nothing waits for. A forced
build's forecast lists a clean module with no steps; the estimate prices the steps the last run of
the same shape ran there. A test run's estimate drops the packaging tails it never runs, but for a fat jar a sibling's suite
launches.

Steps the build starts before the estimate arrives are recorded on the request and replayed into the
model when it does, each counted down from its real start.

A step's status matters: a cache hit is a restore, priced from restores, never from runs. A filtered
suite (`--class`) is priced as its test JVM plus the recorded walls of the classes it selects, or a
touch in a module it selects nothing in. Any other step with no history and no calibration of its
own falls back to the module-weight price of that step alone.

**A running suite.** Its classes run side by side and its last few alone, so the share of its class
walls done runs ahead of its clock: the engine's suite was 84% done by class walls at 71% of its wall.
Each finished suite records its **curve**, the share of its wall that had passed at each tenth of its
class walls, into its run's ledger line; the next run reads its class share through the pointwise
median of the latest five curves of the same shape and work set (else the same shape, else the same
profile and filter). One run's curve is noisy where a long class ends (the engine's suite was 98%
through its wall at 90% of its class walls in one run and 76% in the next two); curves of another
work set are not mixed in, since what runs beside a suite shapes its curve. Between class finishes the
suite counts down from the wall its rate last projected: a long last class reports nothing for seconds.
The rate takes over only past half the suite's wall: before it, the rate is mostly JVM start and
discovery, and a remainder of `elapsed × (1 − f) / f` swings by `elapsed / f²` per unit of share (at
a quarter, one curve's six points cut the engine's suite by 15 s). Neither share nor curve sees a long
class running alone at the end, so each class's start reaches the model (`TaskContext.testClassStarted`)
and the suite is held open for the recorded wall of every class still running: the engine's longest,
13 s, ran on after 91% of its class walls had finished, and the suite had read 2 s left for 11 s.

**A suite's workers.** The build picks a suite's test JVMs as it starts, from what else is running
then (`TestWorkers.liveShare`): the engine's integration suite got 2 in one run and 4 in the next and
took 239 s and 145 s. Each run records each suite's JVMs (the sharded pool's, one when every class is
serial-tagged) with its classes' recorded walls, their sum and the longest; a suite is priced at the
JVMs its newest sample ran on, its other samples read at that count, and once it starts on another
count it is re-priced at it. A wall goes with the suite's parallel bound, `max(longest class, class
walls / JVMs)`, to the power 0.6 (`StepCost.scale`): JVM start and contention take a share, and a
suite its longest class bounds gains nothing (the CLI's integration suite took 636 s on 2 and 631 s
on 8).

**Re-pricing.** The engine re-notes each live request's residual on every step and test-class event
and once a second in between (`EtaStepTicker`), so a running step counts down between events.

**Revisions.** Some verdicts wait on a sibling the build has yet to compile: the source-diff hint's
`likely recompile` or `likely up to date`, a recompile `only if the compile runs`
(`TaskForecast.Task.provisional`). When a module whose classes such a verdict reads publishes them,
the build walks every scheduled module's forecast again off its threads, reading what has compiled
from its outputs, and prices it under the seed's work set and shape (`BackgroundEta.reprice`, at
most one at a time, triggers coalescing). The result reaches the engine as a `WorkModel` with a
`revision`: it replaces the residual, its finished modules and steps replayed into it, and keeps
the clock the seed anchored. `eta-model.jsonl` records each revision.

**Before the estimate.** The estimate is priced beside the build and arrives within a second or two.
Until it does, execute moves the bar at most to 15%: the plan's weights count a fully cached
module's slice as done and would pin the bar far ahead of the clock. Nor does the bar move faster
than 1% a second: preflight's band is a tenth of the bar, yet it is about a second of a minute's
build, and a test run's bar read 17% when its estimate came at 1.4 s, then held there for 9.5 s
while the clock caught up.

**Likely range.** Each step's range is the 20th–80th percentile of the samples its price comes from,
never narrower than 15% either side; a filtered suite's is ±30%; a step priced with no history of
its own is half to twice its price. The range narrows as steps finish; a suite on its own rate
projects its remainder at its low and high over its price, so the range does not shrink to a point
as the last suite runs (it had, and half of warm checkpoints fell outside it). Warm runs' coverage
is judged against the target; a cold run misses by more than any range it could honestly show.
Surfaces show `~1m 32s` when the range is narrow and `1m 20s–1m 50s` once it spans more than 40% of
what remains (and at least 10 s).
`jk explain` prints `~1m 11s (likely 59s – 1m 30s)` and the critical path the estimate rides on,
`jk-core → jk-engine → jk-cli`: back from the module that finishes last, each admitted by the
classes of the one before (`WorkSchedule.criticalPath`; MCP's explain carries it as `criticalPath`,
and counts each module's `provisionalSteps`, the verdicts a revision will settle). jsonl and the wire carry the range beside
`remainingMs`: `lowMs` and `highMs` on `eta`, `etaLowMs` and `etaHighMs` on `workspace-progress`; MCP's `status` gives each job in
flight its `progress`, `remainingMs`, `etaLowMs` and `etaHighMs` under `estimates`.

**On hold.** A fork that has waited half a second for a worker lease (`WorkerLeases`) puts its build
on hold until the build's last waiting fork leaves the queue (`MemoryHolds`). While held, the
engine's clock and the build's running steps stand still; every surface shows the hold and restores
itself on resume. The forks not waiting run on, so a suite's rate is read on the wall clock: what
its classes did through the hold is real progress, and read against a clock that stood still the
engine's suite, started inside a 21 s hold, projected double its rate:

| Surface | On hold |
|---------|---------|
| TUI | gray bar, breathing `■` in place of the circle, one line `JumpKick is waiting for system memory to become available…` in place of the work rows, `On hold 12s` in place of the countdown |
| Plain | `JumpKick build paused - Waiting for system memory to become available...`, `On hold Ns` at the heartbeat, `JumpKick build resumed after Ns on hold` |
| jsonl / wire | `hold-start` (`needBytes`, `freeBytes`), `hold-end` (`heldMs`); `workspace-progress` carries `"state": "on-hold"` and `heldMs` |
| Web | the TUI's hold in the build pane; the countdown's anchors move by the time held |
| MCP | a parked call's progress notifications at the start, every 5 s, and on resume; `status` lists held jobs; the verdict names the time on hold |

A held run's long waits come off the walls its steps teach the ledger; the scorer judges a run on its
working time.

### Packaging cascade (hard rule)

| If dirty… | Then dirty… |
|-----------|-------------|
| **jar** | **native** (when the module builds one) |
| **jar** or **native** | **OCI** (when the module builds an image) |

Impossible: jar dirty + native clean. Impossible: jar/native dirty + OCI clean.
Implemented in `EffortWeights.jarWillChange` / `nativeWillChange` / `ociWillChange` and
native-image's predictor (`PlannerNative.forecast`).

Dirty-module prepare/run sets **over-reserve tails** so native/assembly/OCI reserve full
learned walls at plan-start even when an old binary still looks mtime-fresh vs the pre-build
jar. Runtime may **shrink** on cache hit (`RESTORE`); never reweight *up* mid-run.

### HARD INVARIANT: `jk explain` ≡ `jk build` seed `R0`

| Rule | Detail |
|------|--------|
| **One function** | Both call `BuildService.estimateEtaMillis` only for `R0`. Pinned by `ExplainBuildEtaParityTest` (`:engine`). |
| **One argument set** | Both derive the plan-affecting options through `BuildFlags.from` — workers (floored at 0), skipTests (incl. `--scripts-only`), profile, jdksDir, parallelTests, jobs, verbose, rebuild. Pinned by `BuildExplainPlanOptionsParityTest` (`:cli`), which parses one argv against both commands' option sets and compares. A flag on one command and not the other fails the parse there. |
| **One forecast** | Costs from `ForecastWalk` / `ExplainPlan` only. |
| **Material dirty only** | A module is dirty only if a step the forecast prices (compile/test/package/native/…) is not CACHED — steps that ride with their module (parse-build, resolve-deps, write-stamp) are never priced. Resource drift is material: the forecaster runs `copy-resources` (main/extra) or `compile-test` (test scope, which copies the suites' resources) only when trees actually drifted, and either schedules the module. Compile-consumer cascade seeds from **compile/package** only (not `copy-resources` alone): consumers hash the packaged jar; package is forecast against a post-copy projection when resources drifted. A dirty `order-after`-only prereq adds an unpriced `order-check` task: the dependent schedules (real action keys re-check out-of-band outputs) but contributes nothing to ETA. |
| **Price material steps only** | ETA costs skip bookkeeping steps even when the plan still runs them. Cascade-forced compile/package/**native** (`dependency changed` / `main changed` / `compile changed` without local *compile* content) are recheck tokens, not suite/native walls. Resource-only modules (copy/package resources) price package+copy only — never unlock compile/test suite walls. `run-tests` stays full when the module has local compile content, no compile steps (test-dep only), a heavy packaging tail forecast (cli-shaped), or a red marker under its current key (`run tests · … · last run failed` — a failed suite stores its counts under the same key a green one does, so the re-run is evidence rather than stamp drift); pure cascade modules discount tests. |
| **Same lock view** | The forecast reads `jk-lock.toml` through `MemberRows.view`, as the build compiles: a partition row another member holds stays off this module's processor path, so forecast and live compile keys agree. |
| **Same concurrency** | `etaConcurrency(jobs)` is the executor's module cap (`-j`, else resolved jobs). |
| **Seed path lock** | Client freezes the R0 *seed path* when execute starts (provisional eta thrash guard). Residual still re-anchors the painted countdown mid-run. |
| **Fully-cached fast path** | Empty dirty → `R0 = 0`, skip forecast walk. |
| **Missing outputs (incl. post-`jk clean`)** | Input fingerprints can still match while jars/classes/binaries are gone. Preflight splits **input-dirty** vs **restore-needed**. Restore-needed modules restore from the action cache (no full compile/test/package plans); priced at `EffortWeights.RESTORE`. Dirty-memo dual-writes under `~/.jk/cache/projects/…` so clean does not erase fingerprints. |

### Schedule admission (ETA ≡ live)

`WorkSchedule` admits **first ready in topo/list order**, at most `concurrency` in flight, and a
module is ready when every dirty prerequisite has passed its **gate** — the same policy as the
bounded `WorkspaceScheduler`, which admits a dependent when its build-order prerequisites have
published their classes trees (compile and resource copy), not when they have packaged or tested. A
test edge is no prerequisite here: it orders the consumer's test steps inside its plan. A cost carries the
gate as `ModuleWorkCost.gateWeight` (`EffortWeights.gatesDependents` names the steps); a cost that
never split its prefix gates on the whole prefix.

`concurrency` is the executor's cap: the request's `-j`, else the engine's resolved jobs. Not the
graph's ready width — with publish-time admission a build keeps as many modules in flight as the cap
allows.

Serial (`-j1` / concurrency ≤ 1): sum of module weights.  
When `parallelTests == false`: `max(scheduled, Σ testWeight)` as serial test floor.

## Engine owns aggregate math

| Component | Responsibility |
|-----------|----------------|
| `StepLedger` | Per project, the latest 60 successful runs' step walls with each run's shape, work set, suite curves and suite workers; prices and ranges |
| `StepEta` | Prices every step the forecast runs; the step-priced `R0` |
| `WorkSchedule` / `RemainingWork` | Schedule of module costs; the residual `R(t)` and its range, step by step |
| `SuiteClassProgress` | A running suite's share of its class walls done, under the run's own tier, and its curve when it finishes |
| `SuiteCurve` | A suite's class share read as the share of its wall: the median of the curves its recent runs measured |
| `WorkspaceProgressTracker` | Preflight band, bar strategies, peak hold, the hold clock, the range's gaps |
| `MemoryHolds` | Which builds are on hold for memory, and for how long |
| Wire `workspace-progress` / `eta` | `progress`, `numerator`, `denominator`, `phase`, `remainingMs`, `R0`, `etaLowMs`, `etaHighMs`, `state`, `heldMs` |

### Guards-off invariant (measured)

A project without `jk-guards.toml` pays nothing for JumpKick's own house-rule guards. The scoreboard's
`dogfood` dimension carries the raw `dogfood.<row>` and the guards-on `dogfood.<row>.guards` rows as
separate series in `scoreboard.toml`, and fails a night run when either regresses past its band —
see [docs/perf/README.md](../perf/README.md).

### Metrics hygiene

- Harvest uses **one project home per checkout path** (prefer `source=lock`, higher run count); a
  home whose `identity.toml` lists several checkouts (worktrees sharing one lock id) claims each.
- **Memory is bounded by the build, not the host.** A session in a project loads that project's
  ledger, expanded against its own root only. Any other session (the engine's working dir, a
  dashboard) reads the host tier: per-kind means and `[count]` in `host-metrics.toml`, written by
  the harvest. Nothing in a request loads every project, and `identity.toml` keeps the 32 most
  recently built checkouts, so a CI host's thousands of workspaces cost no heap.  
- **Heavy-step floors:** native-image &lt; 5s and write-image &lt; 3s walls are dropped at journal
  write and harvest (cache-restore noise).  
- Cancelled / failed builds do not train.

## Progress bar

Shared strategies (`cc.jumpkick.wire.runtime.progress` in `:wire`) — used by **engine
workspace-progress**, **CLI header**, and **web dashboard**:

| Mode | Strategy | When |
|------|----------|------|
| **clock** (default via AUTO) | `ClockProgressStrategy` — `elapsed/(elapsed+R(t))` when residual known, else `elapsed/R0` | R0 &gt; 0 (AUTO) or force clock |
| **weighted** | `WeightedProgressStrategy` — Σ effort weights | R0 absent (AUTO) or force weighted |

```bash
JK_PROGRESS_MODE=clock      # force open-loop (engine + CLI)
JK_PROGRESS_MODE=weighted   # force weight slices
# unset / auto              # clock when R0 > 0, else weighted
```

Web override (browser cannot read process env): `localStorage.jkProgressMode = 'clock'|'weighted'|'auto'`.

- Engine emits `progress` from the strategy on every `workspace-progress` event.  
- Web recomputes clock client-side from residual (+ `R0` fallback) so the bar stays smooth between
  events; countdown re-anchors on each residual sample (`residualAt` + `remainingMs`).  
- **Never go backwards** — one `SharedPeak` fraction floor per clock/weighted pair, so
  neither the AUTO weighted→clock takeover nor a denominator growth (calibrate/reweight) repaints
  lower; the web clamps per card (`card.peakPct`). Settle → 100%.

## Wire (schema 1, additive fields)

| Event | Fields |
|-------|--------|
| `eta` | `millis` (= remaining), `remainingMs`, `fullMillis` (explain), `lowMs`, `highMs` |
| `workspace-progress` | `progress`, `numerator`, `denominator`, `phase`, `modulesComplete`, `modulesTotal`, `remainingMs`, `R0`, `etaLowMs`, `etaHighMs`; on hold also `state: on-hold`, `heldMs` |
| `hold-start` / `hold-end` | `needBytes`, `freeBytes` / `heldMs` |

## Learning layout

```
~/.jk/state/builds/
  host-metrics.toml
  projects/<id>/
    identity.toml
    project-metrics.toml
    step-ledger.jsonl # the latest runs' step walls, shape, work set, suite curves and workers (StepLedger)
    runs/<build-number>/
      record.json
      details.jsonl
      metrics.toml
  sessions/<session-id>/
    session.json      # the session and its episodes, rewritten on each change
    actions.jsonl     # one line per run or action, appended
```

Per-class test walls are one table per package of a module in both files,
`[test-class."<dir>"."<pkg>"]` with a `<SimpleName> = <ms>` row per class (a default-package class
under `[test-class."<dir>"]` by its bare name), closing the file after the scalar rows; the run
file carries the measured walls, the ledger their trimmed means. A standalone project's plan
records them as a workspace member's does (`StandalonePlanTimings` attaches the same recorder
and folds its step rates), so its ETA and slow-class hints read the same data. Naming the module and the package
once each, instead of a qualified name per row, is what keeps a two-thousand-class ledger under
200 KB.

## Step metrics: wall and wait

`metrics.toml` records every successful step twice: `task.<step>.wall-ms` /
`module.<dir>.task.<step>.wall-ms` is the step's wall clock, and where the step spent part of
that wall blocked on a shared resource, `…wait-ms` beside it says how much. All Java compilation
in a job goes through one resident compiler worker, so on a wide build a module's `compile-java`
wall is mostly its place in that queue: thirty modules summing to a minute of `compile-java`
is a minute of machine-busy time, not a minute of javac. The wall stays the honest wall; the
learned per-unit rates (`StepTimings`) train on `wall − wait`, so a rate never prices the queue
as work. `task-finish` carries the same split as `waitMillis`, on the wire and in JSONL alike.

## Measurement

`bench/eta` holds the instrument: `score.py` scores a home's runs against their walls (seed error,
ETA error and bar error at 10–90% of the wall, plateau, tail, range coverage), `Replay.java`
replays a run's journal under candidate models, and `corpus.sh` runs a fixed set of scenarios on
JumpKick's own tree into a private home. A change to the ETA lands with corpus numbers.

## Out of scope

- Method-level live TUI rows
- Third-party metrics backends
- Allowing the bar to go backwards  
