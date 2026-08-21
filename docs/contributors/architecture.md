# Architecture

How jk is structured today. For day-to-day usage see [user documentation](../user/README.md).

## Design tenets

1. **Declarative core** — `jk.toml` is data; logic lives in plugins and task scripts, not the manifest.
2. **Fast client, hosted build** — native CLI for UX; resident engine for resolution, BuildPlanner, and memory coordination.
3. **Reproducibility by default** — lockfile, content-addressed cache, deterministic packaging.
4. **Diagnostics as product** — PubGrub prose, `jk why` / `jk explain`, machine-readable output.
5. **Adoption first** — `jk mvn` / `jk gradle`, import/export, Maven Central semantics.
6. **Resist plugin sprawl** — first-party batteries; no public marketplace before a frozen SPI.
7. **One build orchestrator** — `jk build` / `test` / `native` / `image` (workspace) share
   `WorkspaceExecute.buildWorkspace`. Commands differ by **target** and **module cone**, not by
   a second dirty/ETA/schedule loop. One `WorkspaceExecute` orchestrator; add a `WorkspaceTarget` + module filter.

## Process model

```
┌─────────────────────┐         JSONL (UDS / TCP)        ┌──────────────────────────┐
│  jk client          │ ──────────────────────────────── │  jk engine               │
│  (GraalVM native)   │                                  │  (HotSpot JVM, capped)   │
│  TUI, shell, jdk    │                                  │  resolve · build · cache │
│  terminal execs     │                                  │  fork plugin workers     │
└─────────────────────┘                                  └──────────────────────────┘
```

- **Client** — presentation, shell hooks, JDK install prompts, anything that owns your terminal
  (`jk run` exec, `jk mvn`/`gradle` interactive). Sub-50 ms cold start; no engine code in the
  native image. The CLI does not interpret plugin schemas — `jk-plugin.toml` and Giter8
  templates are engine-only, never `:core` / the native client.
- **Engine** — dependency resolution, task graph / BuildPlan execution, CAS, toolchains,
  compiler/test workers, hosted verbs (`build`, `test`, `lock`, `publish`, …). Default heap ceiling
  **256 MiB** (or **512 MiB** when `CI=1`/`true` and unset) via
  `~/.config/jk/config.toml` → `[engine] max-heap-mb`, or `JK_ENGINE_MAX_HEAP_MB`.
  **Three budgets:** (1) engine heap = thin coordinator (JK-1075 measured ~36 MiB peak on a
  200-module build); (2) worker JVM heaps from free RAM via `HeapPlan`; (3) concurrency via
  **`-j` / `--jobs` / `JK_JOBS` / `[engine] jobs`** (Mill-shaped: `0`=effective cores via
  cgroup quota when present else `availableProcessors()`, `1`=serial, `N`=cap — JK-1084),
  still RAM-clamped by `PluginSlots`. Do not grow the non-CI engine default toward multi-GiB
  “just in case”; set `max-heap-mb` or run under CI for a higher default.
- **Load-bearing** — if the engine cannot start, the command fails clearly (no silent
  in-process fallback for hosted work). That is how concurrent builds avoid RAM overcommit.
- **Lifecycle** — lazy start on first need; stays resident until `jk engine stop` or
  version-skew replacement. **No idle timeout**: the dashboard is written against that guarantee, and
  a browser tab cannot respawn an engine the way the CLI can — see [http.md](http.md). A displaced
  predecessor yields UDS / wire / HTTP immediately, drains in-flight jobs, and reports
  `drain-status` to the successor. The one exception is an *orphaned* engine (no endpoint pointer
  names it, so nothing can reach it), which exits once it has no in-flight jobs and no attached
  event stream.
- **Identity** — one engine per (state directory, artifact store) pair. The store is part of the
  identity hash because two invocations can share a state dir while disagreeing about where downloads
  belong; without it, `JK_STORE_DIR` silently did nothing. A machine can therefore hold several
  engines: `jk engine status` lists every resident engine this user owns (including other
  `JK_HOME`s and draining/ghost pids). `jk engine stop --all` stops **this home only** so a
  nested test suite cannot kill the host engine. `--pid` stops one explicitly.
