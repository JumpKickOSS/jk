# Architecture

How jk is structured today. For day-to-day usage see [guide.md](guide.md).

## Design tenets

1. **Declarative core** — `jk.toml` is data; logic lives in plugins and task scripts, not the manifest.
2. **Fast client, hosted build** — native CLI for UX; resident engine for resolution, pipelines, and memory coordination.
3. **Reproducibility by default** — lockfile, content-addressed cache, deterministic packaging.
4. **Diagnostics as product** — PubGrub prose, `jk why` / `jk explain`, machine-readable output.
5. **Adoption first** — `jk mvn` / `jk gradle`, import/export, Maven Central semantics.
6. **Resist plugin sprawl** — first-party batteries; no public marketplace before a frozen SPI.

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
  native image.
- **Engine** — dependency resolution, action graph, CAS, toolchains, compiler/test workers,
  hosted verbs (`build`, `test`, `lock`, `publish`, …). Default heap ceiling **256 MiB**
  (`~/.jk/config.toml` → `[engine] max-heap-mb`, or `JK_ENGINE_MAX_HEAP_MB`).
  **Three budgets:** (1) engine heap = thin coordinator (JK-1075 measured ~36 MiB peak on a
  200-module build); (2) worker JVM heaps from free RAM via `HeapPlan`; (3) concurrency via
  **`-j` / `--jobs` / `JK_JOBS` / `[engine] jobs`** (Mill-shaped: `0`=effective cores via
  cgroup quota when present else `availableProcessors()`, `1`=serial, `N`=cap — JK-1084),
  still RAM-clamped by `PluginSlots`. Do not grow the engine default toward multi-GiB “just
  in case”; CI may raise `max-heap-mb` when needed.
- **Load-bearing** — if the engine cannot start, the command fails clearly (no silent
  in-process fallback for hosted work). That is how concurrent builds avoid RAM overcommit.
- **Lifecycle** — lazy start on first need; stays resident until `jk engine stop` or
  version-skew replacement. One engine per `JK_HOME` / state directory.
- **Versioning** — side-by-side installs under `~/.jk/versions/<v>/`; client and engine jar
  share a version; handshake detects skew and takes over.
- **Liveness** — a listening socket alone is not proof the engine is healthy (ticket-1043):

| Layer | What proves health | Bound |
|---|---|---|
| **Probe** (`ping` / `hello` / `status`) | One request/reply | ~2s client watchdog |
| **Stream** (build / test / sync) | Protocol lines keep flowing | `JK_STREAM_IDLE_MS` (default 60 minutes between lines; `0` disables) |
| **Job heartbeat** (ticket-1051) | Engine emits `heartbeat` while async jobs run | `JK_ENGINE_HEARTBEAT_MS` (default **30s**; `0` disables) — resets client stream idle |
| **Job wall deadline** (ticket-1051 / JK-1067) | Cancel token + worker shutdown + interrupt runner; connection join bounded | `JK_ENGINE_JOB_DEADLINE_MS` (default **0** = off); join grace `JK_ENGINE_JOB_DEADLINE_GRACE_MS` (default **30s**, last-chance wait capped ~1s) |
| **User cancel / EOF** (JK-1096) | Cancel token + **grace→force** worker kill; join bounded by cancel grace + 500 ms | `JK_CANCEL_GRACE_MS` (default **500**; max 5000). **Never hangs.** |
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
(in the engine jar). Disk paths are always `Cache-Control: no-cache`, so edits show up on
refresh without reinstalling.

| Source | Key |
|---|---|
| Env | `JK_HTTP_WEB_ROOT` (absolute path preferred) |
| Config | `~/.jk/config.toml` → `[http] web-root` |
| Default | `~/.jk/state/web` (relative to `JK_HOME`) |

Point at the worktree for UI iteration:

```bash
export JK_HTTP_WEB_ROOT="$PWD/clients/web/src/main/resources/web"
jk engine stop
jk engine start
# edit style.css / index.html / *.webp → hard-refresh the browser
```

Relative `web-root` values resolve against `~/.jk`. Only files present under the root are
overridden; anything missing still falls through to the jar.

Transport: Unix domain socket on macOS/Linux; loopback TCP + shared-secret token on Windows.
Protocol is internal (same-version client/server), JSONL (one JSON object per line).

### Wire freeze vocabulary (pre-1.0)

Same-version client/engine only — not a multi-version public API. Conventions frozen for 1.0:

| Rule | Shape |
|---|---|
| Envelope | One JSON object per line; discriminator field `"type"` (same as CLI JSONL / SSE / MCP) |
| Auth (TCP only) | First line `{"type":"auth","token":…}` — never a raw token line |
| Handshake | `hello` / `hello-ack` carry `version`, `proto` (`EngineProtocol.PROTOCOL`), `purpose` (`connect`\|`probe`); `hello-ack` uses `startedAt` (millis) |
| Errors | `{"type":"error","code",…,"message",…}` (`auth`, `protocol`, `version-skew`, …) |
| Project path | Field name is always `dir` |
| Pipeline finish | `{"type":"pipeline-finish","kind":…,"dir":…,"success":…}` (+ kind-specific tails) |
| Session extras | Variant / client env / JVM tuning via typed `withSession` builders — no JSON string surgery |
| Line limits | Bounded line reader + idle timeout; unknown/`type`-less lines → `error`, not silent drop |

