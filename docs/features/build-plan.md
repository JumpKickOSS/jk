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
| **InvocationPhase** | Coarse outer stages of a whole `jk` request (below). Orthogonal to the task graph. |

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

## Targets

A **target** is a task name that closes the plan. Examples:

| CLI / engine kind | Typical target |
|-------------------|----------------|
| `jk build` | `package-jar` (or packager-provided artifact task) |
| `jk test` | `run-tests` |
| `jk image` | `write-image` |
| `jk publish` | publish terminal task |
| `jk run` (build half) | package (or classes) before client exec |

Selecting a target includes every task reachable as an upstream dependency. Tasks not on any
path to the target are omitted (e.g. skip tests when the target is package and tests are not
required).

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
- The engine builds a `BuildPlan` from core tasks + plugin contributions, validates the DAG
  (unknown names, cycles), estimates weights, then executes.

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

Separate from the task graph. Stages of a **whole engine request**:

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
| Status | `activeBuildPlans` (was `activeBuildPlans`) |

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