- **Versioning** — one live engine at `~/.local/share/jk/lib/jk-engine.jar` (or
  `$JK_HOME/lib/jk-engine.jar`), paired with the PATH `jk`. An upgrade parks the previous jar as
  `jk-engine.jar.old` and the previous client as `jk.old` (`jk.exe.old` on Windows) until the
  displaced engine drains; GC deletes the parked files. Handshake detects skew and takes over.
  **Newer always wins**: the lock's
  `jk-min` is a *floor*, never a pin — a jk older than the floor refuses artifact jobs with an
  upgrade error (`jk self update`) on every surface, and nothing ever fetches or runs an older
  engine to satisfy a lock. The lock pins inputs (artifacts, checksums, BOMs), not the operator;
  `generated-by` is provenance only. Same lock ⇒ same resolved graph across jk versions until
  `jk update`; tool behavior may still change pre-1.0 (a newer jk may rebuild).
- **Liveness** — a listening socket alone is not proof the engine is healthy (ticket-1043):

| Layer | What proves health | Bound |
|---|---|---|
| **Probe** (`ping` / `hello` / `status`) | One request/reply | ~2s client watchdog |
| **Stream** (build / test / sync) | Protocol lines keep flowing | `JK_STREAM_IDLE_MS` (default 60 minutes between lines; `0` disables) |
| **Job heartbeat** (ticket-1051) | Engine emits `heartbeat` while async wire jobs run; detached (HTTP/MCP) jobs have no stream to keep alive, so only the wall-deadline watchdog runs | `JK_ENGINE_HEARTBEAT_MS` (default **30s**; `0` disables) — resets client stream idle |
| **Job wall deadline** (ticket-1051 / JK-1067) | Cancel token + worker shutdown + interrupt runner; connection join bounded | `JK_ENGINE_JOB_DEADLINE_MS` (default **0** = off); join grace `JK_ENGINE_JOB_DEADLINE_GRACE_MS` (default **30s**, last-chance wait capped ~1s) |
| **User cancel / EOF** (JK-1096 / JK-1252) | Cancel token + **grace→force** worker kill; join bounded by cancel grace + 500 ms. Public cancel handle is **jid**. Entry points: Ctrl-C, `jk cancel` / `jk cancel <jid>`, `POST /api/cancel` (`jid` or `dir`), MCP `jk_cancel`. | `JK_CANCEL_GRACE_MS` (default **500**; max 5000). **Never hangs.** |
| **Ensure** | Handshake must succeed | Silent peer (connect works, no reply) → hard-kill once + respawn |
| **Stop** | Process death, not only `bye` | Force-stop waits for pid exit (~1.5s) then escalates |

If a stream goes idle, the client fails closed with a clear error (tune with `JK_STREAM_IDLE_MS`;
recover with `jk engine stop --force`). Heartbeats keep long quiet compiles honest against the
idle timer. Huge monorepos leave `JK_ENGINE_JOB_DEADLINE_MS` at `0`; CI can set a wall cap.

**Worker cancel contract (JK-1096):**

1. **All** registered workers for the request get `Process.destroy()` first (tight loop — one
   shared signal phase, not staggered).
2. The engine waits **one** shared wall-clock grace (`JK_CANCEL_GRACE_MS`, default **500 ms** for
   the whole set — **not** 500 ms × N workers).
3. Survivors get `destroyForcibly()`.

Plugins must treat that **shared sub-second** window as all they get. Cancel always finishes; the
connection thread never `await`s unboundedly on a cancelled job.

**Windows:** `Process.destroy()` is not SIGTERM; HotSpot typically terminates immediately (no
portable graceful OS signal). The grace still bounds *our* wait; do not rely on Windows shutdown
hooks after `destroy()`. Prefer cancel-token / stdin EOF where possible; force-kill is the portable
last step. `JK_CANCEL_GRACE_MS` max **5000** is only an env safety clamp for misconfiguration — not
the product default and not a per-worker budget.

```bash
jk engine start | status | stop
```

### Dashboard static assets (`web-root`)

The engine HTTP server serves the dashboard from **disk first**, then classpath `/web`
(in the engine jar). Both tiers are `Cache-Control: no-cache` (store, but revalidate every
load): disk so worktree edits show up on refresh without reinstalling; classpath so a new
engine jar is not hidden by a still-fresh `max-age`. Classpath responses also carry a
version+mtime `ETag` (`"jk-<version>-<stamp>"`) — unchanged jar → `304`.