Builders and round-trip tests live in `shared/wire` / `EngineProtocolTest`.

### Schema freeze until 1.0

**Until JumpKick 1.0 ships, do not rev schema / protocol version numbers.** Stay on **version 1**
(or the field’s existing constant) for every external-ish format:

| Surface | Field / constant | Pre-1.0 policy |
|---------|------------------|----------------|
| `jk.toml` | grammar / tables | Additive only; no version bump |
| `jk.lock` | `version` / `Lockfile.CURRENT_VERSION` | Stay on **1**; additive rows/fields only |
| Client↔engine wire | `EngineProtocol.PROTOCOL` | Stay on **1** |
| CLI JSONL / run logs | `JsonlShape.SCHEMA` / `"schema"` | Stay on **1** |
| Session transcripts | `details.jsonl` `"schema"` | Stay on **1** |
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
| `shared/` | `jk-api`, `plugin-sdk`, `core`, `client-io`, `toolchain-jdk`, `wire` | Client-safe contracts, config/lock, CLI I/O, JDK tools, wire codec |
| `server/` | `io`, `resolver`, `toolchain`, `engine` | Repo fetch, PubGrub, import/export tools, build pipeline; `EngineMain` + fat jar packaging (never links CLI) |
| `clients/` | `cli`, `web` | Slim wire client (native/JVM), dashboard SPA |
| `plugins/` | `java-compiler`, `kotlin-compiler`, `test-runner`, `auditor`, `publisher`, `image-builder`, `formatter`, `compat-bridge`, `spring-boot`, `android`, `protobuf`, `shrink` | First-party workers / build plugins |

**Layering:** `jk-api` → `core` → `{client-io, wire, …}` → server `{io, resolver, toolchain}` → `engine` → clients. Plugins depend on `plugin-sdk`, not on engine internals.

Ship layout (`./gradlew dist`): slim native `jk` + `lib/jk-engine-<version>.jar`.

## Dependency resolution

- **Algorithm:** PubGrub (same family as Dart `pub` / `uv`).
- **Conflict policy:** highest-version-wins across a graph; Maven nearest-wins is rejected.
- **Lockfile:** one root `jk.lock`; builds never re-resolve.
- **BOMs:** soft prefer (pin first, full candidate list); stricter floors may lift.
- **Scopes:** **main**, **test**, and **processor** graphs are solved separately so processor
  constraints do not force main versions. Dual lock rows are allowed when versions diverge;
  classpaths select by scope.
- **POM fidelity:** exclusions and Maven version ranges are honored on expand; optional deps
  stay out until features activate them.
- **Budgets:** `JK_RESOLVE_MAX_DECISIONS` (default 100 000), `JK_RESOLVE_TIMEOUT_MS` (default 120 s).

Package identity in the solver is `group:artifact:type:classifier` (defaults: type `jar`,
classifier empty → `g:a:jar:`). Legacy lock rows with bare `g:a` still load. BOM management
and exclusions stay GA-scoped.

## Build execution

1. Expand a verb (`build`, `test`, …) into a DAG of steps with typed inputs/outputs.
2. Hash inputs (sources, classpath ABI inputs, flags, toolchain, plugin code, …).
3. **Action cache** hit → restore outputs from the **CAS**; miss → run and store.
4. Compilers and tests run in **forked plugin processes** sized by a shared memory plan.

Local cache roots under `~/.jk/cache/` (content-addressed blobs + action mappings). CAS writes
are copy/atomic; build trees must not share inodes with immutable blobs.

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

Build plugins own a `jk.toml` table (`[spring-boot]`, `[android]`, …) via a jar containing
`jk-plugin.toml` plus optional code that runs **out of process**. The engine never classloads
plugin code. See [plugins.md](plugins.md).

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
- **IntelliJ (ticket-1054):** `clients/intellij/` — install-from-disk zip, Tools → JumpKick actions
  (`jk bsp install` / sync / build / test). No engine jars on the plugin classpath.

### Project build logic (`.jk-build/`, ticket-1037)

Convention directory **`.jk-build/`** (hidden) next to `jk.toml` holds project-local Java build
logic (overridable via `[build].logic`). The engine compiles and runs mains (`--project` /
`--out`) during `copy-resources`, action-caches outputs, and merges generated files into the
classes tree. No scripts in TOML. See [features/project-build-logic.md](features/project-build-logic.md).

## Status

Pre-1.0 alpha. **Self-host phase 2:** root workspace covers library/client modules plus thin
workers (`plugins/test-runner`, `plugins/java-compiler`); `jk lock` + `jk build --skip-tests`
dogfoods after a Gradle `dist`/`installLocal` or thin `:cli:installDist` + `:engine:shadowJar`
bootstrap. Full `dist`, remaining plugins, and nested engine integration tests remain
Gradle-heavy. Breaking changes remain acceptable until 1.0.
