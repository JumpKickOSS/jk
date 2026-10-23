# The build lifecycle

Every command that builds a project — `jk compile`, `test`, `build`, `install`, `native`,
`image`, `publish`, `train`, `run`, `dev` — runs one lifecycle:

```
preflight (lock, graph)  →  sync  →  plan  →  compile  →  test  →  package  →  (the goal's tail)
```

Code generation is part of COMPILE: a plugin's source generators (a jOOQ or OpenAPI preset, a
`[generate]` step) run before the compiles that read their output, so `jk compile` generates exactly
as `jk build` does. Every core plan holds every plugin step, and the terminal keeps the ones its
steps require — the generators the compiles read, what feeds the test JVM, and, when the goal
packages, what feeds the artifact. A step that requires one the plan does not hold fails the plan.

The steps up to a goal's last phase are the **same steps, built by the same code**, for every
goal. Only two things may differ between goals: the **tail** that runs after PACKAGE, and whether
the goal runs **tests**. Never run one command to prepare another: `jk install` is `jk build` plus
the install, `jk publish` is `jk build` plus the upload.

## The pieces

| Piece | Where | What it owns |
|-------|-------|--------------|
| The SYNC phase | `WorkspaceSyncPhase` (`runtime.workspace`) | `SyncPlans.syncBuildPlan`, the plan `jk sync` runs, once per build: locked artifacts, plugins, tools and the JDK on disk before any module plans. A module's `resolve-deps` only derives its classpaths from them |
| The goal table | `WorkspaceTarget` (`shared/wire`) | each goal's last phase (`COMPILE`, `TEST`, `PACKAGE`) and test policy (`ALWAYS`, `UNLESS_SKIPPED`, `NOT_RUN`) |
| The planner | `ModulePipeline` (`server/engine`, `runtime.workspace`) | the module's `BuildPlanner.Inputs`, the core through the goal's phase, the PACKAGE phase's declared deliveries and test join, then the tail |
| A tail | `Tail` (`runtime`); `InstallPlans.tail`, `NativePlans.tail`, `ImagePlans.tail`, `TrainPlans.tail`, `PublishTail` | the goal's own steps, appended with `BuildPlan.Builder.extend` |
| The client verb | `BuildVerb` (`clients/cli`, `command.pipeline`) | declares its `WorkspaceTarget`; its shared options and `BuildRun.skipTests()` follow from the target's policy |

A plan is a task graph pruned to its terminal (`BuildPlan.Builder.terminal`): only the terminal
and what it requires survive. The test suite stays in a packaging plan because the PACKAGE phase
joins `run-tests` and the declared deliveries under one terminal; a tail keeps them by requiring
that terminal, which is exactly what `extend` does. `BuildPlan.Builder.build()` refuses a plan
re-rooted onto a step that no longer reaches the terminal it replaced, so a tail cannot drop a
phase silently.

## Adding or changing a goal

1. Add or change its row in `WorkspaceTarget`: the last phase and the test policy.
2. If it does work past PACKAGE, write a `Tail` beside the other tails. Append every step with
   `plan.extend(task)`; a step that must run before the build (a refusal) is `addTask` with a
   `requires` on `parse-build`, and the extended step requires it. Every tail step declares how it
   is forecast (`Task.Builder.forecast`, see [Forecasting](#forecasting)).
3. Give `ModulePipeline` the tail (from the spec, or from the verb's request).
4. Point the client `BuildVerb` at the target. Its options and test skipping follow.
5. Add the goal's tail steps to `LifecycleParityTest.TAIL_STEPS`. The test plans every goal on
   every module of a fixture workspace and requires exactly `jk build`'s steps plus the tail.

Never call `BuildPlanner.coreBuilder`, `PlannerTails.appendDeclaredTails` or `joinTests`, or
construct `BuildPlanner.Inputs`, outside the pipeline: the `one-module-planner` guard refuses it.
Never re-root a plan with `terminal(name)` from a tail. Never decide "skip tests" in a verb: the
goal's policy and `--skip-tests` decide it, in `BuildRun.skipTests()` and `ModulePipeline`.

## Forecasting

Every step of a module's plan declares how it is forecast, on its `Task.Builder` chain:
`forecast(StepForecast.priced(predictor))` prices it against the caches its live step keys on,
`always(why)` runs on every invocation (an upload, an image push, a training run), `RIDES` never
schedules its module alone (parsing, stamps, joins, hooks). The `steps-declare-forecast` guard
refuses a step that declares nothing.

A predictor is built by the same factory as the step's body and reads the same key owner
(`PackagingKeys`, `GuardKeys`, the shared compile requests; `forecast-key-parity` refuses a key
built anywhere else). It receives the module's `ForecastScope`: what every predictor reads of the
module (its lock, layout, sources, toolchain), what the steps it requires left for it (whether the
compile runs, the keys a wiped tree is projected from, resource drift), and the workspace state it
keys siblings by (the restore ledger, the dependencies' dirtiness and API hints).

`ForecastWalk` (over `TaskForecaster`'s wave walk) prices a workspace: for each module it builds
the plan `ModulePipeline` builds for the goal's phase and prices its steps in dependency order,
the main compiles first among the steps ready at once. Each verdict is reported under its step's
name. Once every step is priced the module adds its own verdicts: `restore-outputs` when every step
is cached but an output is missing, `order-check` when an order-after sibling is rebuilding.

The preflight decides which modules a run schedules in two parts:

- **The lifecycle.** Every goal that packages forecasts `jk build`'s lifecycle through PACKAGE
  (COMPILE and TEST through their own phase): the same walk, and the same dirty memo, which any
  complete run through PACKAGE certifies clean. So an install, a native image or a publish agrees
  with `jk build` on which modules are dirty.
- **The tail.** `WorkspacePreflightPhase.withRunningTails` builds each module's plan with
  `ModulePipeline.planned`, which reports the steps the goal's tail appended, and prices each by
  its declaration. A module is scheduled when its lifecycle is dirty or a tail step runs.

## One orchestrator

Every goal enters the engine as one `build-request` naming its `WorkspaceTarget` and the tail's
arguments (`TailArgs`), served by `WorkspaceBuildVerb` and run by `WorkspaceExecute`. A standalone
project is a workspace of one: the client's `BuildVerbCommand` routes it through the same
`workspace()` path with a standalone chrome (no `[k of N]` rows, the lone-project wording), and
`InstallCommand` and `PublishCommand` hand their builds to the same path. There is no per-goal
engine verb and no per-goal request type.

## Not yet one path

- `jk sync` runs the SYNC phase's plan through its own `sync-request`, without the graph or the
  workspace preflight. Its `parse-lock` writes a missing or stale lock (the IDE's first import
  relies on that); a build's reads the lock its preflight freshened.