| Source | Key |
|---|---|
| Env | `JK_HTTP_WEB_ROOT` (absolute path preferred) |
| Config | `~/.config/jk/config.toml` → `[http] web-root` |
| Default | `~/.local/state/jk/web` (under the platform state dir) |

Point at the worktree for UI iteration:

```bash
export JK_HTTP_WEB_ROOT="$PWD/clients/web/src/main/resources/web"
jk engine stop
jk engine start
# edit style.css / index.html / *.webp → hard-refresh the browser
```

Relative `web-root` values resolve against the product home (data root, or `$JK_HOME` when set).
Only files present under the root are overridden; anything missing still falls through to the jar.

### HTTP server knobs

`~/.config/jk/config.toml`; env wins over the file (`env > file > default`):

| Config key | Env | Default | Meaning |
|---|---|---|---|
| `[http] max-concurrent-requests` | `JK_HTTP_MAX_CONCURRENT_REQUESTS` | `16` (`0` = core count) | RPC admission cap |
| `[http] max-event-streams` | `JK_HTTP_MAX_EVENT_STREAMS` | `16` (min `1`) | Web-UI SSE budget (`GET /api/events`) |
| `[mcp] enabled` | `JK_MCP_ENABLED` | `true` | MCP surface toggle — `false` 404s `/mcp`; the HTTP server and dashboard stay up |
| `[mcp] max-event-streams` | `JK_MCP_MAX_EVENT_STREAMS` | `16` (min `1`) | MCP SSE budget (`GET /mcp` event streams) |

`[http] enabled = false` still disables the whole server, MCP included.

Transport: Unix domain socket on macOS/Linux; loopback TCP + shared-secret token on Windows.
Protocol is internal (same-version client/server), JSONL (one JSON object per line).

### Wire freeze vocabulary (pre-1.0)

Same-version client/engine only — not a multi-version public API. Conventions frozen for 1.0:

| Rule | Shape |
|---|---|
| Envelope | One JSON object per line; discriminator field `"type"` (same as CLI JSONL / SSE / MCP) |
| Auth (TCP only) | First line `{"type":"auth","token":…}` — never a raw token line |
| Handshake | `hello` / `hello-ack` carry `version`, `proto` (`EngineProtocol.PROTOCOL`), `purpose` (`connect`\|`probe`); `hello-ack` uses `startedAt` (millis) |
| Handover | Displaced predecessor yields UDS/HTTP, then `drain-status` / `drain-done` to the successor |
| Errors | `{"type":"error","code",…,"message",…}` (`auth`, `protocol`, `version-skew`, …) |
| Project path | Field name is always `dir` |
| BuildPlan finish | `{"type":"buildplan-finish","kind":…,"dir":…,"success":…}` (+ kind-specific tails) |
| Session extras | Variant / client env / JVM tuning via typed `withSession` builders — no JSON string surgery |
| Line limits | Bounded line reader + idle timeout; unknown/`type`-less lines → `error`, not silent drop |

Builders and round-trip tests live in `shared/wire` / `EngineProtocolTest`.

### Schema freeze until 1.0

**Until JumpKick 1.0 ships, do not rev schema / protocol version numbers.** Stay on **version 1**
(or the field’s existing constant) for every external-ish format:

| Surface | Field / constant | Pre-1.0 policy |
|---------|------------------|----------------|
| `jk.toml` | grammar / tables | Additive only; no version bump |
| `jk-lock.toml` | `version` / `Lockfile.CURRENT_VERSION` | Stay on **1**; additive rows/fields only |
| Client↔engine wire | `EngineProtocol.PROTOCOL` | Stay on **1** |
| CLI JSONL / run logs | `JsonlShape.SCHEMA` / `"schema"` | Stay on **1** |
| Session transcripts | `details.jsonl` `"schema"` | Stay on **1** |
| Run report | `jk-results.md` | Markdown; no version field |
| REST `/api/*` | response shapes | Additive fields only |
| SSE event `data` | `"schema"` | Stay on **1** |
| MCP | `protocolVersion` / tool payloads | Stay on advertised **1**-era shape; no version churn |

**Why:** there are no public users to protect yet. Version bumps create noise and force dual
readers without benefit. Prefer **additive, backward-compatible** fields under the same version.
Breaking renames/removals wait for an explicit 1.0 compatibility story.

**Exception:** pure internal constants (metrics/journal experiments) may already differ; do not
proliferate new schema versions. When in doubt, keep `1` and document the field in prose.

