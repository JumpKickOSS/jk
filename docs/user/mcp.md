# MCP

The resident engine hosts a **Model Context Protocol** server on the same HTTP listener as
the [web dashboard](web.md). MCP is **on by default** when HTTP is on.

Agent playbook (when to use which tool): [Agents](agents.md).
JumpKick skill: CLI `jk skill` / MCP **`skill`** / resource **`jk://skill`**.
Fix a failing build: [Troubleshooting](troubleshooting.md).

## Connect

**Per project (preferred): `jk mcp`.** Register jk as a stdio server whose command is `jk mcp`.
MCP clients start a stdio server in the project they are working on; `jk mcp` finds the project
root from its working directory (the workspace root for a member), starts the engine if needed,
and forwards to it. The connection is pinned to that project, so the tools take no `dir`, the
instructions do not mention it, and no token goes in the client's config.

```bash
claude mcp add jk -- jk mcp                  # Claude Code
codex mcp add jk -- jk mcp                   # Codex
```

Or in a project's `.mcp.json`: `{"mcpServers": {"jk": {"command": "jk", "args": ["mcp"]}}}`.
Outside a project `jk mcp` still serves, and the tools take `dir` as over HTTP.

**Over HTTP**, for a client registered once for every project:

| Item | Value |
|------|--------|
| Endpoint | `POST {httpUrl}/mcp` (JSON-RPC 2.0; single object or batch) |
| Discovery | `GET {httpUrl}/mcp` |
| Live events | `GET {httpUrl}/mcp` with `Accept: text/event-stream` |
| Auth | `Authorization: Bearer <token>` (**always** required) |
| Session | `initialize` answers with an `Mcp-Session-Id` header; echo it on every later request. One id is one **session**: the runs it starts journal as `trigger: mcp · session: <clientInfo.name> <id>`. `DELETE /mcp` with the header ends it |
| Protocol | Advertised `protocolVersion` `2024-11-05` (Streamable-HTTP) |
| Server name | `jk-engine` |
| Tool list | `tools/list` answers five tools (`run`, `diagnostics`, `deps`, `why`, `skill`). `{"extended": true}` on that call, or `[mcp] tools = "all"` (`JK_MCP_TOOLS=all`), lists every card. Every tool is callable by name either way |
| CLI | `jk engine status` shows **MCP**; JSON includes `mcpUrl` |

Token: `jk engine status` / `jk web` URL fragment, or the file under the state directory
(`~/.jk/state/engine/<key>.http-token`). Same token as the dashboard.

Register the engine once with an MCP client that speaks Streamable HTTP. The three facts are the
URL, the bearer header, and HTTP transport:

```bash
jk engine status --output json   # mcpUrl
# token file: ~/.jk/state/engine/<key>.http-token
```

The engine's port is stable across restarts unless `[http] port` is `0`, so the registration
outlives the engine process.

Disable MCP only: `[mcp] enabled = false` in `~/.jk/config.toml` (or
`JK_MCP_ENABLED=false`) — `/mcp` 404s; dashboard stays up; `mcpUrl` is `null`.

SSE budget: `[mcp] max-event-streams` / `JK_MCP_MAX_EVENT_STREAMS` (default **16**).

The five loop tools (`run`, `diagnostics`, `deps`, `why`, `skill`) answer `content` text only;
for `run` and `diagnostics` that text **is** the verdict (below). They send no
`structuredContent`, because hosts that get one show the model that instead of the text. A
failure the agent must act on (a bad `deps` coord, a failed relock) sets `isError`. The extended
tools answer `structuredContent` (`schema` + `type`, like the rest of the machine model;
`schema` stays **1** until 1.0) with a one-line text summary.

`run` runs the tests (`kind` defaults to `test`), waits, and the reply is the run. `run=<id>` (no `kind`) reads an earlier verdict.
It does not carry a dashboard link, a session id, or a trigger. The journal still records who
asked; the human report is `target/jk-results.md` and [Web](web.md). `details` and
`jk://runs/latest/*` answer for the bound `dir`, never a sibling worktree's run.

## Verdict

An OK run is one line. A failure adds one line per problem, capped at five, then
`+K more: jk results --all | diagnostics(file=<path>)`: the shell command, then this tool. With no
`file`, `diagnostics` returns every problem with its source lines (the same text as `jk results
--all`); with `file`, that file's.

```
OK test rest-service · 2 tests · 500ms
```

```
FAIL build rest-service · 1 error · 700ms
E src/main/java/com/example/restservice/RestServiceApplication.java:3:50 ';' expected
  3|	public class RestServiceApplication {
```

```
FAIL test rest-service · 1 of 2 failed · 1.2s
T com.example.restservice.GreetingControllerTests#noParamGreetingShouldReturnDefaultMessage
  expected: "Hello, World!" but was: "Hello, Wrld!"
  at src/test/java/com/example/restservice/GreetingControllerTests.java:44
  44|	        assertThat(body).isEqualTo("Hello, World!");
```

