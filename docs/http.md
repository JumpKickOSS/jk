# The engine's embedded HTTP server

The engine serves a dashboard (the SPA in `clients/web`) and an MCP surface over an embedded HTTP
server. This file is cited from `api.js`, `EngineStatusCommand`, `EngineRotateTokenCommand` and the
engine tests; it had never been written, which is how the guarantee below came to be relied on by
client code without being recorded anywhere.

## On by default

`[http]` is **enabled by default** — loopback bind, **token-gated `/api/*`** (static shell open). It is not opt-in. Turn it
off with `[http] enabled = false` in `~/.config/jk/config.toml` or `JK_HTTP_ENABLED=false`; a malformed
config yields empty and fails closed (no server).

`[mcp] enabled = false` disables only the MCP surface, never the server. Machine-scoped, not
project-overridable, read once at engine start.

That default matters for reasoning about engine lifetime: since most engines have HTTP on, any rule
of the form "engines with HTTP behave differently" applies to nearly all of them.

## The engine never idles out

**A resident engine does not self-terminate for being idle.** There is no idle timeout, and adding
one would be a breaking change to the dashboard's contract, not a tuning knob.

The SPA is written against this. `api.js`:

> `EventSource` reconnects on its own; an HTTP-enabled engine never idles out, so `'offline'` only
> ever means an explicit stop, an upgrade respawn, or a crash.

So the client treats a lost stream as an anomaly. If engines idled out, `offline` would become
routine while the UI still reported it as exceptional.

The reason is an asymmetry between clients, and it is the whole argument:

- The **CLI** ensures a live, version-matched engine before every request, spawning one if none is
  listening. An engine that exited costs a cold start, not an error.
- A **browser tab** cannot spawn anything. If the engine it was talking to is gone, the SPA is dead
  until the user knows to drop to a terminal and run a jk command — which is an astonishing thing to
  ask of someone whose primary interface is the dashboard.

Concretely: a developer working through the Web UI who leaves the tab open overnight must find it
working the next morning.

## When an engine *does* exit

Three states, decided by whether the `<key>.endpoint` pointer names this engine:

| Pointer | State | Behaviour |
|---|---|---|
| names this engine | **primary** | Never self-terminates. Exits on `jk engine stop` or version-skew replacement. |
| names another | **displaced** | Surrenders the HTTP port **immediately**, then drains in-flight jobs and exits. |
| absent | **orphaned** | Exits once genuinely unused: no in-flight jobs **and** no attached SSE stream. |

### Displacement surrenders the port unconditionally

A newer engine taking over needs the port. A displaced engine calls `stopNow()` on its HTTP server
right away — attached dashboard streams get **no vote**. This is correct because there *is* a
successor: the tab reconnects to it, and the HTTP token is deliberately preserved across the respawn
so it does not have to re-authenticate. Client-side reconnect is the SPA's job.

### An orphan waits for an attached tab

An orphaned engine is one no pointer names: no CLI will reach it again, and no successor wants its
port either. Before this was handled, such an engine served forever — the displacement check required
the pointer to *exist*, so a deleted one left an unreachable engine running indefinitely (JK-1293).

It now exits when unused, but an attached SSE stream vetoes that. Unlike displacement there is no
successor to hand the tab to, so exiting under an open dashboard would strand it with nothing to
reconnect to.

The count comes from the SSE admission budgets rather than a separate counter, so it cannot drift from
what actually holds a slot: a stream keeps its permit for the life of the connection.

## Stopping engines

The engine identity is a hash of the state directory **and** the artifact store, so a machine can
hold several at once — one per `(state dir, store)` pair. `jk engine status` lists every running
engine with its id and pid; `jk engine stop` addresses the one this directory resolves to,
`--all` stops all of them, and `--pid <pid>` stops one by pid.

Every form of stop escalates to a hard kill if the engine does not exit, and reports what actually
happened. Reliably stopping an engine must never require the user to reach for `kill` or hunt through
Task Manager.

## Auth tiers

**Every `/api/*` call requires a valid bearer token**, including loopback binds. There is no
tokenless “watch-only” mode — a bare browser open without a token must not see live builds or
history. Static content stays open, in two tiers: the classpath shell (`index.html`, JS, CSS,
images shipped in the jar) is served under the SPA's own CSP so it can show the blocking
authorization dialog, while files under the on-disk `web-root` (user reports and other
build-written content) are served with `Content-Security-Policy: sandbox` — a unique opaque
origin with scripts and forms disabled, so nothing dropped into `web-root` can script the
dashboard origin or read the stored bearer token.