## Repository layout

Bootstrap build: **Java 25 + Gradle** (until self-hosting CI is complete). Runtime modules:

| Area | Modules | Role |
|---|---|---|
| `shared/` | `jsonl`, `jk-api`, `plugin-sdk`, `core`, `client-io`, `toolchain-jdk`, `wire` | JSONL codec, client-safe contracts, config/lock, CLI I/O, JDK tools, wire |
| `server/` | `io`, `resolver`, `toolchain`, `engine` | Repo fetch, PubGrub, import/export tools, build plan; `EngineMain` + fat jar packaging (never links CLI) |
| `clients/` | `cli`, `web` | Slim wire client (native/JVM), dashboard SPA |
| `plugins/` | `java-compiler`, `kotlin-compiler`, `groovy-compiler`, `test-runner`, `auditor`, `publisher`, `image-builder`, `formatter`, `compat-bridge`, `spring-boot`, `quarkus`, `grails`, `android`, `protobuf`, `minified` | First-party workers / build plugins |

**Layering:** `jsonl` → `{plugin-sdk, wire, cli}` ; `jk-api` → `core` → `{client-io, wire, …}` → server `{io, resolver, toolchain}` → `engine` → clients. Plugins depend on `plugin-sdk` (+ transitive `jsonl`), not on engine internals.

Ship layout (`./gradlew dist`): slim native `jk` + `lib/jk-engine-<version>.jar`.

## Dependency resolution

- **Algorithm:** PubGrub (same family as Dart `pub` / `uv`).
- **Conflict policy:** bare POM versions are highest-wins floors (not Maven nearest-wins).
  **With** a platform BOM and default policy **enforced**, BOM-map GAs use the BOM pin
  exactly; opt-in **`[resolve] platform = "floor"`** / `jk update --platform=floor` treats
  BOM-map pins as lower bounds only. GAs the BOM does not manage keep highest-wins mediation
  by default; **`[resolve] unmapped = "strict"`** makes their fills exact (JK-1241). Explicit
  Maven ranges stay open.
- **Lockfile:** one root `jk-lock.toml`; builds never re-resolve.
- **BOMs:** enforced platform by default; incomplete BOM families (e.g. maven-resolver
  named-locks) still get family alignment into the map. **`jk export bom`** freezes a lock
  scope into a Maven BOM POM for consumers.
- **Remotes:** built-in order JumpKick → Central → Google; exclusive specialist groups
  `cc.jumpkick.*` / `build.jumpkick.*` never resolve from Central (see [maven-repo.md](maven-repo.md)).
  Path/git remotes preserve exclusive bindings when prepended.
- **Scopes:** **main**, **test**, and **processor** graphs are solved separately so processor
  constraints do not force main versions. Dual lock rows are allowed when versions diverge;
  classpaths select by scope.
- **POM fidelity:** exclusions and Maven version ranges are honored on expand; optional deps
  stay out until features activate them.
- **Budgets / anti-loop:** `JK_RESOLVE_MAX_DECISIONS` (default 100 000), `JK_RESOLVE_TIMEOUT_MS`
  (default 120 s). Every prop/conflict step counts toward a step budget
  (`maxDecisions × 16`). Conflict **watermarks** fingerprint decision maps that already
  failed so the solver cannot re-enter them (cleared when a universe expands).

Package identity in the solver is `group:artifact:type:classifier` (defaults: type `jar`,
classifier empty → `g:a:jar:`). Legacy lock rows with bare `g:a` still load. BOM management
and exclusions stay GA-scoped.

## Build execution

1. Expand a verb (`build`, `test`, …) into a DAG of steps with typed inputs/outputs.
2. Hash inputs (sources, classpath ABI inputs, flags, toolchain, plugin code, …).
3. **Action cache** hit → restore outputs from the **cache CAS**; miss → run and store.
4. Compilers and tests run in **forked plugin processes** sized by a shared memory plan.

**Two-tier CAS** (separate roots, separate budgets):

| Tier | Root | Contents |
|------|------|----------|
| **Artifact store** | `~/.local/share/jk/store/` (`JK_STORE_DIR`) | Long-lived blobs under `sha256/…` + Maven-layout views under `repos/<name>/…` (deps, workers, installLocal) |
| **Cache** | `~/.cache/jk/` (`JK_CACHE_DIR`) | Action index (`actions/`) + rebuildable action payloads under `sha256/…` |

