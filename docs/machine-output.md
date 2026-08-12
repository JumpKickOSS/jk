# Machine output, agents, and multi-client event model

**Status:** product stance + implementation map (2026-07-22)  
**Audience:** agents, CI, IDE plugins, MCP, maintainers

## Product stance

| Consumer | Default channel | Goal |
|----------|-----------------|------|
| **Human at a TTY** | Terse visual CLI (wedges, bars) | Eyeball success/failure; interesting, not noisy |
| **Human debugging** | `-v` / `--verbose` + files under `target/` | Scrollable prose; pointers to structure |
| **Agents / scripts / CI** | **`--output json` or `jsonl` (identical)** | **Live JSONL event stream** on stdout |
| **Web dashboard** | Engine HTTP + SSE (`/api/events`) | Same facts, SSE framing |
| **Future MCP** | Engine-hosted tools + resources | Same facts as tools/resources, not a new build model |

Humans never need to scrape the TUI. Agents never need to parse ANSI bars.

This model has fully shipped (JSONL stream, `details.jsonl` session log, aggregate `progress`
rider, MCP adapter, robust cancel).

## Unified information model

All machine surfaces should carry **the same conceptual events**. Framing differs:

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
| Plan / ETA | (wire → engine; extend JSONL) | `plan`, `eta` | explain / bar countdown | tools |
| Module (workspace) | `module-start` / `module-finish` (+ nested step JSONL) | `module-start` / `module-finish` | completion lines | same events on MCP SSE |
| Workspace end | `workspace-finish` | `request-finish` | summary chip | notification |

**Rule:** when adding a new fact (module coord, worker id, ETA), add it once to the **shared conceptual model**, then project into JSONL fields, SSE `data`, verbose text, and MCP tools — do not invent parallel schemas.

**Smart engine / dumb clients (JK-1120–1122):** workspace aggregate percent is computed **only** in the
engine (`WorkspaceProgressTracker`). Clients render `workspace-progress` and the additive
`progress` rider — they must **not** re-sum module-local `numerator`/`denominator` for the bar.
Future bar tuning lives in the engine alone.

### Canonical live stream: JSONL

```bash
jk build --output json …      # same as jsonl
jk test  --output jsonl …
export JK_OUTPUT=json         # or jsonl
```

- **One JSON object per line**, flushed promptly (live).
- Every object includes at least: `schema` (int), `ts` (epoch ms), `type` (string).
- Most lines also carry **`progress`**: aggregate workspace/plan percent **0–100** (or
  `null` until known) from the **engine** tracker — **not** `progress_num`/`progress_den`
  (JK-1117/1120). Per-task `numerator`/`denominator` on `progress` / `tick-update` events stay
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

### Session log: `details.jsonl` (JK-1116)

```text
~/.local/state/jk/builds/projects/<key>/runs/<run-id>/details.jsonl
```

- Default **on**; disable with `JK_CLI_DETAILS=off` (or `0`).
- **Same event shape** as `--output json`/`jsonl` (`JsonlShape`, `schema: 1`), one object per
  line, **appended live** (safe to `tail -F` mid-run). Ends with a `session-finish` line
  (`exit`, `duration_ms`, optional `wedge` / `modules`).
- Opens with `session-start` (`command`, `argv`). With `-v`, CLI prints `Details: <path>` at
  **open** (and again at finish).
- Supersedes the end-only `details.json` blob (JK-1079). Prefer this path for offline triage
  and support; agents watching a long build use the live file without waiting for process exit.

### Materialize cadence (JK-1118)

Live model updates on every meaningful event; sinks materialize under one policy:

| Rule | Trigger | TTY paint | JSONL append (`--jsonl` + `details.jsonl`) |
|------|---------|-----------|---------------------------------------------|
| **M1** | Phase finish (preflight stage / plan phase) | next frame | **append + flush** |
| **M2** | Test class finish (when wired) | optional | **append + flush** |
| **M3** | Module / plan / command finish | yes | **append + flush** |
| **M4** | Dirty heartbeat | **80 ms** TTY paint (`TTY_FRAME_MS`); wire samples at **`JK_WIRE_PROGRESS_MS`** (default **500 ms**) | **2 s** if dirty (`DISK_HEARTBEAT_MS`) |
| **M5** | Hot ticks (`progress` / `tick-update` / `label` / `output`) | model + next frame; **wire/SSE coalesce** to cadence (latest wins) | append line; flush ≤ M4 |

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

Engine hosts HTTP (loopback) with:

- `GET /api/status`, `GET /api/events` (SSE), `POST /api/build`, …
- SSE: `event: <type>` + `data: <json>` — types include `request-start`, `task-start`, `task-finish`, `plan-progress`, `eta`, `diagnostic`, module events, …

**Convergence (one conceptual model):** SSE `data`, MCP `notifications/jk/event` params, CLI
JSONL, and (additively) the client↔engine wire all carry the same facts where they describe the
same work:

| Field | Meaning |
|-------|---------|
| `schema` | Always `1` until jk 1.0 |
| `type` | Conceptual event name (`task-start`, `progress`, `error`, …) |
| `progress` | Aggregate % 0–100 or `null` (JK-1117/1119) — **not** `progress_num`/`progress_den` |
| `task` / `stage` / `status` / `dir` / `coord` | Same names across surfaces. `stage` is the task's `BuildStage` — a **closed** set (`resolve`, `generate`, `compile`, `test`, `package`, `native`, `image`, `other`), always present, never free-form. `phase` means only `InvocationPhase` and `workspace-progress` |
| `numerator` / `denominator` | Task-scoped weights (progress events); optional beside the % rider |
| `test` / `exceptionClass` | Structured failure fields |