Classpath shell files are `Cache-Control: no-cache` plus a version+mtime `ETag`, so a new
engine jar is visible on the next load (revalidate always; unchanged bytes are a `304`). Disk
`web-root` is also `no-cache` (`Last-Modified`). `/api/*` is `no-store`. There is no
`max-age` on the shell: a still-fresh cached copy would hide a replacement engine, including
security patches, and `location.reload()` after an `engineEpoch` mismatch does not bust
subresources the browser still considers fresh. Non-loopback clients carry the token the same way; `EventSource`
cannot send headers, so SSE passes it as an `access_token` query parameter. The SPA bootstraps
from a `#t=` fragment. **`jk web`** starts the engine if needed, prints the tokenized URL, and
opens a browser (`$BROWSER` or the platform default).

Missing or invalid credentials → **401** (plus SPA hard-gate). Engine generation mismatch →
**409** with `engine-epoch-mismatch` (see below).

### `GET /api/project`

`GET /api/project?project=<id>` (preferred) or `?dir=<abs-path>`.

Returns `{ projectId, dir, coord, description }`. `project` is the durable identity; `dir` is the
last-known checkout path for build/graph ops.

### `GET /api/project/graph`

Dependency graph for the Project page (JK-1542), same idea as `jk tree`:

`GET /api/project/graph?dir=<path>&scopes=main,test&transitive=0|1`

| Query | Default | Meaning |
| --- | --- | --- |
| `dir` | required | Project or workspace root (checkout path) |
| `scopes` | `export,main,runtime` | Comma-separated canonical scopes (same default as `jk tree`). Percent-encoded like any query value; an unknown name is a **400** naming the valid set |
| `transitive` | `false` | When true, expand lockfile transitive deps under each declared root (same as `jk tree -t` / `--transitive`) |

Response:

`{ dir, workspace, scopes, transitive, truncated, availableScopes, nodes: [{ id, label, kind, version?, path? }], edges: [{ from, to, scope? }] }`

`kind` is `module` (workspace member — the workspace root itself is a `module` node at path `.`),
`declared` (listed in a selected-scope `jk.toml`), or `transitive` (lockfile-only). Nodes are keyed
by full package identity (`group:artifact:type:classifier`), so a test-jar or classifier variant is
its own node; the label shows `g:a` plus a classifier/type suffix when non-default. Edges are
dependent → prereq. Transitive expansion is capped (500 nodes / 2000 edges); `truncated: true`
means the graph is a prefix of the full closure — narrow the scopes or turn off `transitive`.

Errors: a missing root `jk.toml` (deleted or non-jk checkout) is a **200** with empty `nodes` —
genuinely "no dependencies". A project that exists but cannot be loaded — malformed `jk.toml`, a
workspace member missing its `jk.toml`, IO failure — is a **422** with `{ error }` naming what is
broken; a malformed `dir` or unknown scope is a **400**. Token-gated like `/api/project`. The SPA
loads this **only** when the Dependencies panel opens.

The default scope set is defined once — `DependencyTree.defaultScopeOrder()` (`export`, `main`,
`runtime`) — and shared verbatim by `jk tree` and this endpoint. Declared-only is the default on
both; `transitive=1` matches `jk tree -t` / `--transitive`.

### `GET /api/project/files`

`GET /api/project/files?project=<id>`

Allow-listed source paths under the identity checkout (`ProjectIdentity.pathForId`). No `dir=`
fallback — a tree that merely contains a `jk.toml` is not enough.

Response: `{ projectId, dir, truncated, files: [{ path, lang }] }`. `path` is workspace-relative
with `/` separators. `lang` is `java` / `kotlin` / `groovy` / `toml` / `json` / `markdown` /
`mermaid` / `graphviz` / `asciidoc` / `d2` / `image` (plus the usual source extensions). Hidden
segments, `node_modules`, module-root `target`/`build`/`out`, and unknown extensions are omitted.
The walk is breadth-first, capped at 2000 files and 32 directory levels, so truncation drops
the deepest paths first; the workspace-root `jk.toml` is always included when it exists.
In-root directory symlinks are walked (once — cycles are guarded by real path); links whose
target escapes the workspace are omitted, matching the read endpoints. Entries are sorted by
path; `truncated: true` means more remain (file cap or depth cap — deeper files stay readable
via `GET /api/project/file`).

