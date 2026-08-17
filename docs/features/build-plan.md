# Build model: Task, Target, BuildPlan

How JumpKick schedules and runs work. Mill-shaped: a **DAG of tasks** with declared
dependencies, not a fixed lifecycle of slots.

## Vocabulary

| Term | Definition |
|------|------------|
| **Task** | One named unit of work. Declares zero or more **requires** edges (upstream tasks that must succeed first). Cached or uncached; may report progress ticks. |
| **Target** | The terminal task the invocation is trying to reach (e.g. `package-jar`, `run-tests`, `write-image`). CLI verbs and engine `kind` map to a target. |
| **BuildPlan** | The executable DAG for one module (or single-module request): the **transitive closure** of tasks required to produce the target, scheduled by readiness. |
| **TaskForecast** | Read-only cache forecast for the same task set (explain / dirty reporting). Not the executor. |
| **InvocationPhase** | Coarse outer **phases** of a whole `jk` request (below). Orthogonal to the task graph. |
| **BuildStage** | Which part of a module build a task belongs to — `resolve`, `generate`, `compile`, `test`, `package`, `native`, `image`, `other`. A closed taxonomy, and **enforced**: see [Stages](#stages). |

### Rename map (implementation)

| Previous name in code | Canonical name |
|----------------------|----------------|
| `BuildPlan` (executor) | `BuildPlan` |
| `Step` | `Task` |
| `BuildPlanListener` / step wire events | `BuildPlanListener` / `task-*` events |
| Plugin `Phase` + after/before windows | Task `requires` + contribution wiring |
| `PhaseGraph` | Removed (task graph is law) |
| “Goal” (terminal capability / UI) | **Target** |
| Forecast type `BuildPlan` / `TaskForecaster` | `TaskForecast` / `TaskForecaster` |
| Plugin/lifecycle `Phase` enum + `PhaseGraph` | Removed; replaced by the closed `BuildStage` taxonomy (below) |

## Stages

Every task carries a `BuildStage`. It is **not** a free-form label and **not** UI-only.

| Stage | Runs | Examples |
|-------|------|----------|
| `resolve` | module setup | `parse-build`, `resolve-deps`, `ensure-jdk` |
| `generate` | codegen before compile | `build-logic-before-compile`, source-generating plugin tasks |
| `compile` | main compile + resources | `compile-java`, `copy-resources`, `write-stamp` |
| `test` | test compile + run | `compile-test`, `run-tests` |
| `package` | jar / assembly | `package-jar`, `embed-sha` |
| `native` / `image` | native-image, OCI | `native-image`, `write-image` |
| `other` | unknown or plugin-private | UI parks these under "Other" |

**A task may not require a task in a later stage.** `BuildPlan.build()` rejects such an edge, so
this aborts the build at plan time, before any task runs:

```
step 'x' (stage generate) requires 'y' (stage compile) — cannot depend on a later BuildStage
```

Ordering is still the DAG (`requires`); the stage is product taxonomy that the DAG must not
contradict. `other` is exempt from the check on both sides.

### Plugin tasks

A plugin task's stage is **inferred from the window the engine schedules it in**, so inference can
never produce an edge the validator rejects:

| Window | Stage |
|--------|-------|
| runs before the compilers (source-generating, or no `In.classes()` and no package/test contribution) | `generate` |
| contributes only to the test runtime classpath | `test` |
| everything else | `compile` |

`TaskSpec.stage("…")` **narrows** that for the UI fold — `android-dex` declaring `package`, say. It
is validated, not trusted:

- it must be at or after the window's stage;
- it must be at or before `test` when `run-tests` requires the task (any test-classpath contributor);
- an unrecognized spelling is an error, not a silent `other`.

Violations name the plugin task and say which window it is in.

## Targets

A **target** is a task name that closes the **module** plan. Selecting a target includes every
task reachable as an upstream dependency. Tasks not on any path to the target are omitted
(e.g. skip tests when the target is package and tests are not required).

### One orchestrator (invariant)

Build-family commands share **one** workspace lifecycle — `WorkspaceExecute.buildWorkspace`
(`jk build`’s path). They are **not** allowed to grow a second cascade (dirty set, ETA seed,
prepare, schedule, progress). The only intentional differences:

| CLI | `WorkspaceTarget` | Module cone |
|-----|-------------------|-------------|
| `jk build` | `PACKAGE` | Whole graph (or `-m` / dirty hint) |
| `jk test` | `TEST` (`testOnly`) | Same as build |
| `jk native` | `NATIVE` | Native-eligible modules + dependency closure |
| `jk image` | `IMAGE` | The one image module + closure |
| `jk compile` | `COMPILE` | Compile-only plans |
| `jk run` (build half) | `PACKAGE` | Then client exec-handoff |

Dependency closure uses **all scopes** when tests run (dirty test harnesses rebuild) and
**production scopes** when `--skip-tests`. New build-family verbs add a target + filter —
they do not copy `NativeVerb`’s old hand-rolled loop.

| CLI / engine kind | Typical module terminal |
|-------------------|-------------------------|
| `jk build` | `package-jar` (or packager-provided artifact / declared tails) |
| `jk test` | `run-tests` |
| `jk native` | `native-image` (prereqs stay at package) |
| `jk image` | `write-image` (prereqs stay at package) |
| `jk publish` | publish terminal task |
| `jk run` (build half) | package (or classes) before client exec |

## Tasks and the BuildPlan

```
sources / parse-build / resolve-deps / ensure-jdk
        ↘
    compile-* → copy-resources → assemble-classes
        ↘              ↘
    plugin tasks …     package-jar → (image | publish | …)
        ↘
    compile-test → run-tests
```

- Edges are **requires** (data/order dependencies), never ordinal phase slots.
- Independent tasks run in parallel (subject to `-j` / heap plan).
- The engine builds a `BuildPlan` from core tasks + plugin contributions, selects a **Target**,
  **prunes** to the target’s upstream `requires` closure, validates the DAG (unknown names, cycles),
  estimates weights, then executes.

### Plugin contributions

Plugins register **tasks** (not phase windows):

```java
ctx.named("protoc")
    .requires() // optional explicit deps; engine supplies resolve/jdk when contributing sources
    .inputs(In.projectFiles("proto"), In.config())
    .outputs("gen")
    .contributesSources("gen")
    .run(body);

ctx.named("spring-aot")
    .inputs(In.classes(), In.runtimeClasspath(), In.config())
    .outputs("classes", "resources", "sources")
    .contributesClasses("classes")
    .contributesResources("resources")
    .run(body);
```

**Engine wiring from contributions** (automatic reverse edges):

| Contribution | Engine adds |
|--------------|-------------|
| `contributesSources` | compile tasks **require** this plugin task; task requires parse/resolve/jdk |
| `contributesClasses` / `contributesResources` / `transformsClasses` | `package-jar` (and consumers of classes) **require** this task; task requires assemble/classes readiness |
| `contributesTestClasspath` | `run-tests` **requires** this task |
| `In.stepOutput("other")` | this task **requires** `plugin-other` |
| Packager `In.stepOutput("x")` | packager path **requires** `plugin-x` |

Plugins may also list explicit `.requires("task-name", …)` for extra edges. There is no
`after(Phase)` / `before(Phase)` API.

Terminal plugins (image, publish, run) **own a target**: they consume the finished module rather
than inserting mid-graph steps.

## InvocationPhase (outer orchestration)

Separate from the task graph, and separate from `BuildStage`. Phases of a **whole engine request**:

| Phase | User-visible (CLI / Web) | Role |
|-------|--------------------------|------|
| `initialize` | no | Session bootstrap, admission, journal open |
| `resolve` | **yes** | Lock freshen / resolve when needed |
| `plan` | **yes** | Map kind → target, assemble BuildPlan(s), forecast, ETA |
| `toolchain` | no | Ensure JDK / tools |
| `build` | **yes** | Execute each module’s BuildPlan until targets complete |
| `finalize` | no | Always-run: journal, timeline, mirror, cleanup |

Progress hierarchy for UIs:

```
invocation
  ├─ resolve   (optional detail)
  ├─ plan      (modules / task forecast)
  └─ build
       ├─ module A
       │    ├─ task compile-java
       │    ├─ task package-jar
       │    └─ …
       └─ module B …
```

Compile / test / package are **task names** (or groups of tasks), not invocation phases.

## Wire events (schema 1, breaking rename)

| Event | Meaning |
|-------|---------|
| `invocation-phase` | Enter/leave a user-visible or internal InvocationPhase (`phase`, `status`) |
| `plan-module` / `plan-task` / `plan-done` | Plan burst (forecast) |
| `task-start` / `task-finish` | Task lifecycle (`task`, optional module `dir`) |
| `buildplan-start` / `buildplan-finish` | One module BuildPlan start/end |
| `progress` / `label` / `output` / … | Unchanged roles; field `step` → `task` where present |
| `explain-task` / `history-task` | Explain/history bursts (were `explain-step` / `history-step`) |
| Task events' stage | Field `stage` (was `group`, before that `phase`) carries the task's `BuildStage` wire name; `phase` means only InvocationPhase and the workspace-progress tracker |
| Status | `activeBuildPlans` (was `activePipelines`) |

Protocol version stays **1** (pre-1.0 freeze); names replace in place — no dual-read.

## Parallelism

- **Module graph**: workspace modules scheduled by module dependency edges.
- **Task graph**: within a module, ready tasks (all requires terminal success) run up to the job
  concurrency / heap plan.
- Synchronization exists only where a `requires` edge says so.

## Related

- [project-build-logic.md](project-build-logic.md) — hatch tasks outside TOML  
- [plugins.md](../plugins.md) — authoring plugins  
- [architecture.md](../architecture.md) — process model  
- [machine-output.md](../machine-output.md) — CLI/SSE event tables  
- [mill-comparison.md](../mill-comparison.md) — maintainer gap analysis  
