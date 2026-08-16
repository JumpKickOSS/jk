# Machine output, agents, and multi-client event model

**Status:** product stance + implementation map (2026-08-15)  
**Audience:** agents, CI, IDE plugins, MCP clients, maintainers

## Product stance

| Consumer | Default channel | Goal |
|----------|-----------------|------|
| **Human at a TTY** | Terse visual CLI (wedges, bars) | Eyeball success/failure; interesting, not noisy |
| **Human debugging** | `-v` / `--verbose` + files under `target/` | Scrollable prose; pointers to structure |
| **Agents / scripts / CI** | **`--output json` or `jsonl` (identical)** | **Live JSONL event stream** on stdout |
| **Web dashboard** | Engine HTTP + SSE (`/api/events`) | Same facts, SSE framing |
| **MCP clients** | Engine-hosted tools, resources, SSE | Same facts as tools/resources/events — not a second build model |

Humans never need to scrape the TUI. Agents never need to parse ANSI bars.

All of this has shipped: JSONL stream, `details.jsonl` session log, aggregate `progress`
rider, full MCP tool surface + SSE, robust cancel.

## Unified information model

All machine surfaces carry **the same conceptual events**. Framing differs:

| Concept | CLI JSONL (`type`) | Web SSE (`event` + `data`) | Verbose (human) | MCP (tools / SSE) |
|---------|--------------------|----------------------------|-----------------|-------------------|
| Request / session start | `buildplan-start` (per plan); workspace: `workspace-start` | `request-start` | `▶ plan (N steps)` | tool result / `notifications/jk/event` |
| Task start | `task-start` | `task-start` | `· stage/task (ticks: N)` | notification |
| Progress ticks (fine) | `progress`, `tick-update` | `plan-progress` | (bar / quiet) | notification |
| **Whole-job % (aggregate)** | **`workspace-progress`** | **`workspace-progress`** | TUI bar | filter `type=workspace-progress` |
| Label (current work) | `label` | (via progress / output) | last label on finish line | notification |
| User/compiler output | `output` | `output` | printed lines | notification |
| Warning | `warn` | (diagnostic-like) | bang line | notification |
| Error / test failure | `error` (+ `test`, `exceptionClass`) | `diagnostic` | FAILED lines / stacks | tool error + structured fields |
| Step end | `task-finish` | `task-finish` | `✓/✗ step took …` | notification |
| BuildPlan end | `buildplan-finish` | module/request finish | wedge chip | tool result |
| Plan / ETA | (wire → engine; extend JSONL) | `plan`, `eta` | explain / bar countdown | `jk_explain` + notifications |
| Module (workspace) | `module-start` / `module-finish` (+ nested step JSONL) | `module-start` / `module-finish` | completion lines | same events on MCP SSE |
| Workspace end | `workspace-finish` | `request-finish` | summary chip | notification |

**Rule:** when adding a new fact (module coord, worker id, ETA), add it once to the **shared conceptual model**, then project into JSONL fields, SSE `data`, verbose text, and MCP tools — do not invent parallel schemas.

**Smart engine / dumb clients:** workspace aggregate percent is computed **only** in the
engine (`WorkspaceProgressTracker`). Clients render `workspace-progress` and the additive
`progress` rider — they must **not** re-sum module-local `numerator`/`denominator` for the bar.
Bar tuning lives in the engine alone.

### Canonical live stream: JSONL

```bash
jk build --output json …      # same as jsonl
jk test  --output jsonl …
export JK_OUTPUT=json         # or jsonl
```

- **One JSON object per line**, flushed promptly (live).
- Every object includes at least: `schema` (int), `ts` (epoch ms), `type` (string).
- Most lines also carry **`progress`**: aggregate workspace/plan percent **0–100** (or
  `null` until known) from the **engine** tracker — **not** `progress_num`/`progress_den`.
  Per-task `numerator`/`denominator` on `progress` / `tick-update` events stay
  **plan-local** (one module). For whole-job % without task spam, subscribe to
  **`type=workspace-progress`** (fields: `progress`, `numerator`, `denominator`, `phase`,
  `modulesComplete`, `modulesTotal`).