Errors: missing `project` → **400**; unknown id or missing checkout → **404**. Token-gated.

### `GET /api/project/file`

`GET /api/project/file?project=<id>&path=<rel>`

UTF-8 body of one allow-listed file. `path` is workspace-relative (`src%2FMain.java`). Same
sandbox as the list: identity checkout, real-path containment, shared allow-list. Hidden /
output / unsupported paths are **404** (existence is not distinguishable).

Response: `{ projectId, dir, path, lang, bytes, lines, encoding, etag, content }`. `encoding` is
`utf-8`, or `iso-8859-1` when the bytes were not valid UTF-8 (the pane labels the fallback
instead of silently substituting U+FFFD). `etag` is the SHA-256 hex of the on-disk bytes
(optimistic concurrency for PUT). Image allow-list entries (`lang: image`) are **415**
here — use the raw endpoint below.

| Status | When |
| --- | --- |
| 200 | OK |
| 400 | missing `project` / `path`, or illegal relative path |
| 401 | no / bad bearer |
| 404 | unknown project, missing file, or non-servable path |
| 413 | servable file larger than 1 MiB |
| 415 | binary / image path (NUL in the first 8 KiB, or image extension) |

### `GET /api/project/file/raw`

`GET /api/project/file/raw?project=<id>&path=<rel>`

Raw bytes of one allow-listed file with a suitable `Content-Type` (images for the dashboard
Preview pane). Same sandbox as the JSON body endpoint. Clients must `fetch` with the bearer
token and build a blob URL — a bare `<img src>` cannot send `Authorization`.

| Status | When |
| --- | --- |
| 200 | OK (`Cache-Control: no-store`) |
| 400 | missing `project` / `path`, or illegal relative path |
| 401 | no / bad bearer |
| 404 | unknown project, missing file, or non-servable path |
| 413 | larger than 1 MiB |

### `PUT /api/project/file`

`PUT /api/project/file` with JSON body `{ "project", "path", "content", "etag"?, "encoding"? }`.
`encoding` echoes the value GET returned (`utf-8` default, `iso-8859-1` for the Latin-1
fallback) so a save re-encodes to the original charset instead of transcoding; content no
longer representable in the declared charset → **400**.

Replace a **text-servable** file under the identity checkout (atomic temp+move). Images and
non-servable paths are not writable. Body size is capped near 1 MiB of content (not the smaller
global 64 KiB mutation limit used for build/cancel).

When `etag` is present it must match the current on-disk SHA-256 (from GET). Mismatch → **409**
`{ "error": "file changed on disk", "etag": "<current>" }` so a multi-tab / external edit cannot
silently clobber. Omit `etag` for last-write-wins.

Response: `{ projectId, dir, path, lang, bytes, lines, etag }` (new content hash), plus
`"lockStale": true` when the saved file is a manifest (`jk.toml` / `jk-libs.toml`) — the lock's
`manifests-sha256` stamp no longer matches, so the next build pays a full re-resolve.

| Status | When |
| --- | --- |
| 200 | OK |
| 400 | missing fields or illegal path |
| 401 | no / bad bearer |
| 404 | unknown project, missing file, or non-servable path |
| 409 | `etag` present but file changed on disk (not engine-epoch) |
| 413 | content larger than 1 MiB (or request body over cap) |
| 415 | file type is not text-writable (e.g. image) |
| 500 | filesystem write failure |

The dashboard `#project/<id>/files/…` pane is the primary consumer (Copy / Preview / Save + Build).

### `GET /api/metrics`

Aggregate build history as a flat array, one object per row, averages pre-computed so clients stay
arithmetic-free. `?dir=<path>` keeps that project's rows plus the always-included machine tiers.

| `scope` | Row is | `kind` | `task` |
| --- | --- | --- | --- |
| `global` | every build on this machine | `build` / `test` | null |
| `project` | every build of one `dir` | `build` / `test` | null |
| `task` | one task across every project | null | task name |
| `project/task` | one task in one `dir` | null | task name |

The tier names come from `BuildMetrics.SCOPE_*`; the socket `metrics-entry` frame carries the same
`scope` values.

### `GET /api/config`

Effective machine `~/.config/jk/config.toml` (plus env) as `{ path, rows: [{ key, default, value,
overridden }] }` for the Status Configuration panel — every known scalar key with its default and
whether the effective value differs. Token-gated even on loopback (JK-1524): the payload names the
owner's config path and raw values, the same class as `/api/projects/defaults`.

