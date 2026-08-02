# JumpKick vs Mill — adversarial gap analysis

**Audience:** JumpKick maintainers and agents.  
**Purpose:** Where [Mill](https://mill-build.org) is stronger today, what that implies for product direction, and which Mill ideas are worth stealing vs deliberately not matching.  
**Mill version reviewed:** 1.1.7 docs + main-branch source at `../mill` (com-lihaoyi/mill).  
**JumpKick status:** pre-1.0 alpha (`0.10.1`).  
**As of:** 2026-07-20 — reconciled after tickets 1023–1055 / 1035–1048 dogfood. Live board: kanartist `JK-NNNN`.

> **Shipped since first draft (do not re-litigate as gaps):** `jk watch` / `dev` (1025), chrome
> timeline (1023), microbench (1024), module DAG `jk explain --graph dot` (1035), `jk tasks` /
> `show` / `inspect` (1047), BSP server + compile/test (1028/1041/1048), VS Code + IntelliJ
> wire-only plugins (1017/1054), selective prepare/run + content-hash (1040/1045), project
> build-logic + SPI anchors (1037/1039/1044). **Still open / gated:** warm pool (1049), Zinc
> (deferred 1046), cosign (1019), dependency-confusion namespace pins (1064), release pipeline
> (1066).

This is **not** a marketing comparison. JumpKick and Mill occupy overlapping “better JVM build tool” space but make different bets. Mill is production-mature with a broad language/feature surface. JumpKick is early, lockfile-first, and **convention-over-configuration by default**—with a Mill-style programmable escape hatch as an intentional product goal (scripts live *outside* TOML, never *inside* it). Treat Mill as a high bar, not as an enemy to copy blindly.

---

## Executive summary

| Dimension | Who is ahead | One-line take |
|---|---|---|
| **Maturity / dogfood** | **Mill** | Ships 1.x, self-hosts, proven on Netty/Mockito-scale ports |
| **Programmable build graph** | **Mill** (today) | Tasks = methods; free cache/parallel/IDE — **steal this model as a jk escape hatch** |
| **Fine-grained compile incrementality** | **Mill** (edge) | Zinc + persistent tasks; jk has ABI incremental but less battle-tested |
| **Task DAG UX** | **Closer** | Mill still deeper path algebra; jk has `tasks`/`show`/`inspect`, DOT, timeline, watch |
| **Selective CI** | **Closer** | jk has `--affected-since`, `selective prepare/run` + content-hash; Mill still richer inputs |
| **IDE / BSP** | **Closer** | jk BSP + VS Code/IntelliJ wire-only plugins; Mill still broader IDE polish |
| **Language surface** | **Mill** | Java + Kotlin + Scala (+ more); jk is Java/Kotlin/Groovy-first |
| **Extensibility model** | **Mill** (today) | OO traits; jk has out-of-process plugins, not yet a Mill-like task escape hatch |
| **Lockfile / resolve diagnostics** | **JumpKick** | Canonical `jk-lock.toml`, PubGrub prose; Mill is Coursier-resolve-on-demand |
| **Supply chain defaults** | **JumpKick** (intent) | audit / deny / sigstore / SLSA / dual SBOM are first-class product claims |
| **Client/engine memory model** | **JumpKick** (intent) | Capped resident engine + forked workers; Mill is a warm daemon |

**Bottom line for JumpKick:** Mill wins on *build-system ergonomics for complex monorepos* (graph UX, programmable tasks, selective CI, IDE, proven performance story). JumpKick’s differentiators (Cargo/uv-style lockfile law, data-only `jk.toml`, PubGrub diagnostics, hard memory caps) remain real—but they do not yet compensate for Mill’s depth on the daily edit–compile–test–CI loop. The right response is not “stay pure-declarative forever”; it is **convention-first TOML + a Mill-quality programmable escape hatch that never pollutes the TOML file**.

---

## Positioning (do not confuse the products)

| | Mill | JumpKick |
|---|---|---|
| Config default | Declarative YAML *or* programmable Scala `build.mill` | Declarative `jk.toml` (data) by convention |
| Mental model | Object hierarchy of modules; tasks are methods | Workspace + verbs (`build`/`test`/`lock`); plugins own steps |
| Resolve | Coursier; no committed lockfile as law | PubGrub + **`jk-lock.toml` is law**; builds do not re-resolve |
| Extension | Override `Task`s in-process; publish Mill plugins | Out-of-process workers today; **Mill-like escape hatch planned** |
| Process model | Launcher + long-lived Mill daemon | Native CLI + memory-capped engine (JSONL wire) |
| Maturity | Stable 1.x, commercial support, large examples | Alpha; self-host partial |

**Hard line:** no scripting language *inside* `jk.toml`. TOML stays data so `jk add` / `jk remove`, convention-over-configuration, and human review remain reliable.

**Not an anti-goal:** a Mill-style programmable escape hatch for custom tasks, overrides, and monorepo traits—implemented as **separate source** (e.g. Java/Kotlin build modules or task scripts that participate in the engine graph), never as embedded TOML code. Default path stays declarative; power users get the hatch without turning the manifest into Gradle.

---

## Gaps where Mill is stronger

### 1. Production maturity and performance narrative

Mill publishes head-to-head numbers (Netty ~500kLOC, Mockito ~100kLOC): clean/incremental/no-op compile workflows claimed **3–7×** faster than Maven/Gradle, with sub-second no-op modules after warm-up. The story is reproducible example zips, chrome profiles, and “we ported real codebases.”

JumpKick has a solid CAS + action-cache architecture and incremental Java (ABI / dependency graph in-engine), but:

- no public third-party monorepo benchmark suite at Mill’s scale  
- self-host is phase 2 only (workspace modules + thin workers; full dist still Gradle-heavy)  
- alpha status undercuts “trust the tool” for adopters

**Improvement to capture**

- Publish a **reproducible micro-benchmark suite** (clean / incremental / no-op / parallel) against a fixed open codebase.  
- Treat “dogfood full self-host of jk” as a product milestone, not just a bootstrap ticket.  
- Ship **chrome-tracing / timeline** for every build (Mill: `out/mill-chrome-profile.json`). JumpKick’s `jk explain` forecasts *what* will run; Mill also shows *how long / in parallel* after the fact.

---

### 2. Fine-grained incremental compilation (Zinc + persistent tasks)

Mill uses **Zinc** (Java/Scala) and Kotlin’s Build Tools API inside **persistent tasks** that keep analysis files across runs. Invalidation is task-input + method-level code-change awareness for the build definition itself.

JumpKick has real incremental Java (`JavaIncrementalCompile`, class ABI, AP provenance via in-process javac)—not a full clean every time. Gaps relative to Mill:

- Zinc’s file dependency graph is industry-proven; jk’s incremental path is younger and less documented as a user-facing guarantee.  
- Mill’s **persistent task** model (keep `Task.dest`, refine outputs) is a general pattern beyond “javac only.”  
- Kotlin incremental in Mill is wired to the official Build Tools API; confirm jk K2 path matches that quality.

**Improvement to capture**

- Document and test **incremental contracts** the way Mill does (signature change → N files; body change → 1 file).  
- Consider Zinc (or a Zinc-compatible analysis) if ABI-only analysis plateaus.  
- Expose “why this file recompiled” next to `jk explain` (Mill: invalidation tree + profile).

---

### 3. Task graph as a product surface

Mill’s CLI is a **queryable task graph**:

| Capability | Mill | JumpKick today |
|---|---|---|
| List tasks | `mill resolve __` | Command surface (`jk --help`); not a full DAG walk |
| Inspect task | `mill inspect foo.compile` | Partial via explain |
| Show outputs | `mill show foo.assembly` | Implicit paths / packaging commands |
| Dry-run / selective resolve | `selective.resolve`, planning | `jk explain`; `--affected-since` |
| Watch | `mill -w compile` | No first-class watch loop |
| Visualize DAG | `mill visualize __.compile` → SVG | None |
| Query syntax | `_` / `__` / `{a,b}` / `+` | Workspace modules; no Mill-style selectors |

Mill’s design principle is explicit: the **call graph of tasks is the product**. JumpKick’s verb model is simpler for Cargo-like workflows but thinner for monorepo power users.

**Improvement to capture**

- First-class **`jk watch`** (or `--watch`) for compile/test.  
- Expand `jk explain` toward Mill’s **invalidation tree** (root cause → downstream steps).  
- Module/task selectors for multi-module workspaces (`jk test libs/*`, `jk build {api,worker}`).  
- Optional DAG export (DOT/SVG) for large workspaces—even if only for debugging.

---

### 4. Selective execution for CI (prepare / run)

Mill’s selective testing is a full workflow:

```text
mill selective.prepare
# change code / switch branch
mill selective.run __.test
mill selective.resolveTree __.test   # debug
```

It snapshots inputs, runs only downstream tasks of what changed, and documents determinism pitfalls (VCS version tasks, path identity, FS permissions).

JumpKick has:

```bash
jk build --affected-since=origin/main
```

That covers **git-diff → module set** for builds. Gaps:

- No prepare/run split for CI agents that share artifacts  
- Weaker story for “select tests without evaluating the full graph”  
- No `selectiveInputs` override for tasks that over-depend  
- Not generalized to arbitrary verbs as Mill’s selective layer is

**Improvement to capture**

- Generalize `--affected-since` into a **selective plan** reusable for `test`, `publish`, custom pipelines.  
- Add dry-run (`jk explain --affected-since=…` / `jk selective resolve`) with a machine-readable tree.  
- Document CI determinism rules (same as Mill’s reproducibility section).

---

### 5. Default parallelism + observability

Mill parallelizes tasks by default (`-j` = cores), sandboxes filesystem writes to `Task.dest`, and emits chrome traces every run. Test parallelism reuses JVMs; optional test grouping isolates classes.

JumpKick:

- Engine memory plan can **reduce** parallelism under heap caps (correct for RAM, but not a “max throughput” default story).  
- Test workers support parallelism (`-w` / parallel tests) with worker prefixes.  
- Missing: always-on timeline profiles, graph visualization, documented “wide monorepo” parallel defaults.

**Improvement to capture**

- Default to aggressive parallel module builds when memory budget allows; publish the decision in `jk explain`.  
- Always write a **trace file** (under platform state, or project `out/`) loadable in `chrome://tracing` or Perfetto.  
- Sandbox guarantees for plugin workers comparable to Mill’s `Task.dest` purity story (document + enforce).

---

### 5b. Compile-worker process model: Mill warm JVMs vs JumpKick `.aot` forks

Mill’s compile speed story leans on **long-lived / reused JVM workers** (Zinc worker kept warm in the daemon process model). That avoids cold classloading and JIT every compile.

JumpKick deliberately **forks** java-compiler / kotlin-compiler workers (isolation + memory plan). Cold-start tax is mitigated with **JEP 514 AOT caches** (`.aot` files), not a permanent warm pool:

| Piece | JumpKick today (`PluginAot`) |
|---|---|
| bare `javac` | Short-lived `javac` process; **no** worker AOT (not trained/mapped) |
| java-compiler worker | Short-lived `java … PluginMain`; maps `java-compiler-*.aot` via `-XX:AOTCache=…` when present |
| kotlinc worker | Short-lived plugin JVM; maps `kotlinc-<jdk+gc+classpath>.aot` via `-XX:AOTCache=…` |
| Miss path | Background train on first miss (`AOTCacheOutput`); next compile maps the cache — never blocks the user compile |
| Kill switch | `-Djk.worker.aot=off` / `JK_WORKER_AOT=off` |
| Engine itself | Separate engine `.aot` training path (same family of idea) |

AOT-mapped forks are almost certainly **slower than a fully warm resident compiler**, but they:

- keep **RSS predictable** under the capped engine + forked workers model  
- avoid pinning large compiler heaps between builds  
- still cut a large slice of cold-start cost once caches exist  

**Warm compiler pool is a likely-good Mill steal — but not free.** Holding a pool of warm javac/kotlinc JVMs trades **extra resident memory** for lower latency. That may be worth it on fat monorepos or tight edit–compile loops; it may lose on laptops already near the engine heap ceiling, CI with many concurrent jobs, or workloads already AOT-warm.

#### Gate: measure and verify before adopting a warm pool

Do **not** implement a resident compiler pool until benchmarks show the RSS cost is justified.

| Measure | Baseline (current) | Challenger (warm pool) |
|---|---|---|
| Process model | Fork + `.aot` map when ready | Reused in-process / pooled worker JVMs |
| Wall time | clean module, 1-file edit, no-op, multi-module parallel | same scenarios |
| RSS / peak memory | engine + peak worker set | engine + pool size × worker heap |
| Cold first compile | no `.aot` yet | pool cold start vs train |
| Steady state | `.aot` present, second+ compiles | pool already hot |
| Kill / disable | `JK_WORKER_AOT=off` as control arm | pool size 0 / 1 / N |

**Go criteria (illustrative — pick concrete thresholds when writing the ticket):**

- Steady-state incremental or no-op latency improved enough to matter to developers (e.g. clear multi‑tens-of-ms or better on representative modules), **and**  
- Peak RSS increase fits the product memory story (does not blow concurrent-build caps / default heap plan), **and**  
- Benefit still holds with AOT **on** (challenger must beat the already-accelerated baseline, not only “cold fork without AOT”).

**No-go / defer:** gains only vs unaccelerated cold forks, or latency win only at RSS that forces lower parallelism than AOT+fork.

**Steal note:** prioritize **profiles + microbench harness** first so this comparison is cheap to re-run. Warm pools remain on the “likely good” list; **measure-and-verify is a hard prerequisite**, not optional polish.

---

### 6. Programmable customization without Gradle pain (**steal this**)

Mill’s decisive advantage over Gradle is **object-oriented builds**:

- `def lineCount = Task { … }` is cached, parallelized, inspectable for free  
- `override def resources` splices into the graph with normal `super` semantics  
- IDEs navigate defs like application code (BSP + Scala plugin)

That model is a **Mill steal**, not a reject. JumpKick’s constraint is narrower: **never put that code in `jk.toml`**. Today extension is out-of-process plugins and first-party TOML tables—enough for convention, weak for:

- codegen into resources  
- custom packaging pipelines  
- ad-hoc repo tooling as named tasks  
- monorepo-wide shared traits (`MyModule extends JavaModule`)

**Target shape (convention-over-configuration + escape hatch)**

| Layer | Role |
|---|---|
| `jk.toml` | Data only: project identity, deps, features, plugin tables, pins |
| Convention | First-party verbs + defaults cover the common 90% |
| Escape hatch | Programmable tasks/modules (Java or Kotlin preferred) that override or add graph nodes like Mill `Task`s—**separate files**, graph-native (cache keys, parallel, `jk explain` / inspect) |
| Plugins | Still out-of-process for heavy/isolated work; hatch can call into them |

**Improvement to capture (without becoming Gradle)**

- Design a **programmable escape hatch** inspired by Mill: tasks as first-class graph nodes (inputs/outputs, CAS, inspectable), override/splice into compile–resource–package pipelines, share traits across workspace modules.  
- Prefer **JVM-language** source (Java/Kotlin/Groovy — same stack as application code; IDE-friendly) over inventing a TOML DSL or embedding scripts in the manifest.  
- `jk.toml` may *point at* hatch modules (e.g. path / coordinate / feature flag) but must not *contain* executable code.  
- Richer first-party hooks remain valuable so most projects never open the hatch.  
- Keep anti-goal: **no scripting language inside `jk.toml`**—not “no programmability.”

---

### 7. IDE and editor integration

Mill:

- BSP install (`--bsp-install`), IntelliJ import, VSCode/Metals, Eclipse file generation  
- Navigable `build.mill`  
- Separate BSP output dir to avoid clashing with `out/`

JumpKick:

- `IdeEngineClient` facade (ticket-1014 done): sync/build events over wire  
- `jk export idea | vscode` / offline project files  
- Marketplace plugins still backlog (ticket-1017)  
- No BSP server

**Improvement to capture**

- Ship a **BSP server** (or thin IDE host) on top of the existing wire verbs—prefer this over shelling out to `jk`.  
- Prioritize ticket-1017 once BSP or the facade is packagable.  
- Match Mill’s “import just works” for a empty greenfield project.

---

### 8. Language and ecosystem breadth

Mill ships first-party **Java, Kotlin, Scala**, Android, plus multi-language experiments (Python, JS, …), Scala.js/Native, Spark examples, Spring Boot / Micronaut / Ktor samples, ErrorProne / Checkstyle / PMD / Detekt / ktlint / Scalafmt / MiMa, jlink / jpackage, assembly merge rules, Docker contrib, etc.

JumpKick is intentionally JVM-focused with first-party plugins (Spring Boot, Quarkus, Grails,
Android, protobuf, shrink, image, …). Gaps that matter for “modern JVM shop” adoption:

| Area | Mill | JumpKick |
|---|---|---|
| Scala | First-class | Not a goal today |
| Linting suite | Many builtins | Formatter plugin; thinner static analysis matrix |
| Fat/assembly jars | `assembly` + rules + Spring repackage | Packaging via plugins; less “one task” polish |
| Single-file scripts | Header `//|` scripts with JVM pin | `jkx` / JBang-compatible tools |
| Bootstrap `./mill` | Zero-setup, auto-download JVM | `jk wrapper`; still less famous than mill/gradle wrappers |

**Improvement to capture**

- Treat **assembly / executable jar** and **lint matrix** as product depth, not plugin afterthoughts.  
- Keep Scala out of scope unless strategy changes—but document that clearly so Mill stays the Scala answer.  
- Invest in **wrapper + zero-setup** parity with Mill’s “no global install” story.

---

### 9. Dependency management UX (partial Mill wins)

Mill uses **Coursier**: BOMs (`bomMvnDeps`), `depManagement`, forceVersion, exclusions, `showMvnDepsTree --whatDependsOn`, dependency update search. Tests are separate modules (no Maven `test` scope)—clear model.

JumpKick is stronger on **reproducibility** (`jk-lock.toml`, scopes main/test/processor, PubGrub diagnostics, `jk why` / `jk tree`). Gaps vs Mill:

- Mill’s update search (`Dependency/showUpdates`) is polished  
- BOM publishing modules (`BomModule`)  
- Tree “whatDependsOn” is very usable  
- Meta-build dependency updates

**Improvement to capture**

- `jk outdated` / update report at Mill quality (even though lockfile remains law until `jk update`).  
- Richer `jk tree --what-depends-on` (if not already feature-complete).  
- Publishable BOM modules if library authors are a target audience.

---

### 10. Migration tooling and third-party proof

Mill has a written migration playbook, `mill init` importer, and **checked-in third-party ports** (Netty, Mockito, Gatling, …) used as living demos.

JumpKick has `jk import` / `jk mvn` / `jk gradle` coexistence—excellent adoption path—but fewer “we built X with jk” public artifacts. Custom build logic today maps mainly to plugins; a Mill-like hatch would ease “translate this Groovy/Kotlin build logic” migrations without forcing every quirk into a published plugin.

**Improvement to capture**

- One **public showcase monorepo** continuously built with jk on CI.  
- Fidelity reports that match Mill’s honesty about long-tail quirks.  
- Keep `jk gradle`/`jk mvn` as the coexistence edge Mill lacks (this is a JumpKick *strength*).  
- Map common migration customizations onto the **programmable escape hatch** (codegen, resource munging, packaging tweaks) once it exists.

---

### 11. Watch mode, REPL, and local DX microfeatures

Mill:

- `-w` / `--watch`  
- `jshell` attached to modules  
- Tab completion install (`mill.tabcomplete/install`)  
- `runBackground`  
- Sandboxed tests with `MILL_TEST_RESOURCE_DIR`

JumpKick has strong shell/JDK activation and a modern CLI, but watch/REPL/background are thinner or absent.

**Improvement to capture**

- `jk watch test` / `jk watch compile`  
- Module-scoped jshell or kotlin REPL  
- Document sandbox env vars for tests the way Mill does

---

## Where JumpKick is already ahead (do not regress)

Adversarial review is incomplete without knowing what **not** to “Mill-ify”:

1. **Lockfile is law** — Mill re-resolves; JumpKick’s Cargo/uv stance is a real differentiator for CI and offline.  
2. **PubGrub conflict prose** — Mill/Coursier trees are usable; JumpKick aims for English “why” + suggestions.  
3. **Data-only `jk.toml`** — No executable code in the manifest. Protects `jk add`/`remove`, convention-over-configuration, and reviewability. Programmability lives *beside* TOML, not *in* it.  
4. **Client / capped engine + AOT-accelerated forks** — Concurrent builds share a memory plan; compile workers are short-lived with JEP 514 `.aot` caches (`PluginAot`). Warm pool **deferred** (ticket-1030 / [docs/perf/warm-pool-bench.md](perf/warm-pool-bench.md)) until a prototype beats AOT-on on wall *and* RSS.  
5. **Supply-chain product surface** — audit (OSV), deny, signing, Sigstore, SLSA, SBOM as core verbs.  
6. **Adoption without rewrite** — `jk mvn` / `jk gradle` run real builds; Mill expects parallel Mill build during migration.  
7. **Content-addressed action cache design** — Local CAS with a clean path to read-only remote (ticket-1012 design).

These are the axes where beating Mill means **doubling down**, not converging.

---

## Priority recommendations (Mill → JumpKick backlog)

Live tickets: **`JK-NNNN`** in [kanartist](https://github.com/jkbuild/kanartist) (`projects/jk/`). Snapshot of themes at migration time (status may have moved — check the board):

| Priority | Ticket | Theme | Kind |
|---|---|---|---|
| **P0** | JK-1023 | Build timeline / chrome profile every run | go-do |
| **P0** | JK-1024 | Microbench harness (clean / incremental / no-op) | go-do |
| **P0** | JK-1025 | Watch mode for compile/test | go-do |
| **P0** | JK-1026 | Programmable escape hatch design (outside TOML) | research |
| **P1** | JK-1029 | Incremental contracts + why-recompiled | go-do |
| **P1** | JK-1027 | Selective test / multi-verb plan | go-do |
| **P1** | JK-1028 | BSP server / IDE host | research → MVP |
| **P1** | JK-1030 | Warm pool vs AOT measure-and-verify | research (no pool until go) |
| **P1** | JK-1037 | Escape hatch MVP | go-do (after 1026) |
| **P1-depth** | JK-1031 · JK-1039 · JK-1040 · JK-1041 | Selectors, multi-task logic, selective prepare/run, BSP import | often done |
| **P2** | JK-1034 | `jk outdated` polish | go-do (command exists) |
| **P2** | JK-1032 | Fat-jar rules + R8 productization | go-do (`jk assemble` exists) |
| **P2** | JK-1038 | Showcase monorepo CI | go-do |
| **P2** | JK-1033 | Lint matrix | research → thin go-do |
| **P2** | JK-1017 | Marketplace IDE plugin (one track) | go-do after BSP |
| **P3** | JK-1035 | Module DAG DOT export | go-do |
| **P3** | JK-1036 | jshell + sandbox docs | go-do |
| **P3** | JK-1022 | CLI test TempDir cleanup | go-do |
| **P3** | JK-1019 | Cosign additive plugin sigs | go-do |

Warm pool **implementation** is not scheduled until JK-1030 go criteria pass.

### Programmable escape hatch — steal brief

Mill proves programmable builds can stay *understandable* if tasks are pure, graph-native, and IDE-navigable—not a second configuration language of global mutables (Gradle). JumpKick should steal that property set:

| Steal from Mill | JumpKick constraint |
|---|---|
| Tasks as pure nodes (inputs → outputs, dedicated dest) | Same; CAS / action-cache keyed like engine steps |
| Override / super to splice into pipelines | Splice into first-party verb graphs without rewriting core |
| Shared traits for monorepo module presets | Workspace-level reuse without copy-paste TOML |
| Free caching, parallelism, inspect/show | Hatch tasks appear in `jk explain`, profiles, selective plans |
| Real language + IDE navigation | Prefer **Java/Kotlin/Groovy** source beside the project, not Scala-required |
| | **`jk.toml` remains pure data**—may reference hatch entrypoints only |

Default UX stays Cargo-like: most projects never open a hatch file. Escape hatch is for the long tail Mill already handles well.

---

## Design tension: Mill’s “tasks are methods” vs JumpKick’s “TOML + pipeline + hatch”

Mill collapses “what is a module / task / cache key / CLI path” into one hierarchy. That is why IDE support and custom tasks feel free.

JumpKick’s intended split:

- **`jk.toml`** — declarative product intent (data only; convention-over-configuration)  
- **engine pipeline** — verbs → steps → CAS  
- **plugins** — isolated workers for heavy/third-party work  
- **programmable escape hatch** (planned) — Mill-like graph surgery in real JVM source when convention is not enough  

That keeps Cargo ergonomics and a reviewable manifest while closing Mill’s “custom graph” gap. Success criteria:

- 90% of projects stay on convention + first-party plugins  
- Hatch authors get Mill-grade cache/parallel/inspect ergonomics  
- Nothing executable ever lands *inside* `jk.toml`

---

## Sources

- Mill docs: [mill-build.org](https://mill-build.org/mill/index.html) (1.1.7) — design principles, caching, process architecture, performance comparisons, selective execution, testing, packaging, IDE install, migration  
- Mill source: `../mill` (fetched main; core eval/exec, javalib, scalalib, kotlinlib, runner)  
- JumpKick: [README.md](../README.md), [docs/architecture.md](architecture.md), [docs/guide.md](guide.md); planning board in [kanartist](https://github.com/jkbuild/kanartist) (`JK-NNNN`)

---

## Maintenance

- Revisit when JumpKick approaches 1.0 or when Mill ships major 1.2+ features.  
- Keep this file **adversarial and actionable**; do not turn it into a public marketing page unless product strategy asks for it.  
- Actionable work is tracked as **`JK-NNNN`** tickets in [kanartist](https://github.com/jkbuild/kanartist) (project `jk`); update that board when priorities shift rather than re-litigating this essay.
