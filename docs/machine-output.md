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

## Ordered delivery

| # | Work | Status |
|---|------|--------|
| 1 | Document this model (this file + guide) | **This doc** |
| 2 | `--output json` ≡ `jsonl` — live JSONL only (no single blob) | **Shipped** (`GlobalOptions` / `PipelineConsole`) |
| 3 | Versioned shared pipeline event shape (`JsonlShape`, `schema: 1`) | **Shipped** (CLI); align web/MCP over time |
| 4 | `details.json` remains post-hoc summary (same fields where applicable) | **Existing** (JK-1079); keep aligned |
| 5 | Always-on run log JSONL under cache (`EventLogListener`) | **Existing** — same shape as stdout JSONL |
| 6 | Engine MCP adapter (thin, discoverable like web) | **Done (JK-1095)** — `POST /mcp`, tools, status `mcpUrl` |
| 7 | Robust cancel (graceful → hard kill; never hang) | **Done (JK-1096)** — `JobWorkers.shutdownForRequest` + bounded cancel join |

## Unified information model

All machine surfaces should carry **the same conceptual events**. Framing differs:

| Concept | CLI JSONL (`type`) | Web SSE (`event` + `data`) | Verbose (human) | MCP (tools) |
|---------|--------------------|----------------------------|-----------------|--------------|
| Request / session start | `pipeline-start` (per pipeline) | `request-start` | `▶ pipeline (N steps)` | tool result / notification |
| Step start | `step-start` | `step-start` | `· phase/step (ticks: N)` | notification |
| Progress ticks | `progress`, `tick-update` | `pipeline-progress` | (bar / quiet) | optional stream |
| Label (current work) | `label` | (via progress / output) | last label on finish line | notification |
| User/compiler output | `output` | `output` | printed lines | resource / log |
| Warning | `warn` | (diagnostic-like) | bang line | notification |
| Error / test failure | `error` (+ `test`, `exceptionClass`) | `diagnostic` | FAILED lines / stacks | tool error + structured fields |
| Step end | `step-finish` | `step-finish` | `✓/✗ step took …` | notification |
| Pipeline end | `pipeline-finish` | module/request finish | wedge chip | tool result |
| Plan / ETA | (wire → engine; extend JSONL) | `plan`, `eta` | explain / bar countdown | tools |
| Module (workspace) | (workspace CLI path) | `module-start` / `module-finish` | completion lines | tools |

**Rule:** when adding a new fact (module coord, worker id, ETA), add it once to the **shared conceptual model**, then project into JSONL fields, SSE `data`, verbose text, and MCP tools — do not invent parallel schemas.

### Canonical live stream: JSONL

```bash
jk build --output json …      # same as jsonl
jk test  --output jsonl …
export JK_OUTPUT=json         # or jsonl
```

- **One JSON object per line**, flushed promptly (live).
- Every object includes at least: `schema` (int), `ts` (epoch ms), `type` (string).
- Current schema version: **`1`** (see `JsonlShape.SCHEMA` in the CLI).
- Terminal human chrome is **suppressed** in this mode so stdout stays parseable.

Example lines (illustrative):

```json
{"schema":1,"ts":1721664000123,"type":"pipeline-start","pipeline":"test","denominator":42,"steps":3}
{"schema":1,"ts":1721664000456,"type":"step-start","step":"run-tests","phase":"test","ticks":10}
{"schema":1,"ts":1721664000789,"type":"label","step":"run-tests","label":"cc.jumpkick:jk-core :: FooTest > bar()  [w2]"}
{"schema":1,"ts":1721664000901,"type":"error","step":"run-tests","code":"test-failure","message":"…","test":"cc.jumpkick:jk-core :: FooTest > bar()  [w2]","exceptionClass":"org.opentest4j.AssertionFailedError"}
{"schema":1,"ts":1721664001000,"type":"pipeline-finish","pipeline":"test","success":false,"duration_ms":880,"warnings":0,"errors":1}
```

Implementation: `clients/cli/.../JsonlListener` + `JsonlShape` (stdout); `EventLogListener` writes the **same shape** under the cache run log.

### Post-hoc summary: `details.json`

```text
target/.jk-cli/<utc-ts>/details.json
```

- Default **on**; disable with `JK_CLI_DETAILS=off`.
- Schema field `schema` (currently `1`): command, argv, exit, durations, modules, steps, errors, wedge.
- With `-v`, CLI prints `Details: <path>` after the run.
- Agents: prefer **live JSONL during the run**; use `details.json` for offline triage and support.