The token file persists across restarts precisely so an open tab survives an upgrade or crash
respawn. `jk engine rotate-token` is the explicit way to invalidate it.

## Live updates (`GET /api/events`)

One SSE stream serves **build activity** and **chrome vitals**. Additive event names only (no
protocol version bump). Fan-out is `HttpEvents` (bounded per-subscriber queues with priority
shedding: a full queue evicts the oldest low-priority frame — output/label lose to
progress/structure, and the freshest sample always survives); the engine skips work when
`hasSubscribers()` is false.

| Kind | Events | When published |
| --- | --- | --- |
| **Inflicted** | `request-start` / `plan` / `module-*` / `task-start` / `task-finish` / `label` / `plan-progress` / `workspace-progress` / `eta` / `output` / `diagnostic` / `*-finish` / `request-finish` | As the plan mutates state. Structural events are immediate; hot ticks (`progress`/`tick-update`/`label`/`output`) ride the 500 ms wire coalescer — see "Live smoothness" below |
| **Sampled** (change-gated) | `status` | ~every 2 s while any client is subscribed, **and** only when presentation-quantized vitals change (CPU ~1 pp, RAM/heap ~1 MiB, counters exact). Also forced on stream connect and nudged on request start/finish |
| **Sampled** (change-gated, IO) | `cache` | Safety-net tick (60 s) while subscribed, plus after request finish; snapshot walks are single-flight and TTL-memoized (30 s) engine-side; **not** on the 2 s status sampler. Live frames are **thin** (dual surface totals + budgets, `"thin": true`); full section breakdown is REST-only |

The sampled `status`/`cache` frames are **dashboard-stream chrome**: MCP SSE subscriptions
(`GET /mcp`) never receive them, and an MCP stream alone neither starts nor sustains the samplers
— "while subscribed" above means dashboard (`/api/events`) subscribers.

The `cache` storage walk never runs on a request thread: connect hydrate re-sends the last
captured snapshot and never schedules a walk of its own; the post-build nudge is async. A
first-ever connect therefore carries no `cache` frame until a nudge or the 60 s sampler
captures one — the SPA fires a one-shot `GET /api/cache` whenever it is live with no cache
data yet (the REST path shares the same single-flight, TTL-memoized snapshot, so this cannot
storm the store).

**Mid-build connect:** a new dashboard subscription starts *detached* — the engine captures one
compact `run-snapshot` per in-flight job, delivers them to **that subscription only** (not a
broadcast, not a phase-by-phase replay), and only then attaches it for broadcasts, all under a
connect ordering lock that excludes concurrent publishes. Every event is therefore either
reflected in the snapshot or delivered to the queue after it; overlap folds idempotently, gaps
are impossible. Independently, `GET /api/history` enriches `running: true` rows with the same
live fields so the SPA's initial GET matches the TUI even before the first SSE frame.

`run-snapshot` payload (same shape as an enriched history row): `requestId`/`jid`, `kind`,
`dir`, `coord?`, `projectId?`, `buildNumber?`, `historyId?` (journal id, present for journaled
kinds), `startedAt` + `serverNow` (engine wall-clock pair — the SPA derives skew-free elapsed
as `serverNow − startedAt` and re-anchors it to its own clock at receipt; both omitted until
the hold registers), `running: true`, `progress?`, `remainingMs?`, `R0?`,
`numerator`/`denominator?`, and phase chains: `modules[]` (`dir`, `coord?`, `finished`,
`success`, `millis`, `didWork?` when finished, `tasks[]`) or top-level `tasks[]`
(`name`/`stage`/`status`/`millis`) for single-plan runs.

**Live smoothness:** hot progress traffic (aggregate `workspace-progress`, plan
`progress`/`tick-update`/`label`/`output`) is sampled at **`JK_WIRE_PROGRESS_MS`** (default
**500 ms**) on both the CLI UDS path and SSE — same coalescer. `progress`/`tick-update`/`label` are sampled (latest wins); `output` lines are queued and
delivered as a batch each cadence tick, so multi-line bursts (test-failure stacks, native-image
logs) arrive complete. Structural events stay immediate.
The SSE queue sheds low-priority frames before critical ones when full (oldest first). The SPA drains
EventSource callbacks on animation frames and coalesces progress/ETA ticks so the main thread
stays free; open-loop clock/residual fills the gaps between 500 ms samples.

### `event: status`