Repo materialization **hard-links** store CAS blobs into `repos/<name>/` when the filesystem
allows (one allocation) via portable NIO `Files.createLink`. GC unlinks **both** the store CAS
path and matching `repos/` entries so space is reclaimed. Action payloads never share the
artifact pool: deleting `~/.cache/jk` drops index and action blobs without touching deps.
Ingest from build outputs / `~/.m2` is copy (or opt-in link for m2) so non-store trees never
share identity with a hashed blob; writers inside either CAS must temp + atomic-replace.

### Action keys and future remote cache (design)

Local action keys already hash the ingredients a remote cache would need. **Do not rewrite
keys** when adding a read-only remote later — only add an optional remote lookup layer.

| Ingredient | Local today | Remote note |
|---|---|---|
| Task type / id | `task:` line (e.g. `compile-main`) | Keep stable names |
| jk version | `jk:` in key material | Pin engine version for cross-machine hits |
| Toolchain / release | `--release`, Kotlin target | Include JDK major when outputs are version-sensitive |
| Sources | path + content SHA-256 | Prefer content-only relative paths for portability later |
| Classpath / processors | CAS path (content-addressed) | Same hex blobs work remote |
| Plugin / worker jar | worker hash in artifact keys | Must stay part of the key (upgrade invalidates) |
| OS/arch | only when outputs are platform-specific | Omit for pure class jars |

**CAS addressing:** blobs are `sha256` content-addressed; a remote store can use the same hex
keys. Local restore remains copy-not-link (see CAS invariant tests).

**Read-only remote client (post-GA sketch):** lookup action key → download missing blobs →
restore into local CAS/action cache → proceed as a local hit. No write-back, ACLs, or REAPI
execution in 1.0.

**Non-goals for 1.0:** remote write-back, multi-tenant trust, full Bazel REAPI execution,
cross-org sharing.

**Compatibility:** if key material gains fields, version the action-key schema (prefix or
schema byte) so old local entries are not silently reinterpreted.

## Plugins

Build plugins own a `jk.toml` table (`[spring-boot]`, `[quarkus]`, `[grails]`, `[android]`, …)
via a jar containing `jk-plugin.toml` plus optional code that runs **out of process**. The
engine never classloads plugin code. See [plugins.md](plugins.md).

Notable first-party packaging plugins:

| Table | Packaging | Notes |
|-------|-----------|--------|
| `[spring-boot]` | Boot jar (`BOOT-INF/…`) | Optional AOT step |
| `[quarkus]` | fast-jar (default) / uber-jar | Pure bootstrap augment; workspace path deps in `lib/main` |
| `[grails]` | Boot-layout jar | Grails 8 + Groovy lane + `grails-app/` roots |

There is no third-party marketplace yet; first-party plugins ship with jk and version together.

## Extension surface

- **Today:** CLI + engine HTTP dashboard; IDE project file generation (`jk ide` / export).
- **IDE engine client (ticket-1014):** Java facade `cc.jumpkick.cli.ide.IdeEngineClient` for IDE
  hosts and agents. Sequence: open project → `connect()` → `projectInfo()` →
  `sync(ProgressListener)` → `ideModel()` / optional `build(BuildListener)`. Reuses frozen wire
  verbs (project-info, sync, build, ide-model); does not load the engine into the IDE JVM. File
  generation remains the offline export path.

### IDE integration sequence

1. Resolve `EnginePaths` / install layout (same as the CLI — user has a local JumpKick).
2. `IdeEngineClient.open(projectDir)` — requires `jk.toml`.
3. `connect()` — ensures a version-matched resident engine (UDS; TCP+auth on Windows).
4. `projectInfo()` — modules, coord, workspace root without client-side TOML parsing.
5. `sync(listener)` — materialize lock artifacts; map `onStep*` to a progress bar.
6. `ideModel()` — absolute classpath jars + source/classes roots for the open workspace.
7. Optional `build(listener)` — module/step events for a Build tool window.
8. Keep `jk ide` / export for writing `.idea` / `.vscode` files when desired.
9. **BSP (ticket-1028):** `jk bsp install` writes `.bsp/jk.json`; `jk bsp serve` speaks a minimal
   BSP 2.x JSON-RPC on stdio and delegates to `IdeEngineClient` (initialize, buildTargets, sources,
   dependencyModules, compile). No engine jars on the IDE classpath. Marketplace plugins (1017)
   can sit on BSP or call the facade directly.

