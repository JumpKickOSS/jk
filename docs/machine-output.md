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
| 6 | Engine MCP adapter (thin, discoverable like web) | **Backlog [JK-1095](https://github.com/jkbuild/kanartist)** |
| 7 | Robust cancel (graceful → hard kill; never hang) | **Backlog [JK-1096](https://github.com/jkbuild/kanartist)** (P1) |

## Unified information model

All machine surfaces should carry **the same conceptual events**. Framing differs:

| Concept | CLI JSONL (`type`) | Web SSE (`event` + `data`) | Verbose (human) | MCP (future) |
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

**Convergence goal:** SSE `data` objects should use the same field names as JSONL where they describe the same thing (`step`, `phase`, `status`, `numerator`/`denominator`, `test`, `exceptionClass`, …). Full identity is a follow-up; do not fork new names without updating this doc.

### Future MCP (engine-hosted)

Like **web**:

- Bundled with the engine process (not a second build engine).
- Discoverable via `jk engine status` (URL or unix socket + token), same lifecycle as the dashboard.
- Tools wrap existing services: build, test, lock, status, cancel, get failures, open details/timeline.
- Tool results and streaming notifications **project the same event model** (JSONL types as notification payloads).

MCP is **P2** after agents can rely on `--output json` live streams.

## Agent recipe (recommended)

```bash
# Live, parseable, no TUI scrape:
jk test --output json --modules 'shared/*' 2>/dev/null
# or: JK_OUTPUT=jsonl jk build

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

## Cancel (related; separate ticket)

Ctrl-C / cancel must be **reliable and non-hanging**: cooperative window (sub-second for workers/plugins to flush), then forced kill. Never block forever. See kanartist ticket **JK-1096** (robust cancel). UX timeout must stay small so the terminal does not feel wedged.

## Refs

- CLI: `JsonlListener`, `JsonlShape`, `PipelineConsole.Mode.JSON`, `EventLogListener`, `CliSessionTranscript`
- Engine HTTP: `HttpEngineServer`, `HttpEvents`
- Guide: [guide.md](guide.md) (CLI UX + machine output)
- UX charter: kanartist JK-1076–1079