Core engine/host vitals (same facts as `GET /api/status` heap/load/plans fields), including
`availableMemoryBytes`, `systemCpuLoad`, `systemLoadAverage` (1‑minute), and `engineEpoch`
(process generation id). Config knobs (`httpUrl`, `maxConcurrentRequests`, …) stay REST-only;
the SPA merges SSE into the last REST hydrate.

### Engine generation (`engineEpoch`)

Every process mints a stable `engineEpoch` (`version[+buildId]@startedAtMillis`). It appears on
`GET /api/status` and every SSE `status` frame. After bootstrap, non-bootstrap `/api/*` calls must
send `X-Jk-Engine-Epoch: <epoch>` matching the running process; mismatch or missing header →
**409** `{ "error": "engine-epoch-mismatch", "engineEpoch": "…" }`. Exempt: `GET /api/status` and
`GET /api/events` (EventSource cannot send headers). Static shell is ungated. The SPA latches the
epoch and hard-reloads when it changes (engine restart / displacement).

### `event: cache` and `GET /api/cache`

Two storage surfaces (CLI parity: `jk cache usage` / `jk storage usage`), not one combined
“cache used” total:

| Surface | Bytes | Budget field |
| --- | --- | --- |
| **Cache tier** | action index + cache CAS + format stamps → `cacheBytes` / `actionCacheBytes` | `cacheMaxBytes` / `actionMaxBytes` (`[cache] max-cache-size-gb`, default 4 GiB / 8 GiB on CI) |
| **Artifact store** | store CAS + `repos/` mirrors + run logs → `artifactStorageBytes` | `maxBytes` (`[cache] max-store-size-gb`, default 6 GiB / 12 GiB on CI) |

Full REST also exposes `actionsCount`/`actionsBytes` (index), `cacheCasCount`/`cacheCasBytes`
(cache CAS), and store section fields. **Live SSE (thin):** `{ "thin": true, cacheBytes,
cacheMaxBytes, actionCacheBytes, actionMaxBytes, artifactStorageBytes, maxBytes,
lastPrunedMillis }` — enough for the footer; change-gated on MiB quanta.

**REST (full):** section counts (`casCount`, `actionsCount`, …) for the Status panels. Prefer the two surfaces for UI; `totalBytes` is the combined sum.

Capture is a full exclusive walk of the store/cache trees (hardlink-aware). The engine memoizes it
with a **30 s TTL and single-flight** so concurrent `GET /api/cache` calls do not re-walk multi‑GiB
stores in parallel. SSE connect hydrate **never** walks (it only re-sends a stored snapshot);
first numbers come from Status `GET /api/cache`, post-build `notifyLiveCache` (invalidates the
memo), or the slow 60 s safety-net sampler. Without that, Chrome reconnect storms at engine start
left SerialGC holding ~90 MiB used/committed at idle.

REST `GET /api/status` and `GET /api/cache` remain for hydrate, offline fallback, CLI/MCP tools,
and curl. Metrics (`GET /api/metrics`) stay **REST-only / view-scoped** — not on the vitals SSE bus.

### Build SSE publish map (JK-1499)

Inflicted publishers live on `EngineServer` (socket listener + HTTP job listeners). Every dashboard
fold type has a site; progress is coalesced by the intentional `JK_WIRE_PROGRESS_MS` (default 500 ms) filter on
`workspace-progress` (same as the TUI), never by `LiveVitals`.

| Event | Publisher (typical) | Notes |
| --- | --- | --- |
| `request-start` | `publishRequestStart` | CLI admit + HTTP workspace/lock |
| `plan` | `publishPlan` | Total weight for bar denominator |
| `module-start` / `module-finish` | workspace listener | Per-module rows |
| `task-start` / `task-finish` | plan listener | Phase-tagged steps |
| `label` | plan listener | Live step detail (test class.method, “shrinking jar”, …); SPA paints after the running phase node |
| `plan-progress` | plan ticks | Single-module / per-module detail |
| `workspace-progress` | `emitWorkspaceProgress` | Aggregate %; peak-hold + 0.1% / frame filter |
| `eta` | `publishEta` | Seed + re-projections |
| `output` / `diagnostic` | step output / failures | Bounded diagnostics |
| `buildplan-finish` | plan end | Module-level success |
| `request-finish` | request finally | Always includes `success` + `cancelled` (CLI + HTTP) |
| `run-snapshot` | `rehydrateLiveRunsOnSseConnect` | Connect-only, delivered to the joining subscription (never broadcast); payload documented under "Mid-build connect" above |