The SSE *event* name may stay SPA-oriented (`plan-progress, `diagnostic`); agents should
prefer `data.type`. The client↔engine wire uses the same `type` discriminator (and additive
`schema` / `progress` on progress events) — one vocabulary across JSONL, SSE, MCP, and wire.

### MCP (engine-hosted, JK-1095)

Same HTTP server and lifecycle as the web UI:

| Item | Value |
|------|--------|
| Endpoint | `POST {httpUrl}/mcp` (JSON-RPC 2.0) |
| Discovery | `GET {httpUrl}/mcp` |
| Auth | `Authorization: Bearer <token>` (always required) |
| CLI | `jk engine status` shows **MCP**; JSON includes `mcpUrl` |

MCP can be disabled machine-wide with `[mcp] enabled = false` in `~/.config/jk/config.toml`
(404s `/mcp`; web dashboard unaffected — `mcpUrl` reports `null`).

**Tools:** `jk_status`, `jk_build`, `jk_test` (true test-only plans — no package), `jk_lock`
(async → `requestId`), `jk_cancel`, `jk_project`, `jk_history`.

**Live progress (MCP SSE):** `GET {httpUrl}/mcp` with `Accept: text/event-stream` and bearer
token — Streamable-HTTP style. Each frame is `event: message` with JSON-RPC
`notifications/jk/event` and params matching engine facts (`event` = dashboard type name plus the
same fields as SSE `data`). Params also carry aggregate **`progress`** (0–100 or `null`) aligned
with CLI JSONL / `details.jsonl` (JK-1119) — agents should not scrape TTY bars.

**Filter one job** (recommended when the engine may run concurrent jobs):

| Query | Effect |
|-------|--------|
| `?requestId=N` | Only events whose payload has that `requestId` (from the tool result) |
| `?progressToken=T` | Same, after tools/call with `"_meta":{"progressToken":"T"}` binds T→requestId |

Unfiltered `GET /mcp` still receives every job. Dashboard alias: **`GET /api/events`**. Tool JSON
uses `schema` + `type` like the rest of the machine model.

```bash
# Example: list tools (token from ~/.local/state/jk/…/http-token or status URL fragment)
curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  "$MCP_URL"

# Live progress for one job:
curl -sSN -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  "$MCP_URL?requestId=$REQUEST_ID"
```

## Agent recipe (recommended)

```bash
# Live, parseable, no TUI scrape:
jk test --output json --modules 'shared/*' 2>/dev/null
# or: JK_OUTPUT=jsonl jk build

# Multi-turn agents: MCP on the engine (jk engine status → MCP URL + token)
# Live build progress: GET /mcp?requestId=N Accept: text/event-stream  (or GET /api/events)

# Workspace build/test emit module-start / module-finish / workspace-* around step events.

# Exit code still meaningful (0 ok, non-zero fail).
# Parse stdout as JSONL; look for type=workspace-progress (whole-job %) or
# type=buildplan-finish / error / task-finish (fine detail).

# Offline / mid-run:
#   target/.jk-cli/<latest>/details.jsonl   # tail -F during the run
#   target/jk-chrome-profile.json
#   <cache>/runs/*.jsonl   # EventLogListener copy of the stream
```

Do **not** set `TERM=dumb` and scrape wedges. Do **not** use verbose as the primary agent channel.

## Consistency checklist (for authors of new events)

When you add information (e.g. module on a test failure):

1. [ ] BuildPlan / engine model carries the fact  
2. [ ] `JsonlShape` / JSONL fields (**additive only** — keep `schema: 1` until 1.0)  
3. [ ] Web SSE payload fields (same names)  
4. [ ] Verbose / failure headline text (human projection)  
5. [ ] `details.jsonl` (same shape as stdout JSONL; additive `progress` rider from engine)  
6. [ ] MCP tool payloads when MCP exists  
7. [ ] This doc’s table row if a new **type** appears  
8. [ ] Whole-job % comes from engine `workspace-progress` / tracker — **no client re-aggregation**  

Pre-1.0: **no schema version bumps** across jk.toml, lock, wire, REST, SSE, MCP — see architecture.

## Cancel (JK-1096)

| Knob | Default | Role |
|------|---------|------|
| `JK_CANCEL_GRACE_MS` | **500** | Shared wall-clock after signalling **all** workers; then force-kill leftovers |
| Env clamp max | **5000** | Safety only if someone sets a huge env value — not the default |
| Join after user cancel | grace + 500 ms | Connection thread abandons if runner still stuck |
| BuildPlan step cancel | 200 ms | In-process `Future.cancel` after cooperative flag |

**Timeline (N workers):** `destroy()` all → wait ≤500 ms once → `destroyForcibly()` stragglers.  
Not N×500 ms. **Windows:** no SIGTERM; `destroy()` is often already terminal — grace bounds the
engine’s wait, not a guaranteed hook window. See [architecture.md](architecture.md).

## Refs

- CLI: `JsonlListener`, `JsonlShape`, `LiveProgress`, `BuildPlanConsole.Mode.JSON`, `EventLogListener`, `CliSessionTranscript`, `SessionMirrorListener`
- Engine HTTP: `HttpEngineServer`, `HttpEvents`
- Guide: [guide.md](guide.md) (CLI UX + machine output)
- UX charter: kanartist JK-1076–1079
