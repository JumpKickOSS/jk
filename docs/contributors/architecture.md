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
│  (native, or JVM    │                                  │  (HotSpot JVM, capped)   │
│   thin on Windows)  │                                  │                          │
│  TUI, shell, jdk    │                                  │  resolve · build · cache │
│  terminal execs     │                                  │  fork plugin workers     │
└─────────────────────┘                                  └──────────────────────────┘
```

- **Client** — presentation, shell hooks, JDK install prompts, anything that owns your terminal
  (`jk run` exec, `jk mvn`/`gradle` interactive). Preferred form is a Graal native image
  (sub-50 ms cold start). On Windows the thin JVM launcher (`jk.bat` from `:cli:installDist`)
  is also supported. No engine code in the native image. The CLI does not interpret plugin
  schemas — `jk-plugin.toml` and Giter8 templates are engine-only, never `:core` / the
  native client.
- **Engine** — dependency resolution, task graph / BuildPlan execution, CAS, toolchains,
  compiler/test workers, hosted verbs (`build`, `test`, `lock`, `publish`, …). Default heap ceiling
  **256 MiB** (or **512 MiB** when `CI=1`/`true` and unset) via
  `~/.jk/config.toml` → `[engine] max-heap-mb`, or `JK_ENGINE_MAX_HEAP_MB`.
  **Four budgets:** (1) engine heap = thin coordinator (measured ~36 MiB peak on a
  200-module build); (2) worker JVM heaps from free RAM via `HeapPlan`; (3) concurrency via
  **`-j` / `--jobs` / `JK_JOBS` / `[engine] jobs`** (Mill-shaped: `0`=effective cores via
  cgroup quota when present else `availableProcessors()`, `1`=serial, `N`=cap),
  still RAM-clamped by `PluginSlots`; (4) **per-job input-tree retain**
  (`[engine] vfs-max-mb` / `JK_ENGINE_VFS_MAX_MB`, default 32 MiB per job, live sum
  capped at 75% of engine heap; further jobs stream). Does **not** follow `CI=1`; extra
  heap is concurrency headroom, extra VFS is a huge-tree knob. See [vfs.md](vfs.md). Do not grow the non-CI engine default toward multi-GiB
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
- **JDK inventory** — managed installs live in the IntelliJ shared root (`~/.jdks`); JumpKick's
  record of them (`jk-jdks.toml`, fingerprints, Java/Graal defaults) lives under the platform
  **state** dir (`$JK_STATE_DIR`, default `~/.jk/state`). No `default-jdk` / `current-jdk`
  symlinks under data, and no access log — jk never evicts a JDK, so there is nothing to rank.
- **Identity** — one engine per (state directory, artifact store) pair. The store is part of the
  identity hash because two invocations can share a state dir while disagreeing about where downloads
  belong; without it, `JK_STORE_DIR` silently did nothing. A machine can therefore hold several
  engines: `jk engine status` lists every resident engine this user owns (including other
  `JK_HOME`s and draining/ghost pids). `jk engine stop --all` stops **this home only** so a
  nested test suite cannot kill the host engine. `--pid` stops one explicitly.
- **Versioning** — one live engine under `~/.jk/lib/jk-engine/`, with the live pointer in
  `jk-engine.toml` beside the jars, paired with
  the PATH `jk`. One engine is hosted at a time, so there is no per-version directory tree. An upgrade
  writes the new jar **beside** the previous one (`jk-engine-<version>.jar`, or
  `jk-engine-<version>.<epochMillis>.jar` when that name is occupied) and points `jk-engine.toml` at it
  — never rename or overwrite a jar `java.exe` may have mapped. The previous client is parked as
  `jk.old` (`jk.exe.old` on Windows). GC deletes retired jars and parked clients once the displaced
  engine drains. Handshake detects skew and takes over.
  **Newer always wins**: the lock's
  `jk-min` is a *floor*, never a pin — a jk older than the floor refuses artifact jobs with an
  upgrade error (`jk self update`) on every surface, and nothing ever fetches or runs an older
  engine to satisfy a lock. The lock pins inputs (artifacts, checksums, BOMs), not the operator;
  `generated-by` is provenance only. Same lock ⇒ same resolved graph across jk versions until
  `jk update`; tool behavior may still change pre-1.0 (a newer jk may rebuild).
- **Liveness** — a listening socket alone is not proof the engine is healthy:

| Layer | What proves health | Bound |
|---|---|---|
| **Probe** (`ping` / `hello` / `status`) | One request/reply | ~2s client watchdog |
| **Stream** (build / test / sync) | Protocol lines keep flowing | `JK_STREAM_IDLE_MS` (default 60 minutes between lines; `0` disables) |
| **Job heartbeat** | Engine emits `heartbeat` while async wire jobs run; detached (HTTP/MCP) jobs have no stream to keep alive, so only the wall-deadline watchdog runs | `JK_ENGINE_HEARTBEAT_MS` (default **30s**; `0` disables) — resets client stream idle |
| **Job wall deadline** | Cancel token + worker shutdown + interrupt runner; connection join bounded | `JK_ENGINE_JOB_DEADLINE_MS` (default **0** = off); join grace `JK_ENGINE_JOB_DEADLINE_GRACE_MS` (default **30s**, last-chance wait capped ~1s) |
| **User cancel / EOF** | Cancel token + **grace→force** worker kill; join bounded by cancel grace + 500 ms. Public cancel handle is **jid**. Entry points: Ctrl-C, `jk cancel` / `jk cancel <jid>`, `POST /api/cancel` (`jid` or `dir`), MCP `jk_cancel`. | `JK_CANCEL_GRACE_MS` (default **500**; max 5000). **Never hangs.** |
| **Ensure** | Handshake must succeed | Silent peer (connect works, no reply) → hard-kill once + respawn |
| **Stop** | Process death, not only `bye` | Force-stop waits for pid exit (~1.5s) then escalates |

If a stream goes idle, the client fails closed with a clear error (tune with `JK_STREAM_IDLE_MS`;
recover with `jk engine stop --force`). Heartbeats keep long quiet compiles honest against the
idle timer. Huge monorepos leave `JK_ENGINE_JOB_DEADLINE_MS` at `0`; CI can set a wall cap.

**Worker cancel contract:**

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
| Config | `~/.jk/config.toml` → `[http] web-root` |
| Default | `~/.jk/state/web` (under the platform state dir) |

Point at the worktree for UI iteration:

```bash
export JK_HTTP_WEB_ROOT="$PWD/clients/web/src/main/resources/web"
jk engine stop
jk engine start
# edit style.css / index.html / *.webp → hard-refresh the browser
```

Relative `web-root` values resolve against the jk home root (`~/.jk`, or `$JK_HOME`).
Only files present under the root are overridden; anything missing still falls through to the jar.

### HTTP server knobs

`~/.jk/config.toml`; env wins over the file (`env > file > default`):

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
| CLI JSONL / session transcripts | `JsonlShape.SCHEMA` / `"schema"` | Stay on **1** |
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
| `shared/` | `host`, `jk-api`, `plugin-sdk`, `core`, `client-io`, `toolchain-jdk`, `wire` | JSONL codec + host primitives (`Hashing`, `PathUtil`, `Errors`, `Os`, `Exit`), client-safe contracts, config/lock, CLI I/O, JDK tools, wire |
| `server/` | `io`, `resolver`, `toolchain`, `engine` | Repo fetch, PubGrub, import/export tools, build plan; `EngineMain` + fat jar packaging (never links CLI) |
| `clients/` | `cli`, `cli-terminal`, `web` | Slim wire client (native/JVM), JDK-only TTY/style/keys leaf, dashboard SPA |
| `plugins/` | `java-compiler` (job-scoped Zinc worker; PLAN for `jk explain`), `kotlin-compiler`, `groovy-compiler`, `test-runner`, `auditor`, `publisher`, `image-builder`, `formatter`, `spring-boot`, `quarkus`, `grails`, `android`, `protobuf`, `minified` | First-party workers / build plugins |

**Layering:** `host` → `{plugin-sdk, wire, cli, cli-terminal}` ; `jk-api` → `core` → `{client-io, wire, …}` → server `{io, resolver, toolchain}` → `engine` → clients. Plugins depend on `plugin-sdk` (+ transitive `host`), not on engine internals. `host` is the only module a plugin worker, the engine and the native client all link, so it stays JDK-only — `core` cannot serve that role because it api-exposes tomlj.

Ship layout (`./gradlew dist`): slim native `jk` + `lib/jk-engine-<version>.jar`.

## Dependency resolution

- **Algorithm:** PubGrub (same family as Dart `pub` / `uv`).
- **Conflict policy:** bare POM versions are highest-wins floors (not Maven nearest-wins).
  **With** a platform BOM and default policy **enforced**, BOM-map GAs use the BOM pin
  exactly; opt-in **`[resolve] platform = "floor"`** / `jk update --platform=floor` treats
  BOM-map pins as lower bounds only. GAs the BOM does not manage keep highest-wins mediation
  by default; **`[resolve] unmapped = "strict"`** makes their fills exact. Explicit
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

**Two storage tiers** (separate roots, separate budgets):

| Tier | Root | Contents |
|------|------|----------|
| **Artifact store** | `~/.jk/store/` (`JK_STORE_DIR`) | Maven-layout jars under `repos/<name>/…` plus `.jk` memos; first-party workers under `repos/jk-local/`; `libs.global.toml`; cloned Giter8 catalogs under `templates/`. The Maven local repository (`~/.m2/repository` by default) is the primary blob store when `[m2] integration` is on. |
| **Cache** | `~/.jk/cache/` (`JK_CACHE_DIR`) | Action index (`actions/`) + rebuildable action payloads under `sha256/…` |

Dependency jars are real `*.jar` files. Compile classpaths never use hash-named CAS blobs.
A digest-matching file in the Maven local repo is used in place; a mismatch is left untouched
and the locked bytes live under `repos/<name>/`. Action-cache restore stays copy-not-link so
compilers cannot mutate cached outputs. `jk storage nuke` does not delete `~/.m2`.

### Action keys and future remote cache (design)

Local action keys already hash the ingredients a remote cache would need. **Do not rewrite
keys** when adding a read-only remote later — only add an optional remote lookup layer.

| Ingredient | Local today | Remote note |
|---|---|---|
| Task type / id | `task:` line (e.g. `compile-main`) | Keep stable names |
| jk version | `jk:` in key material | Pin engine version for cross-machine hits |
| Toolchain / release | `--release`, Kotlin target | Include JDK major when outputs are version-sensitive |
| Sources | path + content SHA-256 | Prefer content-only relative paths for portability later |
| Classpath / processors | lock digest (`file:<sha256>`) | Hex identity, independent of on-disk path |
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
- **IDE engine client:** Java facade `cc.jumpkick.cli.ide.IdeEngineClient` for IDE
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
9. **BSP:** `jk bsp install` writes `.bsp/jk.json`; `jk bsp serve` speaks a minimal
   BSP 2.x JSON-RPC on stdio and delegates to `IdeEngineClient` (initialize, buildTargets, sources,
   dependencyModules, compile). No engine jars on the IDE classpath. Marketplace plugins
   can sit on BSP or call the facade directly.

### BSP ↔ engine wire (MVP)

| BSP | JumpKick |
|---|---|
| `build/initialize` | local capability advertise |
| `workspace/buildTargets` | `projectInfo` + `ideModel` modules |
| `buildTarget/sources` | module source roots from layout / model |
| `buildTarget/dependencyModules` | `ideModel` lib jars (absolute URIs) |
| `buildTarget/compile` | `IdeEngineClient.build` |

- **VS Code:** `clients/vscode/` — VSIX, tasks/commands via `jk`, BSP install.
- **IntelliJ:** `clients/intellij/` — install-from-disk zip. **Sync project**
  uses `jk ide --print-model` (structured model) + `jk ide --idea` (shared generator apply) +
  `jk bsp install` (dual-path with JetBrains BSP). Open-project activity offers/auto Sync when
  `jk.toml` is present. No engine jars on the plugin classpath. BSP: run, cancel,
  outputPaths, sources jars, publishDiagnostics.

### Request phases vs build stages

Two fixed taxonomies (do not collapse them):

| Layer | Type | Scope |
|-------|------|--------|
| **Request** | `InvocationPhase` | Whole engine call: `initialize → resolve → plan → toolchain → build → finalize` |
| **Module plan** | `BuildStage` | Inside a module `BuildPlan` (usually during `InvocationPhase.BUILD`): `resolve → generate → compile → test → package → train → native → image → publish → other` |

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

### Project build logic (`.jk/`)

Convention directories **`jk/`** (visible) or **`.jk/`** (hidden) next to that module's
`jk.toml` hold project-local stem scripts (overridable via `[build].logic`):
`before-compile.groovy` / `.kts` and sibling stems. If both dirs exist, `jk/` wins.
`.kts` wins a same-stem `.groovy`. Groovy and Kotlin scripts run in forked processes.
Compiled `.java` / `.kt` under the logic dir is rejected. The engine action-caches each
task’s `outDir` and merges into the classes tree (`BEFORE_COMPILE` is a generated-source
root). No scripts in TOML. Anchors: `BEFORE_COMPILE` (codegen), `AFTER_COMPILE`,
`AFTER_RESOURCES`, `BEFORE_PACKAGE` — each carries a stage wire name aligned with
`BuildStage`. See [user build logic](../user/build-logic.md).

## Status

Pre-1.0 alpha. **Self-host phase 2:** root workspace covers library/client modules plus thin
workers (`plugins/test-runner`, `plugins/java-compiler`); `jk lock` + `jk build --skip-tests`
dogfoods after a Gradle `dist`/`installLocal` bootstrap. The native client is the preferred
shipped client; Windows also supports the thin JVM client (`jk.bat`) because Smart App Control
blocks unsigned `jk.exe` (native signing is still open). Once a release is published the
bootstrap is `curl -fsSL https://jumpkick.build/install.sh | bash`. Full `dist`, remaining
plugins, and nested engine integration tests remain Gradle-heavy. Breaking changes remain
acceptable until 1.0.
