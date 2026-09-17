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
  200-module build); (2) worker JVM heaps from free RAM via `HeapPlan`, and per compile the
  larger of that share and `WorkerHeap`'s estimate from the module's classpath and source bytes
  (one retry at twice the heap on a worker OOM, then a failure naming module and heaps);
  (3) concurrency via
  **`-j` / `--jobs` / `JK_JOBS` / `[engine] jobs`** (Mill-shaped: `0`=effective cores via
  cgroup quota when present else `availableProcessors()`, `1`=serial, `N`=cap),
  still RAM-clamped by `PluginSlots`; (4) **per-job input-tree retain**
  (`[engine] vfs-max-mb` / `JK_ENGINE_VFS_MAX_MB`, default 32 MiB per job, live sum
  capped at 75% of engine heap; further jobs stream). Does **not** follow `CI=1`; extra
  heap is concurrency headroom, extra VFS is a huge-tree knob. See [vfs.md](vfs.md). Do not grow the non-CI engine default toward multi-GiB
  “just in case”; set `max-heap-mb` or run under CI for a higher default.
  Full `[engine]` / `JK_ENGINE_*` inventory: [user/engine.md](../user/engine.md#configuration).
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
| **Engine-side reader** | A connection sends its first line, then keeps sending between requests; the engine closes one that does not and counts it as `idleDropped` in status | **10 s** for the first line; `JK_STREAM_IDLE_MS` between requests afterwards; off while a job owns the connection (a quiet client mid-build is normal — the job watchdogs bound it) |
| **Job heartbeat** | Engine emits `heartbeat` while async wire jobs run; detached (HTTP/MCP) jobs have no stream to keep alive, so only the wall-deadline watchdog runs | `JK_ENGINE_HEARTBEAT_MS` (default **30s**; `0` disables) — resets client stream idle |
| **Job wall deadline** | Cancel token + worker shutdown + interrupt runner; connection join bounded. A socket job's EOF is its real bound, so its cap is off unless set; a detached job has no connection to end it, so it always runs under a cap — the submission's own (`POST /api/build` `deadlineMs`, MCP `deadline_s`; `0` = none) or the engine's detached default. The cancel reason names the knob | Socket: `JK_ENGINE_JOB_DEADLINE_MS` (default **0** = off). Detached: `[engine] detached-deadline-ms` / `JK_ENGINE_DETACHED_DEADLINE_MS` (default **1 hour**). Join grace `JK_ENGINE_JOB_DEADLINE_GRACE_MS` (default **30s**, last-chance wait capped ~1s) |
| **User cancel / EOF** | Cancel token + **grace→force** worker kill; join bounded by cancel grace + 500 ms. Public cancel handle is **jid**. Entry points: Ctrl-C, `jk cancel` / `jk cancel <jid>`, `POST /api/cancel` (`jid` or `dir`), MCP `jk_cancel`. | `JK_CANCEL_GRACE_MS` (default **500**; max 5000). **Never hangs.** |
| **Ensure** | Handshake must succeed | Silent peer (connect works, no reply) → hard-kill once + respawn |
| **Stop** | Process death, not only `bye` | Force-stop waits for pid exit (~1.5s) then escalates |
| **Out of memory** | The engine JVM runs with `-XX:+ExitOnOutOfMemoryError` and `-XX:+HeapDumpOnOutOfMemoryError`: the first `OutOfMemoryError` writes `<state>/engine/java_pid<pid>.hprof` and ends the process, however it was caught. The next client spawns a fresh engine and reports the exit once; `jk engine status` and `jk doctor` name the dump while it exists | Dump ≤ `max-heap-mb`; the idle boundary deletes dumps older than 7 days |
| **Engine log** | The spawner redirects the engine's stderr to `<state>/engine/<key>.log` and rotates it to `.1` at each spawn; the engine writes through a byte-counting sink on the same file and rolls it to `.1` itself when it reaches the cap, so a warning loop cannot fill the disk. One generation is kept. `jk engine status` shows the size and the last roll. Lines are leveled and redacted — see [Logging](#logging) | `[engine] log-max-mb` / `JK_ENGINE_LOG_MAX_MB` (default **16** MiB; `0` = no cap); `[engine] log-level` / `JK_LOG_LEVEL` (default **info**) |

If a stream goes idle, the client fails closed with a clear error (tune with `JK_STREAM_IDLE_MS`;
recover with `jk engine stop --now`). Heartbeats keep long quiet compiles honest against the
idle timer. Huge monorepos leave `JK_ENGINE_JOB_DEADLINE_MS` at `0`; CI can set a wall cap. A
dashboard or agent whose builds legitimately run past an hour raises `detached-deadline-ms`, or
passes a deadline on the submission itself.

### Logging

One logger, no framework. `cc.jumpkick.host.Log` is a facade over the JDK's `System.Logger`
with four levels — `debug`, `info`, `warn`, `error` — and a `Log.detail(key, value, …)` suffix
for structured facts (`units=3 dir="a b"`). Nothing else on the engine classpath logs: SLF4J,
Logback and their configuration files are absent by design, and the guard `engine-log-owner`
forbids `System.out` / `System.err` / `printStackTrace()` in `server/*` and `shared/*` main
code (the CLI owns the terminal and is out of scope).

- **Where lines go.** `Log.install` binds the JDK backend to one stream with one formatter. The
  engine binds `System.err` at entry and again once `EngineLogSink` has taken the stream over,
  so every record lands in `<state>/engine/<key>.log` under the same size cap. A plugin worker
  binds its own stderr, which the engine merges into the worker's protocol stream and keeps a
  bounded tail of. The CLI never binds anything: it reaches `Log.level` to parse a name and no
  further, so the native image carries no logging state.
- **Shape.** `HH:mm:ss.SSS LEVEL message key=value …`, one line per record; a cause follows as
  its stack. Messages keep the `jk engine:` / `jk:` prefixes they had as prints.
- **Threshold.** `[engine] log-level` / `JK_LOG_LEVEL`, default `info`, read at engine start —
  `JK_LOG_LEVEL=debug jk build` takes effect on the engine that shell starts, so run
  `jk engine stop` first. `debug` adds the perf probes and every swallowed-exception line.
- **Redaction.** The formatter passes each formatted record — stack included — through
  `SecretRedactor.known()`, the union of every secret value the process has built a redactor
  from (`.env` declarations, resolved repository credentials). A declared secret does not reach
  the log at any level.
- **Perf probes.** `cc.jumpkick.runtime.base.Perf` writes `perf <label> ms=<n>` and
  `perf <label> key=value …` at debug; there is no separate switch. Reading them:
  `JK_LOG_LEVEL=debug`, restart the engine, then `grep ' perf ' ~/.jk/state/engine/*.log`.
- **Swallowed exceptions.** A catch of `Exception`, `Throwable` or `RuntimeException` whose body
  would otherwise be a comment logs `Log.debug("<method>: <why it is tolerated>", e)`; the
  `swallowed-broad-catch-ratchet` guard holds the count of comment-only ones at zero. What the
  preflight could not read is not a log line but a typed outcome: `PreflightMemo.Uncertain`,
  which schedules the module and reaches `jk explain` as "rebuilt because the preflight could
  not read <path> (<cause>)".

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
| `[mcp] tools` | `JK_MCP_TOOLS` | `loop` | Which cards `tools/list` serves: `loop` (the fix-and-rerun set plus `jk_tools`) or `all`; every tool stays callable |

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
| `jk-lock.toml` | `version` / `Lockfile.CURRENT_VERSION` | Stay on **1**; additive rows/fields only (guarded: `schema-freeze`, `lock-version-is-one`) |
| Client↔engine wire | `EngineProtocol.PROTOCOL` | Stay on **1** (guarded: `schema-freeze`) |
| CLI JSONL / session transcripts | `JsonlShape.SCHEMA` / `"schema"` | Stay on **1** (guarded: `schema-freeze`) |
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

Build: **jk** (the root `jk.toml` workspace; `jk-lock.toml`; `.jk/*.kts` build scripts). Runtime modules:

| Area | Modules | Role |
|---|---|---|
| `shared/` | `host`, `jk-api`, `plugin-sdk`, `core`, `client-io`, `toolchain-jdk`, `wire`, `ide`, `guard-api` | JSONL codec + host primitives (`Hashing`, `PathUtil`, `Errors`, `Os`, `Exit`), client-safe contracts, config/lock, CLI I/O, JDK tools, wire, IDE project-file generators, guard-test library (`jk-guards-junit`) |
| `server/` | `io`, `resolver`, `toolchain`, `guard`, `engine` | Repo fetch, PubGrub, import/export tools, house-rule guards, build plan; `EngineMain` + fat jar packaging (never links CLI) |
| `clients/` | `cli`, `cli-terminal`, `web` | Slim wire client (native/JVM), JDK-only TTY/style/keys leaf, dashboard SPA |
| `plugins/` | `java-compiler` (job-scoped Zinc worker; PLAN for `jk explain`), `kotlin-compiler`, `groovy-compiler`, `test-runner`, `auditor`, `publisher`, `image-builder`, `formatter`, `spring-boot`, `quarkus`, `grails`, `android`, `protobuf`, `minified` | First-party workers / build plugins |

**Layering:** `host` → `{plugin-sdk, wire, cli, cli-terminal}` ; `jk-api` → `core` → `{client-io, wire, …}` → `ide` → server `{io, resolver, toolchain, guard}` → `engine` → clients. Plugins depend on `plugin-sdk` (+ transitive `host`), not on engine internals. `host` is the only module a plugin worker, the engine and the native client all link, so it stays JDK-only — `core` cannot serve that role because it api-exposes tomlj.

**Inside `server/engine`:** the root package `cc.jumpkick.engine` (server, connection, startup, the
SSE publisher) sits on top; `engine.api` is its leaf — the JSON and wire writers, the in-flight build
table, history kinds and fingerprints, the coalescing listeners, the lock floor, the live snapshot and
the `SseEvents` surface — and imports no other engine package, so `jobs`, `journal`, `http`, `verbs`
and `listen` read it instead of reaching back up. Worker-process budgeting (`JobWorkers`) lives with
the worker launcher in `engine.plugin`, below `runtime`, `compile`, `test` and `git`, which all use it.
The build runtime is three packages in one direction: `runtime.base` (what the planner reads and
nothing reads back — metrics and priors, compile and tool support, lock primitives, identities; no
class there names the core or imports `task`, `compile`, `test` or `git`), `runtime` (the planner core:
the plan builders, forecast, effort weights, lock pipeline — one strongly connected component of
about fifty classes) and `runtime.workspace` (the workspace phases, the build service, the ETA and
the plan builders that compose the core). The remaining package cycles are the three the guard's
`cycle-baseline.txt` line counts: the planner core (`runtime` and `test`, joined by `AffectedTestRun`
reading `BuildPlanner`), the MCP front (`http`, `http.mcp`, `http.mcp.tools`) and the job/journal pair.

The CLI's verbs are five families under `cc.jumpkick.command` — `pipeline`, `project`, `toolchain`,
`interop`, `system` — each with a package charter, over a root that holds only what more than one
family reads. A verb reaches the shell through `cli.api` and never names it: the two verbs that need
the dispatcher (selective's re-entry, the completion scripts' command table) take it through their
constructor, so no verb imports the dispatcher's package (`cc.jumpkick.cli` itself) and the
dispatcher is outside every cycle.

Ship layout (`jk build`, under `target/dist/`): slim native `jk` + `lib/jk-engine-<version>.jar` + `lib/jk-<version>.jar` (the JVM client, for hosts with no native `jk`) + `repos/jk-local/` (every module's thin jar and POM in Maven layout; `install.sh` shelves it so the installed engine runs the workers built beside it).

## Dependency resolution

- **Algorithm:** PubGrub (same family as Dart `pub` / `uv`).
- **Conflict policy:** bare POM versions are highest-wins floors (not Maven nearest-wins).
  **With** a platform BOM and default policy **enforced**, BOM-map GAs use the BOM pin
  exactly; opt-in **`[resolve] platform = "floor"`** / `jk update --platform=floor` treats
  BOM-map pins as lower bounds only. GAs the BOM does not manage keep highest-wins mediation
  by default; **`[resolve] unmapped = "strict"`** makes their fills exact. Explicit
  Maven ranges stay open.
- **Lockfile:** one root `jk-lock.toml`; builds never re-resolve.
- **Workspace members:** one solve over the merged manifest; a member whose own exact pin or
  BOM scope the merged answer cannot serve is solved on its own (`MemberPartitions`), its
  disagreeing rows carry `members = [path]`, and `Lockfile.forMember` narrows the lock to one
  module's rows before any classpath is assembled.
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
- **Warm-up ahead of the solver:** the roots before the first decide, and every positive edge
  as its parent expands, are read speculatively on the io pool — the version catalog for a
  floating edge, then the `.module` redirect and the effective POM (parents and imports) of
  the version the solver will most likely take — so the solver's own reads are memo hits. A
  `.module` is asked only of the repository that served the POM (Gradle publishes the two
  together), never of the other repositories in the walk. The
  checksum sidecars of a download travel beside its body rather than after it. A POM or
  artifact path a repository answered "not found" is remembered for the metadata TTL, so a
  re-lock in the same engine pays none of those 404s a multi-repository walk produces; a version
  catalog miss is never remembered, and `jk outdated`, `jk update` and `--force` read catalogs
  past every memo. Neither the not-found memo nor the sixty-second version-list memo keeps an
  answer from a loopback repository (`localhost`, `127.*`, `::1`): its port names whatever process
  holds it now, and a loopback round trip is not the cost the memos exist to save.
- **Download budget:** `DownloadSlots.width()` — four per core, one per 4 MiB of engine heap,
  within [8, 64] — bounds two pools. *Row slots:* `ArtifactMaterializer` and `CacheSync` submit
  lock rows through a window of that width (a task exists only once an earlier one finished, so
  the population stays near the width rather than a parked thread per row) and each task holds a
  row slot for its per-repository legs, download and sidecar reads. *Leg slots:* every network
  leg `RepoGroup` fans out across repositories — a POM, a version catalog or an artifact asked of
  one repository — takes a leg slot of its host (a pool per host of four times the host's
  `HostRateLimiter` permits) on the calling thread before it is handed to the io pool, and releases
  it as it ends or when cancelled unstarted. Legs are submitted in two passes — first those whose
  host has room, then the rest in repository order — so a slow repository holds back only its own
  legs while the other hosts' permits stay busy, and the solver's warm-up (`MavenPackageSource`
  prefetch workers, sized to half the width, and `EffectivePomBuilder`'s BOM-import expansions) is
  bounded in what it parks as well as in what it connects. A row never waits on a leg's slot and a
  leg never waits on a row's, so the two cannot deadlock. Per host, six requests at once (`HostRateLimiter`; twenty on
  the Central mirror). `CentralMirror` is the Central failover at `Http`'s choke point: a 429, or a
  403 carrying Cloudflare's headers, from Central opens a four-hour window (a stamp file's mtime,
  under `~/.jk/cache`) during which every Central-bound request is reissued against Google's mirror;
  artifact bytes prefer the mirror regardless, and `LockOrchestrator` raises the window's cause as a
  lock note. A download streams through the JDK's 16 KiB copy buffer into a `.put-` temp
  in the repository's store tree (`DownloadLeg`), so a row in flight costs its connection and that
  buffer, never its payload.
- **Budgets / anti-loop:** `JK_RESOLVE_MAX_DECISIONS` (default 100 000) caps decisions, and every
  prop/conflict step counts toward a step budget (`maxDecisions × 16`). Time is budgeted by
  progress, not by length: a solve is stopped only when no decision, version catalog read or POM
  read — the speculative reads included — has advanced for the stall window,
  `JK_RESOLVE_TIMEOUT_MS` (default 120 s; `0` never stops a solve), and the refusal names what the
  solver was doing when everything stood still. A thousand-dependency reactor on a busy engine
  takes as long as it takes. Conflict **watermarks** fingerprint decision maps that already
  failed so the solver cannot re-enter them (cleared when a universe expands).
- **Speculative reads end with the solve:** when a resolve returns, what the warm-up has not
  started is dropped and what it is reading is cancelled, and the call returns only once nothing is
  in flight — so a store the caller then deletes or replaces sees no late write.

Package identity in the solver is `group:artifact:type:classifier` (defaults: type `jar`,
classifier empty → `g:a:jar:`). Legacy lock rows with bare `g:a` still load. BOM management
and exclusions stay GA-scoped.

## Build execution

1. Expand a verb (`build`, `test`, …) into a DAG of steps with typed inputs/outputs.
2. Hash inputs into an action key. A **compile** key (javac, groovyc, kotlinc) sees its compile
   classpath through ABI tokens: the JVM ABI of each entry for javac and groovyc (signatures,
   supertypes, inlined constants, API annotations — `ClasspathAbi`), the Build Tools API
   classpath-snapshot digest for kotlinc (`KotlinClasspathAbi`, which also covers inline bodies
   and `const val`). Sources enter by content, a mixed module's Java sources enter the kotlinc key
   by declaration digest (`JavaSourceApi`), and options, the JDK and the compiler closure ride
   along. The processor path stays full content. A body-only change in a dependency therefore
   leaves every consumer's compile key — and its freshness stamp — alone; the compilers still see
   the full jars and directories. **Package, test, native and image** keys hash full bytes
   (`ClasspathFingerprint`), so the same change re-packages the producer and re-runs every suite
   whose runtime classpath carries it. Artifact keys also carry the producing engine's identity
   (`BuildIdentity.buildId()`), so a reinstalled engine re-runs plugin steps, guard lanes,
   build-logic and packaging once and never restores what the previous engine produced.
   A compile key and the freshness stamp's instant are both taken before the compiler reads its
   first source. A source whose bytes move while the compiler runs is re-read afterwards: the
   compile is reported and not recorded, its incremental analysis is dropped, and the stamp reads
   stale for that source, so the next build compiles the module from what is then on disk.
3. **Action cache** hit → restore outputs from the **cache CAS**; miss → run and store. A javac
   miss hands the worker the Zinc analyses of the jk-built entries on its classpath
   (`ProducerAnalyses`, found from the entry alone because a compile's state is keyed by its
   output directory), so the consumer's dependencies on a sibling are per-class external
   dependencies rather than one library stamp per jar; a producer analysis that is unreadable or
   no longer describes its classes falls back to the library stamp.
4. Compilers and tests run in **forked plugin processes** sized by a shared memory plan.

**Two storage tiers** (separate roots, separate budgets):

| Tier | Root | Contents |
|------|------|----------|
| **Artifact store** | `~/.jk/store/` (`JK_STORE_DIR`) | Maven-layout jars under `repos/<origin-id>/…` plus `.jk` memos — one tree per repository origin (`RepoIdentity`: reserved `central`/`google`/`jumpkick`, else `<host>-<digest>`), the project's name for it only a label in `.origin`; first-party workers under `repos/jk-local/`; `libs.global.toml`; cloned Giter8 catalogs under `templates/`. The Maven local repository (`~/.m2/repository` by default) is the primary blob store when `[m2] integration` is on. |
| **Cache** | `~/.jk/cache/` (`JK_CACHE_DIR`) | Action index (`actions/`) + rebuildable action payloads under `sha256/…` |

Dependency jars are real `*.jar` files. Compile classpaths never use hash-named CAS blobs.
A digest-matching file in the Maven local repo is used in place; a mismatch is left untouched
and the locked bytes live under `repos/<origin-id>/`. Action-cache restore stays copy-not-link so
compilers cannot mutate cached outputs. `jk storage nuke` does not delete `~/.m2`.

### Action keys and future remote cache (design)

Local action keys already hash the ingredients a remote cache would need. **Do not rewrite
keys** when adding a read-only remote later — only add an optional remote lookup layer.

| Ingredient | Local today | Remote note |
|---|---|---|
| Task type / id | `task:` line (e.g. `compile-main`) | Keep stable names |
| jk version | `jk:` in key material | Pin engine version for cross-machine hits |
| Toolchain / release | `--release`, Kotlin target | Include JDK major when outputs are version-sensitive |
| Sources | module-relative path + content SHA-256 | Portable: two checkouts of one module compute the same key |
| Compile classpath | ABI token (`abi:<sha256>` for javac/groovyc, `kt-abi:<sha256>` for kotlinc) | Content-derived; a body-only dependency change keeps the key |
| Processors / runtime classpath | content identity (`file:<sha256>`, `dir:<sha256>`) | Hex identity, independent of on-disk path |
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
9. **BSP:** `jk bsp install` writes `.bsp/jk.json`; `jk bsp serve` speaks BSP 2.x JSON-RPC on
   stdio and delegates to `IdeEngineClient`. No engine jars on the IDE classpath. Marketplace
   plugins can sit on BSP or call the facade directly.

### BSP ↔ engine wire

The method set is `BspServer`'s dispatch — `build/initialize` and `build/initialized`,
`workspace/buildTargets` and `workspace/reload`, `buildTarget/sources`, `dependencyModules`,
`outputPaths`, `compile`, `test` and `run`, `build/cancel`, `build/shutdown`, `build/exit`, plus the
`build/publishDiagnostics` and `build/logMessage` notifications. Reads answer from `projectInfo` +
`ideModel`; `compile`/`test`/`run` are engine jobs through `IdeEngineClient`. The user-facing
capability table, including what is deliberately not implemented (a debug adapter), is
[docs/user/ide.md](../user/ide.md) — one owner, so this page does not carry a second copy to drift.

**IDE file generators** live in `shared/ide` (`cc.jumpkick.ide`): `IdeModel.fromWire` rebuilds the
`ide-model` record, `IdeGenerators` runs the IntelliJ and VS Code generators through an `IdeOutput`
(writing, or preview), and `BspConnectionFile` writes `.bsp/jk.json`. The module is pure
model-to-files — no terminal, no engine — which is why it sits above `wire` and `toolchain-jdk` and
below both `cli` and `engine`: `jk ide` adds the live chrome around the same generators the engine's
MCP `jk_ide` runs in-process. `wire` cannot host them (it is the frozen protocol contract, and the
generators need `toolchain-jdk`); `core` cannot see the wire record at all.

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
- **Workspace edges are compile-to-compile.** A module's compile classpath names its siblings' `classes/main` trees (`WorkspaceClasspath.siblingClosureClasses`), never their jars, and the workspace scheduler admits a dependent once every module on that classpath has compiled, assembled its classes and copied its resources — while those modules still package, test and build their tails. The jars remain what the dependent's package, test, native and plugin steps read; `copy-resources` is the first step behind which all of them sit, so it waits there (`SiblingArtifacts`) for the siblings to have published their artifacts and then names any jar, test output or fixtures directory that is missing. `resolve-deps` requires only the trees.
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

### Coexistence: a `pom.xml` as the manifest (design)

**Goal.** `jk build` and `jk test` in a directory that has a `pom.xml` and no `jk.toml` run
jk's engine, cache, incremental compiler and results writer with Maven's effective POM as the
project model. No manifest migration; the loop arrives first, the `jk.toml` later or never.

**Where the POM enters: a shadow manifest, not a second loader.** The engine has no manifest
abstraction; about forty sites resolve `dir/jk.toml` on their own (`PlannerSetup`, `PreflightMemo`,
`BuildGraph`, `ProjectCard`, `GraphOps`, `LockManifestDigest`, `WorkspaceLoader`, `ModuleLayout`,
…), and `BuildCommand` / `LockFlow` / `PreflightMemo` gate on the file existing. Virtualising all of
them is churn without a product gain. Instead:

- `ManifestPaths.manifestIn(dir)` is the one way to name a module's manifest. It answers
  `dir/jk.toml` when that exists; otherwise, when `dir/pom.xml` exists, it answers the **shadow**
  `dir/target/jk/shadow/jk.toml` through the process's `ManifestPaths.ShadowSource`. The engine
  installs `ShadowManifests` at start-up, which renders the shadow on first read and again when
  any POM file it read (or the running jk) differs from what the shadow's header names; the native
  client installs nothing and only names the path. Every read site resolves through
  `manifestIn(dir)`; `ManifestPaths.moduleOf(manifest)` maps a manifest back to its module, so a
  shadow parsed through `JkBuildParser.parse` resolves its workspace from the module, not from
  `target/jk/shadow`. The sites that write a `jk.toml` (`jk new`, `jk import`) keep the raw
  `dir/jk.toml`; the ones that edit it (`jk add`, `jk remove`, `jk update`, the MCP manifest
  editors) refuse a shadowed directory with `ManifestPaths.noManifestToEdit`, which names both
  remedies: `jk import pom.xml` to own a `jk.toml`, or edit the POM.
- The shadow is what `PomImporter` produces from the effective POM (parents flattened, managed
  versions applied, properties interpolated), rendered by `JkBuildRenderer` under a `ShadowStamp`
  header (`PomShadow`): the first line carries a digest and the rendering jk's version, one `# read
  <path>` line per POM file the import read — the module's own and every parent its
  `<relativePath>` reaches on disk — and the digest covers those files' bytes, so editing a parent
  re-renders the child. It is a build artefact under `target/`, never committed. `jk import` still
  writes a real `jk.toml` for a user who wants one; the shadow makes that optional.
- Digests stay byte-based. `PreflightMemo`'s three `feedFile` sites and `LockManifestDigest` hash
  the shadow's bytes, which change exactly when the POM chain changes, so memos and lock
  staleness keep their meaning.

**Build directory.** A coexistence build writes into the module's `target/` exactly as a
`jk.toml` module does (`classes/main/`, `lib/`, `reports/`), so Maven's own `target/classes` and
`surefire-reports` share the tree when both tools run; the shadow and its lock live under
`target/jk/shadow/`. Results land at `target/jk-results.md`, the path agents already read, with a
`manifest: pom.xml, no jk.toml` line in the header; `details.jsonl` beside it. Rooting the whole
build under `target/jk/` (a per-build `BuildLayout` root) is open.

**Lockfile.** Maven has no lockfile, so the first coexistence build resolves and writes the lock
beside the owner's shadow and later builds reuse it; the repository gains no file.
`LockPaths.lockOwnerDir` keeps answering a project directory — the module itself, or the reactor
root for a leaf — and `LockPaths.lockFile` places the file under that owner's `target/jk/shadow/`
when the owner is shadowed, so every site that resolves modules, `.env` files or JDK pins against
the lock owner reads the real tree. The POM's direct versions win over transitive requests
(`[resolve] pins = "nearest"`, the policy `jk import` writes), bare versions are exact pins and
BOM imports are enforced platforms; transitives resolve by PubGrub.
A `<mirror>` in Maven's `settings.xml` (`cc.jumpkick.m2.MavenSettings`) is applied where the
repository group is built (`RepoMirrors`, on every `MavenRepo` including the ones a dependency's
POM declares): the repository keeps its name, URL and store, and only the URL its requests open
changes, so the lock records `central` behind Nexus exactly as it does on the open internet. The
active profiles' `<repositories>` join the shadow's `[repositories]` through `PomImporter`.

**Tier-3 rows.** What the import report would grade Tier 3 (`<build><extensions>`, a `war`
packaging, a `system`-scoped dependency, a parent no repository serves) does not stop the build:
`ShadowManifests` parks the rows at render time and the next build's parse step reports each once
under Warnings with the `jk import pom.xml` remedy; a reactor's own rows ride with the first leaf
that drains.

**Multi-module.** A reactor root — `<modules>` at the top level or in a profile — is rendered
through `PomImporter.importWorkspace`: the root's shadow carries the leaves `ReactorModules` walks
as `[workspace] modules`, each leaf's shadow lands under the leaf's own `target/jk/shadow/` with
sibling dependencies rewritten to workspace edges, and every shadow of the tree lists every POM of
the tree, so an edit anywhere re-renders them all. Whichever module is read first triggers the
render: a leaf finds its root through `PomReactorScan.reactorRootOf` and materializes the root.
`PomReactorScan` (shared/core, `DomXml`, no Maven) is the bootstrap twin of the TOML scan: it
follows the raw `<modules>` of the root and of every profile through nested aggregators, and
`WorkspaceScan.isWorkspaceRoot` / `findRoot` answer for a POM-built tree the way they do for a
`jk.toml` one — the outermost reactor is the root, a nested aggregator is not — so `LockPaths`,
`BuildLayout` and the `jk build` entry all agree on one root. A directory the root lists that
Maven would not build here (an aggregator, a module of an inactive profile) has no shadow;
reading it names the root to build from.

**MCP.** `jk_bind` already accepts any directory; the card for a shadowed project reads its
identity from the shadow, and `jk_results` / `jk_diagnostics` need no change once the engine writes
through the normal journal.

**Scope.** Single modules and reactors, default Maven layout (`ModuleLayout.TRADITIONAL` matches
`src/main/java`, `src/test/java`, resources): `jk build`, `jk test`, `jk explain`, results, cache
hit on the second run, one-file edit recompiles the delta. A `--partial` skip of unshadowable
modules and POM-declared `<sourceDirectory>` (which needs a root override the model does not
have) are open.

## Status

Pre-1.0 alpha. **Self-hosted:** the root workspace covers every library, client, worker and rule
pack; `jk build`, `jk install`, `jk guard` and `jk test` are the gate and the release, after a
`curl … install.sh` bootstrap from the hosted release. The native client is the shipped client;
platforms with no hosted client yet are listed in [releases](releases.md#platforms-without-a-hosted-client).
Breaking changes remain acceptable until 1.0.