### Deep timing: chrome timeline

```text
target/jk-chrome-profile.json
```

Disable: `--no-timeline` / `JK_CHROME_PROFILE=off`. Linked from docs; not duplicated into every JSONL tick.

### Verbose (`-v`)

- Human-oriented step lines and full output.
- Must stay consistent with the **same facts** (module labels, failure module/worker, step names) but **not** become the agent API.
- Points at `details.json` when available.

### Web API / SSE

Engine hosts HTTP (loopback) with:

- `GET /api/status`, `GET /api/events` (SSE), `POST /api/build`, …
- SSE: `event: <type>` + `data: <json>` — types include `request-start`, `step-start`, `step-finish`, `pipeline-progress`, `eta`, `diagnostic`, module events, …

**Convergence:** SSE `data` objects carry `schema: 1` and a `type` field matching CLI JSONL where
they describe the same fact (`step-start`, `step-finish`, `progress`, `error`/`diagnostic`, plus
`step`, `phase`, `status`, `numerator`/`denominator`, `test`, `exceptionClass`). The SSE *event*
name may stay SPA-oriented (`pipeline-progress`, `diagnostic`); agents should prefer `data.type`.

### MCP (engine-hosted, JK-1095)

Same HTTP server and lifecycle as the web UI:

| Item | Value |
|------|--------|
| Endpoint | `POST {httpUrl}/mcp` (JSON-RPC 2.0) |
| Discovery | `GET {httpUrl}/mcp` |
| Auth | `Authorization: Bearer <token>` (always required) |
| CLI | `jk engine status` shows **MCP**; JSON includes `mcpUrl` |

**Tools:** `jk_status`, `jk_build`, `jk_test`, `jk_lock` (async → `requestId`), `jk_cancel`,
`jk_project`, `jk_history`. Live progress: **`GET /api/events`** (SSE). Tool JSON uses `schema` +
`type` like the rest of the machine model.

```bash
# Example: list tools (token from ~/.jk/state/…/http-token or status URL fragment)
curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  "$MCP_URL"
```

## Agent recipe (recommended)

```bash
# Live, parseable, no TUI scrape:
jk test --output json --modules 'shared/*' 2>/dev/null
# or: JK_OUTPUT=jsonl jk build

# Multi-turn agents: MCP on the engine (jk engine status → MCP URL + token)
# Live build progress: GET /api/events (SSE)

# Exit code still meaningful (0 ok, non-zero fail).
# Parse stdout as NDJSON; look for type=pipeline-finish / error / step-finish.

# Offline:
#   target/.jk-cli/<latest>/details.json
#   target/jk-chrome-profile.json
#   <cache>/runs/*.jsonl   # EventLogListener copy of the stream
```

Do **not** set `TERM=dumb` and scrape wedges. Do **not** use verbose as the primary agent channel.

## Consistency checklist (for authors of new events)

When you add information (e.g. module on a test failure):

1. [ ] Pipeline / engine model carries the fact  
2. [ ] `JsonlShape` / JSONL fields (schema bump if breaking)  
3. [ ] Web SSE payload fields (same names)  
4. [ ] Verbose / failure headline text (human projection)  
5. [ ] `details.json` if it is a post-hoc summary field  
6. [ ] MCP tool schema when MCP exists  
7. [ ] This doc’s table row if a new **type** appears  

## Cancel (JK-1096)

| Knob | Default | Role |
|------|---------|------|
| `JK_CANCEL_GRACE_MS` | **500** | Shared wall-clock after signalling **all** workers; then force-kill leftovers |
| Env clamp max | **5000** | Safety only if someone sets a huge env value — not the default |
| Join after user cancel | grace + 500 ms | Connection thread abandons if runner still stuck |
| Pipeline step cancel | 200 ms | In-process `Future.cancel` after cooperative flag |

**Timeline (N workers):** `destroy()` all → wait ≤500 ms once → `destroyForcibly()` stragglers.  
Not N×500 ms. **Windows:** no SIGTERM; `destroy()` is often already terminal — grace bounds the
engine’s wait, not a guaranteed hook window. See [architecture.md](architecture.md).

## Refs

- CLI: `JsonlListener`, `JsonlShape`, `PipelineConsole.Mode.JSON`, `EventLogListener`, `CliSessionTranscript`
- Engine HTTP: `HttpEngineServer`, `HttpEvents`
- Guide: [guide.md](guide.md) (CLI UX + machine output)
- UX charter: kanartist JK-1076–1079
