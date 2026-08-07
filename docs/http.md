# The engine's embedded HTTP server

The engine serves a dashboard (the SPA in `clients/web`) and an MCP surface over an embedded HTTP
server. This file is cited from `api.js`, `EngineStatusCommand`, `EngineRotateTokenCommand` and the
engine tests; it had never been written, which is how the guarantee below came to be relied on by
client code without being recorded anywhere.

## On by default

`[http]` is **enabled by default** — loopback bind, token-gated mutations. It is not opt-in. Turn it
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

Loopback binds serve the dashboard without a token; mutations are token-gated. Non-loopback origins
carry the token — `EventSource` cannot send headers, so streams pass it as an `access_token` query
parameter, and the SPA bootstraps from a `#t=` fragment. **`jk web`** starts the engine if needed,
prints the tokenized URL, and opens a browser (`$BROWSER` or the platform default).

**Sensitive reads need the token even on loopback**, because on a shared machine another local
account must not have the engine owner's filesystem and identity for free:

| Endpoint | Why |
|---|---|
| `GET /api/fs` | lists the filesystem with the owner's permissions |
| `GET /api/log` | engine log tail |
| `GET /api/history/artifact` | full on-disk diagnostics / lock snapshots |
| `GET /api/project` | path-existence oracle |
| `GET /api/project/graph` | module dependency DAG (workspace layout / module paths) |
| `GET /api/metrics` | every project dir and coordinate ever built |
| `GET /api/projects/defaults` | derives from the owner's git identity and home layout |
| `GET /api/config` | config file path (home layout) + verbatim values (`templates.official` may embed credentials) |

Aggregate-only reads (`GET /api/status`, `GET /api/cache`), the activity stream
(`GET /api/events`), and the **journal list** (`GET /api/history`) stay open on loopback so a
tokenless dashboard can show live builds **and** rehydrate them after a hard refresh. History
**artifacts** remain token-gated.

### `GET /api/project/graph`

Dependency graph for the Project page (JK-1542), same idea as `jk tree`:

`GET /api/project/graph?dir=<path>&scopes=main,test&transitive=0|1`

| Query | Default | Meaning |
| --- | --- | --- |
| `dir` | required | Project or workspace root |
| `scopes` | `main` | Comma-separated canonical scopes (`main`, `test`, `provided`, …) |
| `transitive` | `false` | When true, expand lockfile transitive deps under each declared root |

Response:

`{ dir, workspace, scopes, transitive, availableScopes, nodes: [{ id, label, kind, version?, path? }], edges: [{ from, to, scope? }] }`

`kind` is `module` (workspace member), `declared` (listed in a selected-scope `jk.toml`), or
`transitive` (lockfile-only). Edges are dependent → prereq. Token-gated like `/api/project`. The
SPA loads this **only** when the Dependencies panel opens.

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
protocol version bump). Fan-out is `HttpEvents` (bounded drop-oldest queues); the engine skips
work when `hasSubscribers()` is false.

| Kind | Events | When published |
| --- | --- | --- |
| **Inflicted** (realtime) | `request-start` / `plan` / `module-*` / `step-*` / `label` / `plan-progress` / `workspace-progress` / `eta` / `output` / `diagnostic` / `*-finish` / `request-finish` | As the plan mutates state — never batched on a timer |
| **Sampled** (change-gated) | `status` | ~every 2 s while any client is subscribed, **and** only when presentation-quantized vitals change (CPU ~1 pp, RAM/heap ~1 MiB, counters exact). Also forced on stream connect and nudged on request start/finish |
| **Sampled** (change-gated, IO) | `cache` | Slow tick (~30 s) while subscribed, plus after request finish; **not** on the 2 s status sampler. Live frames are **thin** (dual surface totals + budgets, `"thin": true`); full section breakdown is REST-only |

The sampled `status`/`cache` frames are **dashboard-stream chrome**: MCP SSE subscriptions
(`GET /mcp`) never receive them, and an MCP stream alone neither starts nor sustains the samplers
— "while subscribed" above means dashboard (`/api/events`) subscribers.

The `cache` storage walk never runs on a request thread: connect hydrate re-sends the last
captured snapshot (refreshing async on the sampler thread), and the post-build nudge is likewise
async — a first-ever connect may briefly carry no `cache` frame until the async capture lands
(the SPA's REST hydrate covers that gap).

### `event: status`

Core engine/host vitals (same facts as `GET /api/status` heap/load/plans fields). Config knobs
(`httpUrl`, `maxConcurrentRequests`, …) stay REST-only; the SPA merges SSE into the last REST
hydrate.

### `event: cache` and `GET /api/cache`

Two storage surfaces (CLI parity: `jk cache storage` / `jk repo storage`), not one combined
“cache used” total:

| Surface | Bytes | Budget field |
| --- | --- | --- |
| **Cache tier** | action index + cache CAS + format stamps → `cacheBytes` / `actionCacheBytes` | `cacheMaxBytes` / `actionMaxBytes` (`[cache] max-cache-size-mb`, default 1 GiB) |
| **Artifact store** | store CAS + `repos/` mirrors + run logs → `artifactStorageBytes` | `maxBytes` (`[cache] max-store-size-mb`, default 4 GiB) |

Full REST also exposes `actionsCount`/`actionsBytes` (index), `cacheCasCount`/`cacheCasBytes`
(cache CAS), and store section fields. **Live SSE (thin):** `{ "thin": true, cacheBytes,
cacheMaxBytes, actionCacheBytes, actionMaxBytes, artifactStorageBytes, maxBytes,
lastPrunedMillis }` — enough for the footer; change-gated on MiB quanta.

**REST (full):** section counts (`casCount`, `actionsCount`, …) for the Status panels. `totalBytes`
is a legacy combined sum; prefer the two surfaces for UI.

REST `GET /api/status` and `GET /api/cache` remain for hydrate, offline fallback, CLI/MCP tools,
and curl. Metrics (`GET /api/metrics`) stay **REST-only / view-scoped** — not on the vitals SSE bus.

### Build SSE publish map (JK-1499)

Inflicted publishers live on `EngineServer` (socket listener + HTTP job listeners). Every dashboard
fold type has a site; progress is coalesced only by the intentional ≥0.1% / TTY-frame filter on
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