A failure with no failed step, test or source error carries its reason on the headline, after
` — `: the run-level error that decided it, or else the warning the run left.

```
FAIL test jk · 9.5s — --class named 1 class the tag filter excluded: com.example.FormatThreadsBenchTest [bench]; pass --include-tags bench (or a --profile that includes it) to run it
```

Paths are relative to the project directory. A quoted source row is its line number, `|`, a
tab, then the line exactly as the file has it, indentation included: everything after the first
tab is an Edit's `old_string`. When that line occurs more than once in the file, the rows on
either side come with it so the quote is unique. `wait=false` answers `RUNNING build jid=42` and
`run(jid=42)`. A wait that expires answers `TIMEOUT build jid=42` with the same continuation:
`run(jid=42)` waits on that job again, starts nothing, and returns the verdict when it finishes.

## Waits and keep-alive

`run` waits up to `timeout_s` (default **240**, at most 3600), then answers `TIMEOUT` and the job
keeps going. The default sits under the shortest tool-call timeout among common MCP clients
(some stop a call after 300 s whatever it reports), so a default wait always answers before the
client gives up. A client with a longer timeout can pass a larger `timeout_s`.

When a `tools/call` carries `_meta.progressToken`, `jk mcp` follows that job's progress and writes
`notifications/progress` for the token while the call is outstanding: the step, the running test
and the percent (`progress` of `total` 100), at least every 5 s, with the last known state when
the engine is quiet. MCP clients that reset their timeout on progress (the spec allows it) keep a
long wait alive; the rest still get the `TIMEOUT` continuation. Over HTTP, read the same facts
from the event stream (below).

The CLI prints the same text with `--agent` or `JK_AGENT=1`, and when stdout is not a terminal
and the process was spawned by a coding-agent CLI. Human terminals keep today's output.
`--output json` stays the live event stream.

## Tools

Every tool card an agent's host shows the model is paid for on every turn. The default
`tools/list` is five tools. The same sentences are the skill's tool table (`jk skill` / `skill`),
which a test holds to the served list. Ask for every card with `tools/list` params
`{"extended": true}`, or set `[mcp] tools = "all"` (`JK_MCP_TOOLS=all`) and restart the engine.

**Binding.** `dir` is the project root. A `jk mcp` connection is pinned to its project and has no
`dir` (the `Jk-Project` header it sends does the same for any HTTP client). An unbound connection is bound by the **first call that
carries `dir`**, and later calls on that connection may omit it. The reply is the tool's own
result; it does not announce the bind. **`bind`** switches. The bind is the connection's
alone (`Mcp-Session-Id`): two agents on one engine each keep their own, every tool and `jk://`
resource answers for the caller's, and there is no engine-wide default one agent could re-target
for another. A client that sends no session id has no bind — it passes `dir` on each call, and
`bind` tells it so. A call that names `dir` always targets that dir, bound or not.

The whole registry:

| Tool | Role |
|------|------|
| **`skill`** | JumpKick skill (markdown). Same as CLI `jk skill`. Resource: `jk://skill`. `topic` loads one page |
| **`bind`** | Set or switch the connection's project dir; returns a project card. Not on the default list: the first call that carries `dir` binds |
| **`status`** | Engine vitals (pid, version, heap) and the `jobs` array — every live and queued job as the same row `jk engine status --output json` and `GET /api/status` carry (`jid`, `kind`, `dir`, `state`, `since`, `workers`, `lastEventAt`, `ahead`) |
| **`project`** | Project card (coord, java, members, last run) |
| **`run`** | Start a job and, by default, wait. The reply is the verdict (see above). `jid` waits on a job already started instead. `kind` is `test` (default) \| `build` \| `guard` \| `lock` \| `update` \| `format` \| `native` \| `image` \| `assemble` \| `compile` \| `clean` \| `publish` \| `install` \| `import`. Publish is **always a dry-run**. `only` limits modules. `run=<id>` with no `kind` reads an earlier verdict. Optional `modules`/`suites`/`include_tags`/`exclude_tags`/`skip_tests`/`timeout_s` (default 240). `deadline_s` caps the job's wall time; default is the engine's `detached-deadline-ms` (1 hour), `0` = none. `kind=test` defaults to the **unit** suite — do not pass every suite as a habit |
| **`build`** / **`test`** / **`lock`** | Async convenience aliases (return `jid` immediately) |
| **`cancel`** | Cancel by **`jid`**, or every live job for a `dir` |
| **`history`** | Recent runs as **summaries** (filters: dir, projectId, success, kind, limit, next). Avoid `view=full` |
| **`diagnostics`** | Problems past the verdict cap, or one file with full snippets (`file`) |
| **`details`** | Budgeted tail of `details.jsonl` (default last-fail, `error` + `task-finish`, 80 events). CLI `jk results --details` dumps the full file |
| **`why`** | The dependency path and the rule that picked the version, at most five lines (`coord`) |
| **`explain`** | Forecast next build |
| **`affected_tests`** | WIP module cone + advisory ranked test classes; writes `target/jk-tests-affected.md` |
| **`outdated`** | Declared deps an update would move (read-only; Current / Compatible / Latest); `all=true` lists every row, `checked` counts them, `file` is the `target/jk-outdated-dependencies.md` it wrote |
| **`update`** | Bump declared pins in `jk.toml` to the newest stable on the same major, then relock. Params: `dir`, `deps` (optional list of handles), `major` (bool), `apply` (bool, default **false**). Preview returns the proposed `jk.toml` hunk without writing; `apply=true` writes, relocks, and lists every lock package the relock added, removed or moved under `lock.changes` (`coordinate`, `from`, `to`; `from`/`to` null when added/removed), with the count in `lock.updated`. Same renderer as `jk add` |
| **`deps`** | Add, remove, or pin coordinates and relock. `preview=true` does not write; a version or coordinate the repositories do not serve is an error, preview or not |
| **`workspace`** | Preview/apply workspace member add/remove |
| **`manifest`** | Set whitelisted `jk.toml` keys (`java=N` is language level, not `jdk=`) |
| **`config`** | Machine config get/set, or `apply_preset=ci` |
| **`disk`** | Cache/store usage; `clean`/`nuke` require `confirm=true` |
| **`jdk`** | List / install / uninstall (`confirm=true` for uninstall) |
| **`doctor`** | Host health snapshot |
| **`new`** | Scaffold; `action=templates` lists ids; `preview=true` does not write |
| **`publish`** | Validate the publish bundle — **always a dry-run** |
| **`install`** | Install the project into the local Maven repo; `action=list` shows jkx tools (tool installs stay CLI-side) |
| **`import`** | Import Maven/Gradle into `jk.toml` |
| **`export`** | Write `maven` \| `gradle` \| `bom` files |
| **`ide`** | Write IDE project files (`kind=idea` \| `vscode` \| `all`) plus `.bsp/jk.json`, same generators as `jk ide`; `preview=true` lists without writing |
| **`graph`** | Compact module/dep graph (transitive expansion opt-in and budget-capped) |

Start with **`run`**. The reply is the verdict. **`diagnostics`** is the detail past its cap. Do not dump full journal records. A tool
outside the default list is called by name once `tools/list` with `extended: true` has named it. `tools/call` accepts it either way; the bind and the session are the same.

Token, loopback bind, and how to report a hole in that gate: [Security](security.md).

## Resources

| URI | Contents |
|-----|----------|
| `jk://skill` | JumpKick skill (same as `skill` / CLI `jk skill`). `jk://skill/<topic>` is one topic |
| `jk://session` | Bound dir + engine status |
| `jk://project` | Project card for the connection's bound dir (`bind` first) |
| `jk://runs/latest` | Latest history summary |
| `jk://runs/latest/results` | Latest verdict (same text as `run`) |
| `jk://runs/latest/details` | Budgeted tail of latest `details.jsonl` (same as `details`) |
| `jk://guards` | Guard catalog (same as `jk guard explain`); `jk://guards/<id>` is one rule's card (same as `jk guard explain <id>`). Reads the connection's bound dir (`bind` first); an unknown id is a `-32602` error naming the nearest ids |
| `jk://disk` | Cache and store usage |
| `jk://config` | Effective machine config |

## Prompts

| Name | Intent |
|------|--------|
| `learn-jumpkick` | `skill` then follow that playbook (not Maven/Gradle) |
| `fix-failing-build` | `run kind=build` (the reply is the verdict) then edit then `run` again |
| `recover-disk` | `disk usage` then clean/nuke with confirm |
| `setup-ci` | `config apply_preset=ci` |
| `upgrade-deps` | `outdated`, read its `file`, then `update` (preview), then `update apply=true` |
| `stall-or-cancel` | `status` then `cancel` |

## Live progress

`GET {httpUrl}/mcp` with `Accept: text/event-stream` and bearer token. Each frame is
`event: message` with JSON-RPC `notifications/jk/event`. Params carry aggregate
**`progress`** (0–100 or `null`). Dashboard-only chrome is **not** sent on MCP SSE.

| Query | Effect |
|-------|--------|
| `?jid=N` | Only that job (from the tool result) |
| `?progressToken=T` | The job a `tools/call` with `"_meta":{"progressToken":"T"}` runs or waits on; the stream may open before that call |

Unfiltered `GET /mcp` still receives every job.

```bash
# MCP_URL and TOKEN from: jk engine status

curl -sS -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  "$MCP_URL"

curl -sSN -H "Authorization: Bearer $TOKEN" -H 'Accept: text/event-stream' \
  "$MCP_URL?jid=$JID"
```

## Related

[Engine](engine.md) · [Machine output](machine-output.md) · [Web](web.md)