- Schema version: **`1`** forever until **jk 1.0** (see [architecture.md — Schema freeze](architecture.md#schema-freeze-until-10)).
  Do **not** bump for additive fields. No pre-1.0 version churn.
- Terminal human chrome is **suppressed** in this mode so stdout stays parseable.

Example lines (illustrative):

```json
{"schema":1,"ts":1721664000123,"type":"buildplan-start","plan":"test","denominator":42,"tasks":3,"progress":12.5}
{"schema":1,"ts":1721664000456,"type":"task-start","task":"run-tests","stage":"test","ticks":10,"progress":45}
{"schema":1,"ts":1721664000789,"type":"label","task":"run-tests","label":"cc.jumpkick:jk-core :: FooTest > bar()  [w2]","progress":67.3}
{"schema":1,"ts":1721664000901,"type":"error","task":"run-tests","code":"test-failure","message":"…","test":"cc.jumpkick:jk-core :: FooTest > bar()  [w2]","exceptionClass":"org.opentest4j.AssertionFailedError","progress":67.3}
{"schema":1,"ts":1721664001000,"type":"buildplan-finish","plan":"test","success":false,"duration_ms":880,"warnings":0,"errors":1,"progress":100}
```

Implementation: `JsonlListener` + `JsonlShape` (stdout); `EventLogListener` and
`details.jsonl` write the **same shape** (with the same `progress` rider).

### Session log: `details.jsonl`

```text
~/.local/state/jk/builds/projects/<key>/runs/<run-id>/details.jsonl
```

- Default **on**; disable with `JK_CLI_DETAILS=off` (or `0`).
- **Same event shape** as `--output json`/`jsonl` (`JsonlShape`, `schema: 1`), one object per
  line, **appended live** (safe to `tail -F` mid-run). Ends with a `session-finish` line
  (`exit`, `duration_ms`, optional `wedge` / `modules`).
- Opens with `session-start` (`command`, `argv`). With `-v`, CLI prints `Details: <path>` at
  **open** (and again at finish).
- Prefer this path for offline triage and support; agents watching a long build use the live
  file without waiting for process exit.

### Materialize cadence

Live model updates on every meaningful event; sinks materialize under one policy:

| Rule | Trigger | TTY paint | JSONL append (`--jsonl` + `details.jsonl`) |
|------|---------|-----------|---------------------------------------------|
| **M1** | Phase finish (preflight stage / plan phase) | next frame | **append + flush** |
| **M2** | Test class finish (when wired) | optional | **append + flush** |
| **M3** | Module / plan / command finish | yes | **append + flush** |
| **M4** | Dirty heartbeat | **80 ms** TTY paint (`TTY_FRAME_MS`); wire samples at **`JK_WIRE_PROGRESS_MS`** (default **500 ms**) | **2 s** if dirty (`DISK_HEARTBEAT_MS`) |
| **M5** | Hot ticks (`progress` / `tick-update` / `label` / `output`) | model + next frame; **wire/SSE coalesce** to cadence (`progress`/`tick-update`/`label` latest-wins; `output` queued and batch-flushed — every line delivered) | append line; flush ≤ M4 |

Constants: `LiveProgress.TTY_FRAME_MS = 80` (client paint / open-loop only), wire cadence
`CoalescingBuildPlanListener.DEFAULT_CADENCE_MS = 500` via `JK_WIRE_PROGRESS_MS` (`0` =
unbatched), `DISK_HEARTBEAT_MS = 2000`, `LINE_STALE_MS = 360` (process-output partial lines only).

**Wire vs paint:** the engine socket and dashboard SSE share one human sample rate for aggregate
`workspace-progress`, plan `progress`/`tick-update`/`label`/`output`. Structural events
(`step-start`/`finish`, `warn`/`error`) stay immediate. Clients open-loop the bar/ETA between
samples (CLI residual decay; web clock/residual).

### Deep timing: chrome timeline

```text
target/jk-chrome-profile.json
```

Disable: `--no-timeline` / `JK_CHROME_PROFILE=off`. Linked from docs; not duplicated into every JSONL tick.

### Verbose (`-v`)

- Human-oriented step lines and full output.
- Must stay consistent with the **same facts** (module labels, failure module/worker, step names) but **not** become the agent API.
- Points at `details.jsonl` when available.

### Web API / SSE

Engine hosts HTTP (loopback by default) with:

- `GET /api/status`, `GET /api/events` (SSE), `POST /api/build`, …
- SSE: `event: <type>` + `data: <json>` — types include `request-start`, `task-start`, `task-finish`, `plan-progress`, `eta`, `diagnostic`, module events, …

See [http.md](http.md) for bind, auth, and engine lifetime. Dashboard-only chrome
(`status`/`cache` sample frames on `/api/events`) is **not** sent on MCP SSE.

**Convergence (one conceptual model):** SSE `data`, MCP `notifications/jk/event` params, CLI
JSONL, and (additively) the client↔engine wire all carry the same facts where they describe the
same work:

| Field | Meaning |
|-------|---------|
| `schema` | Always `1` until jk 1.0 |
| `type` | Conceptual event name (`task-start`, `progress`, `error`, …) |
| `progress` | Aggregate % 0–100 or `null` — **not** `progress_num`/`progress_den` |
| `task` / `stage` / `status` / `dir` / `coord` | Same names across surfaces. `stage` is the task's `BuildStage` — a **closed** set (`resolve`, `generate`, `compile`, `test`, `package`, `native`, `image`, `other`), always present, never free-form. `phase` means only `InvocationPhase` and `workspace-progress` |
| `numerator` / `denominator` | Task-scoped weights (progress events); optional beside the % rider |
| `test` / `exceptionClass` | Structured failure fields |
| `jid` / `requestId` | Public job handle (same integer; prefer **`jid`**, `requestId` is an alias) |

The SSE *event* name may stay SPA-oriented (`plan-progress`, `diagnostic`); agents should
prefer `data.type`. The client↔engine wire uses the same `type` discriminator (and additive
`schema` / `progress` on progress events) — one vocabulary across JSONL, SSE, MCP, and wire.

### MCP (engine-hosted)

Same HTTP server and lifecycle as the web UI. MCP is **on by default** when HTTP is on.

| Item | Value |
|------|--------|
| Endpoint | `POST {httpUrl}/mcp` (JSON-RPC 2.0; single object or batch) |
| Discovery | `GET {httpUrl}/mcp` |
| Live events | `GET {httpUrl}/mcp` with `Accept: text/event-stream` |
| Auth | `Authorization: Bearer <token>` (always required) |
| Protocol | Advertised `protocolVersion` `2024-11-05` (Streamable-HTTP baseline) |
| Server name | `jk-engine` |
| CLI | `jk engine status` shows **MCP**; JSON includes `mcpUrl` |

Disable MCP only: `[mcp] enabled = false` in `~/.config/jk/config.toml` (or
`JK_MCP_ENABLED=false`) — 404s `/mcp`; dashboard stays up; `mcpUrl` is `null`.
SSE budget: `[mcp] max-event-streams` / `JK_MCP_MAX_EVENT_STREAMS` (default **16**).

Results use MCP `structuredContent` plus a short `content` text summary. Tool JSON uses
`schema` + `type` like the rest of the machine model.

#### Tools

Bind once, then omit `dir` on later calls.

| Tool | Role |
|------|------|
| **`jk_bind`** | Set default workspace for later tools; returns a project card |
| **`jk_status`** | Engine vitals (pid, version, heap, active jobs) — same facts as `GET /api/status` |
| **`jk_project`** | Project card (coord, java, members, last run) |
| **`jk_run`** | Start a job: `build` \| `test` \| `lock` \| `update` \| `format` \| `native` \| `image` \| `assemble` \| `compile` \| `clean`. **`wait` defaults true**. Optional modules/tags/suites/`skip_tests`/`timeout_s`/`aot_cache` |
| **`jk_build`** / **`jk_test`** / **`jk_lock`** | Async convenience aliases (return `jid`/`requestId` immediately) |
| **`jk_job`** | `get` \| `wait` \| `cancel` a job; omit `jid` → latest live job for bound dir |
| **`jk_cancel`** | Cancel by **`jid`** (`requestId` alias) |
| **`jk_history`** | Recent runs as **summaries** (filters: dir, projectId, success, kind, limit, next). Avoid `view=full` |
| **`jk_diagnostics`** | Structured compiler/test failures (`last-fail` default, or a history id) |
| **`jk_why`** | Why a dependency is on the graph |
| **`jk_explain`** | Forecast next build: dirty modules and cache hits |
| **`jk_outdated`** | Declared deps newer than the lock (read-only) |
| **`jk_deps`** | Preview/apply surgical dependency add/remove (`apply` defaults false) |
| **`jk_workspace`** | Preview/apply workspace member add/remove |
| **`jk_manifest`** | Set whitelisted `jk.toml` keys (`java=N` is language level, not `jdk=`) |
| **`jk_config`** | Machine config get/set, or `apply_preset=ci` |
| **`jk_disk`** | Cache/store usage; `clean`/`nuke` require `confirm=true` |
| **`jk_jdk`** | List / install / uninstall JDKs (`confirm=true` for uninstall) |
| **`jk_doctor`** | Host health snapshot (config + disk) |

Do not dump full journal records — start with **`jk_diagnostics`** for failures.
`jk_history` lists every journaled kind; the Web UI Activity feed stays build-like.

Server instructions (also returned from `initialize`): bind first → diagnostics on failure →
`jk_run` to rebuild → `jk_why` / `jk_explain` for graph/ETA → `jk_job cancel` when stalled.

#### Resources

| URI | Contents |
|-----|----------|
| `jk://session` | Bound dir + engine status |
| `jk://project` | Project card (needs `jk_bind`) |
| `jk://runs/latest` | Latest history summary |
| `jk://disk` | Cache and store usage |
| `jk://config` | Effective machine config |

#### Prompts (catalog hints)

| Name | Intent |
|------|--------|
| `fix-failing-build` | `jk_diagnostics` → edit → `jk_run kind=build wait=true` |
| `recover-disk` | `jk_disk usage` then clean/nuke with confirm |
| `setup-ci` | `jk_config apply_preset=ci` |
| `upgrade-deps` | `jk_outdated` then `jk_run kind=lock` |
| `stall-or-cancel` | `jk_status` then `jk_job cancel` |

#### Live progress (MCP SSE)

`GET {httpUrl}/mcp` with `Accept: text/event-stream` and bearer token — Streamable-HTTP style.
Each frame is `event: message` with JSON-RPC `notifications/jk/event` and params matching engine
facts (`event` = dashboard type name plus the same fields as SSE `data`). Params also carry
aggregate **`progress`** (0–100 or `null`) aligned with CLI JSONL / `details.jsonl`.

**Filter one job** (recommended when the engine may run concurrent jobs):

| Query | Effect |
|-------|--------|
| `?requestId=N` | Only events whose payload has that id (from the tool result; same value as `jid`) |
| `?progressToken=T` | Same, after tools/call with `"_meta":{"progressToken":"T"}` binds T→job id |

Unfiltered `GET /mcp` still receives every job. Dashboard alias: **`GET /api/events`**
(includes dashboard chrome that MCP does not).

```bash
# Token: ~/.local/state/jk/…/http-token or the status URL fragment
# MCP_URL from: jk engine status  →  mcpUrl

# List tools
curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  "$MCP_URL"

# Bind + run (wait default true on jk_run)
curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"jk_bind","arguments":{"dir":"/path/to/project"}}}' \
  "$MCP_URL"

# Live progress for one job
curl -sSN -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  "$MCP_URL?requestId=$JID"
```

## Agent recipe (recommended)

```bash
# Multi-turn agents: MCP on the engine (jk engine status → mcpUrl + token)
#   1. jk_bind {dir}
#   2. failures → jk_diagnostics (not raw jk_history)
#   3. rebuild → jk_run kind=build wait=true
#   4. graph / ETA → jk_why / jk_explain
#   5. stalled → jk_status then jk_job action=cancel
# Live progress: GET /mcp?requestId=N Accept: text/event-stream

# One-shot CLI (no MCP):
jk test --output json --modules 'shared/*' 2>/dev/null
# or: JK_OUTPUT=jsonl jk build

# Offline / mid-run transcript:
#   ~/.local/state/jk/builds/projects/<key>/runs/<id>/details.jsonl
```

Do **not** set `TERM=dumb` and scrape wedges. Do **not** use verbose as the primary agent channel.

## Consistency checklist (for authors of new events)

When you add information (e.g. module on a test failure):

1. [ ] BuildPlan / engine model carries the fact  
2. [ ] `JsonlShape` / JSONL fields (**additive only** — keep `schema: 1` until 1.0)  
3. [ ] Web SSE payload fields (same names)  
4. [ ] Verbose / failure headline text (human projection)  
5. [ ] `details.jsonl` (same shape as stdout JSONL; additive `progress` rider from engine)  
6. [ ] MCP tool / resource / notification payloads (same names)  
7. [ ] This doc’s table row if a new **type** appears  
8. [ ] Whole-job % comes from engine `workspace-progress` / tracker — **no client re-aggregation**  

Pre-1.0: **no schema version bumps** across jk.toml, lock, wire, REST, SSE, MCP — see architecture.

## Cancel

| Knob | Default | Role |
|------|---------|------|
| `JK_CANCEL_GRACE_MS` | **500** | Shared wall-clock after signalling **all** workers; then force-kill leftovers |
| Env clamp max | **5000** | Safety only if someone sets a huge env value — not the default |
| Join after user cancel | grace + 500 ms | Connection thread abandons if runner still stuck |
| BuildPlan step cancel | 200 ms | In-process `Future.cancel` after cooperative flag |

Public cancel handle is **`jid`** (`requestId` alias). Entry points: Ctrl-C, `jk cancel` /
`jk cancel <jid>`, `POST /api/cancel`, MCP `jk_cancel` / `jk_job action=cancel`.

**Timeline (N workers):** `destroy()` all → wait ≤500 ms once → `destroyForcibly()` stragglers.  
Not N×500 ms. **Windows:** no SIGTERM; `destroy()` is often already terminal — grace bounds the
engine’s wait, not a guaranteed hook window. See [architecture.md](architecture.md).

## Refs

- CLI: `JsonlListener`, `JsonlShape`, `LiveProgress`, `BuildPlanConsole.Mode.JSON`, `EventLogListener`, `CliSessionTranscript`, `SessionMirrorListener`
- Engine HTTP: `HttpEngineServer`, `HttpEvents`, `McpHandler`
- HTTP / auth / lifetime: [http.md](http.md)
- Guide: [guide.md](guide.md) (CLI UX + machine output)