### BSP ↔ engine wire (MVP)

| BSP | JumpKick |
|---|---|
| `build/initialize` | local capability advertise |
| `workspace/buildTargets` | `projectInfo` + `ideModel` modules |
| `buildTarget/sources` | module source roots from layout / model |
| `buildTarget/dependencyModules` | `ideModel` lib jars (absolute URIs) |
| `buildTarget/compile` | `IdeEngineClient.build` |

- **VS Code (ticket-1017):** `clients/vscode/` — VSIX, tasks/commands via `jk`, BSP install.  
- **IntelliJ (JK-1054 / JK-1551):** `clients/intellij/` — install-from-disk zip. **Sync project**
  uses `jk ide --print-model` (structured model) + `jk ide --idea` (shared generator apply) +
  `jk bsp install` (dual-path with JetBrains BSP). Open-project activity offers/auto Sync when
  `jk.toml` is present. No engine jars on the plugin classpath. BSP (JK-1552): run, cancel,
  outputPaths, sources jars, publishDiagnostics.

### Request phases vs build stages

Two fixed taxonomies (do not collapse them):

| Layer | Type | Scope |
|-------|------|--------|
| **Request** | `InvocationPhase` | Whole engine call: `initialize → resolve → plan → toolchain → build → finalize` |
| **Module plan** | `BuildStage` | Inside a module `BuildPlan` (usually during `InvocationPhase.BUILD`): `resolve → generate → compile → test → package → native → image → other` |

- **Task DAG** (`TaskNames` + `requires`) is the scheduler; stages are product buckets for UI fold, ETA, and future pre/post hooks — not a second scheduler.
- In-plan stage **`resolve`** (parse / lock classpath / ensure JDK) ≠ request phase **`RESOLVE`** (lock/graph for the command).
- Prefer `Task.builder(…).stage(BuildStage.COMPILE)`; free-form `group("…")` maps unknown strings to `OTHER`.
- `TaskPhases` remains a string facade over `BuildStage` for metrics call sites.
- **Build-logic anchors** are pre/post cuts on stages (`BEFORE_COMPILE`→generate, `AFTER_COMPILE`→compile, `BEFORE_PACKAGE`→package).
- **Inter-stage requires**: a task may not require a task in a *later* stage (plan validation).
- **Plugins**: optional `TaskSpec.stage("compile")` (describe wire); else engine infers (e.g. source-gen → `generate`).

### Remaining wall-work `R(t)` (progress + ETA)

Countdown and the aggregate progress bar share one oracle: residual schedule of unfinished
module costs (`RemainingWork` / `WorkSchedule`). Seed `R0` ≡ `jk explain`; live `R(t)` updates
as modules progress/finish. Bar ≈ `elapsed / (elapsed + R(t))` (cap 99%); countdown re-anchors
to residual so both end on time with `R → 0`. See [progress-contract.md](progress-contract.md).

### Project build logic (`.jk-build/`, ticket-1037)

Convention directory **`.jk-build/`** (hidden) next to `jk.toml` holds project-local build logic
(overridable via `[build].logic`): **stem scripts** (`before-compile.groovy` / `.kts`, …) and/or
**compiled Java/Kotlin** SPI / `*Build` mains under e.g. `.jk-build/src/`. Scripts-only trees are
valid. The
engine action-caches each task’s `outDir` and merges into the classes tree. No scripts in TOML.
Anchors: `BEFORE_COMPILE` (codegen), `AFTER_COMPILE`, `AFTER_RESOURCES`, `BEFORE_PACKAGE` — each
carries a stage wire name aligned with `BuildStage`. See
[user build logic](../user/build-logic.md).

## Status

Pre-1.0 alpha. **Self-host phase 2:** root workspace covers library/client modules plus thin
workers (`plugins/test-runner`, `plugins/java-compiler`); `jk lock` + `jk build --skip-tests`
dogfoods after a Gradle `dist`/`installLocal` or thin `:cli:installDist` + `:engine:shadowJar`
bootstrap. Full `dist`, remaining plugins, and nested engine integration tests remain
Gradle-heavy. Breaking changes remain acceptable until 1.0.
