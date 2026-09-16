# MCP

The resident engine hosts a **Model Context Protocol** server on the same HTTP listener as
the [web dashboard](web.md). MCP is **on by default** when HTTP is on.

Agent playbook (when to use which tool): [Agents](agents.md).
JumpKick system prompt: CLI `jk manual` / MCP **`jk_manual`** / resource **`jk://manual`**.
Fix a failing build: [Troubleshooting](troubleshooting.md).

## Connect

| Item | Value |
|------|--------|
| Endpoint | `POST {httpUrl}/mcp` (JSON-RPC 2.0; single object or batch) |
| Discovery | `GET {httpUrl}/mcp` |
| Live events | `GET {httpUrl}/mcp` with `Accept: text/event-stream` |
| Auth | `Authorization: Bearer <token>` (**always** required) |
| Session | `initialize` answers with an `Mcp-Session-Id` header; echo it on every later request. One id is one **session**: the runs it starts journal as `trigger: mcp · session: <clientInfo.name> <id>`. `DELETE /mcp` with the header ends it |
| Protocol | Advertised `protocolVersion` `2024-11-05` (Streamable-HTTP) |
| Server name | `jk-engine` |
| Tool list | `tools/list` answers the **loop set** plus **`jk_tools`** by default; `[mcp] tools = "all"` (or `JK_MCP_TOOLS=all`) lists every card. Either way every tool is callable |
| CLI | `jk engine status` shows **MCP**; JSON includes `mcpUrl` |

Token: `jk engine status` / `jk web` URL fragment, or the file under the state directory
(`~/.jk/state/engine/<key>.http-token`). Same token as the dashboard.

Register the engine once with an MCP client that speaks Streamable HTTP, passing the token as a
header. With Claude Code:

```bash
URL=$(jk engine status --output json | jq -r .mcpUrl)
TOKEN=$(cat ~/.jk/state/engine/*.http-token)
claude mcp add --transport http jk "$URL" --header "Authorization: Bearer $TOKEN"
```

Other clients take the same three facts (a Streamable-HTTP server, its URL, one bearer header) in
their own configuration file. The engine's port is stable across restarts unless `[http] port`
is `0`, so the registration outlives the engine process.

Disable MCP only: `[mcp] enabled = false` in `~/.jk/config.toml` (or
`JK_MCP_ENABLED=false`) — `/mcp` 404s; dashboard stays up; `mcpUrl` is `null`.

SSE budget: `[mcp] max-event-streams` / `JK_MCP_MAX_EVENT_STREAMS` (default **16**).

Results use MCP `structuredContent` plus a short `content` text summary. Tool JSON uses
`schema` + `type` like the rest of the machine model (`schema` stays **1** until 1.0).

A job result (`jk_run`, `jk_build`, `jk_test`, `jk_lock`) names who asked — `trigger: "mcp"`
and `session: "claude-code 3f9a"` when the client echoes its session id — and carries
**`dashboard`**: the authenticated project page (`{httpUrl}#project/<id>?t=<token>`) that
follows the newest run of that project, so the human supervising the agent can open it and
watch. Absent when HTTP is not serving. Same facts as `target/jk-results.md`: [Web](web.md).

## Tools

Every MCP tool card an agent's host shows the model is paid for on every turn, and the metric jk
is measured on is tokens to green. So the default `tools/list` is the **loop set** — the seven
tools one fix-and-rerun loop needs — plus **`jk_tools`**, which lists and calls the rest:

| Default list | |
|------|------|
| **`jk_run`** **`jk_results`** **`jk_diagnostics`** | run, read the report, read the structured failures |
| **`jk_deps`** **`jk_manifest`** | edit `jk.toml` (dependencies; `java = N`) |
| **`jk_manual`** **`jk_bind`** | the playbook; switch project dir |
| **`jk_tools`** | `action=list` → every other tool's name and one-liner; `action=call name=… arguments={…}` → call it |

Each default card is one sentence; the same sentences, and the arguments the cards leave out, are
in the playbook's **MCP tools** page (`jk manual` / `jk_manual`), which a test holds to the served
list byte for byte. Widen the list to every card with `[mcp] tools = "all"` in `~/.jk/config.toml`
(`JK_MCP_TOOLS=all`), then restart the engine; `loop` is the default.

**Binding.** `dir` is the project root. An unbound connection is bound by the **first call that
carries `dir`** — that one result says `bound <dir>` (text and `structuredContent.bound`) — and
later calls on that connection may omit it. **`jk_bind`** switches. The bind is per connection
(`Mcp-Session-Id`), so two agents on one engine never clobber each other; a client that sends no
session id has no bind of its own and falls back to the engine-wide one that every `jk_bind` also
sets. A call that names `dir` always targets that dir, bound or not.

The whole registry:

| Tool | Role |
|------|------|
| **`jk_manual`** | JumpKick playbook (markdown). Same as CLI `jk manual`. Resource: `jk://manual` |
| **`jk_bind`** | Set or switch the connection's project dir; returns a project card |
| **`jk_tools`** | `list` the tools outside the default list with one-liners, or `call` one by name |
| **`jk_status`** | Engine vitals (pid, version, heap, active jobs) |
| **`jk_project`** | Project card (coord, java, members, last run) |
| **`jk_run`** | Start a job: `build` (default) \| `test` \| `guard` \| `lock` \| `update` \| `format` \| `native` \| `image` \| `assemble` \| `compile` \| `clean` \| `publish` \| `install` \| `import`. **`wait` defaults true**. Publish is **always a dry-run**. Optional `modules`/`suites`/`include_tags`/`exclude_tags`/`skip_tests`/`timeout_s` (the card lists the first two; the playbook spells out the rest). `deadline_s` caps the job's wall time — the engine cancels it past that and the record says so; default is the engine's `detached-deadline-ms` (1 hour), `0` = none. `kind=test` defaults to the **unit** suite — do not pass every suite as a habit |
| **`jk_build`** / **`jk_test`** / **`jk_lock`** | Async convenience aliases (return `jid` immediately) |
| **`jk_job`** | `get` \| `wait` \| `cancel`; omit `jid` → latest live job for bound dir |
| **`jk_cancel`** | Cancel by **`jid`**, or every live job for a `dir` |
| **`jk_history`** | Recent runs as **summaries** (filters: dir, projectId, success, kind, limit, next). Avoid `view=full` |
| **`jk_diagnostics`** | Structured compiler/test failures (`last-fail` default, or a history id); `severity`, `module`, `unique`, `limit`, `next` |
| **`jk_results`** | High-level markdown (same as CLI `jk results` / `target/jk-results.md`); `delta` = what changed since this session's previous run (files, diagnostics, tests, wall) |
| **`jk_details`** | Budgeted tail of `details.jsonl` (default last-fail, `error` + `task-finish`, 80 events). CLI `jk results --details` dumps the full file |
| **`jk_why`** | Why a dependency is on the graph |
| **`jk_explain`** | Forecast next build |
| **`jk_affected_tests`** | WIP module cone + advisory ranked test classes; writes `target/jk-tests-affected.md` |
| **`jk_outdated`** | Declared deps newer than the lock (read-only; Current / Compatible / Latest) |
| **`jk_update`** | Bump declared pins in `jk.toml` to the newest stable on the same major, then relock. Params: `dir`, `deps` (optional list of handles), `major` (bool), `apply` (bool, default **false**). Preview returns the proposed `jk.toml` hunk without writing; `apply=true` writes and relocks. Same renderer as `jk add` |
| **`jk_deps`** | Preview/apply surgical dependency add/remove (`apply` defaults **false**) |
| **`jk_workspace`** | Preview/apply workspace member add/remove |
| **`jk_manifest`** | Set whitelisted `jk.toml` keys (`java=N` is language level, not `jdk=`) |
| **`jk_config`** | Machine config get/set, or `apply_preset=ci` |
| **`jk_disk`** | Cache/store usage; `clean`/`nuke` require `confirm=true` |
| **`jk_jdk`** | List / install / uninstall (`confirm=true` for uninstall) |
| **`jk_doctor`** | Host health snapshot |
| **`jk_new`** | Scaffold; `action=templates` lists ids; `preview=true` does not write |
| **`jk_publish`** | Validate the publish bundle — **always a dry-run** |
| **`jk_install`** | Install the project into the local Maven repo; `action=list` shows jkx tools (tool installs stay CLI-side) |
| **`jk_import`** | Import Maven/Gradle into `jk.toml` |
| **`jk_export`** | Write `maven` \| `gradle` \| `bom` files |
| **`jk_ide`** | Write IDE project files (`kind=idea` \| `vscode` \| `all`) plus `.bsp/jk.json`, same generators as `jk ide`; `preview=true` lists without writing |
| **`jk_graph`** | Compact module/dep graph (transitive expansion opt-in and budget-capped) |

Start with **`jk_results`** or **`jk_diagnostics`**. Do not dump full journal records. A tool
outside the default list is called as itself when the client knows the name, or through
`jk_tools action=call`; the two paths are one dispatcher, so the bind and the session are the same.

Token, loopback bind, and how to report a hole in that gate: [Security](security.md).

## Resources

| URI | Contents |
|-----|----------|
| `jk://manual` | JumpKick playbook (same as `jk_manual` / CLI `jk manual`) |
| `jk://session` | Bound dir + engine status |
| `jk://project` | Project card (needs an engine-wide bind: `jk_bind`) |
| `jk://runs/latest` | Latest history summary |
| `jk://runs/latest/results` | Latest `jk-results.md` (same as `jk_results`) |
| `jk://runs/latest/details` | Budgeted tail of latest `details.jsonl` (same as `jk_details`) |
| `jk://guards` | Guard catalog (same as `jk guard explain`); `jk://guards/<id>` is one rule's card (same as `jk guard explain <id>`). Needs an engine-wide bind (`jk_bind`); an unknown id is a `-32602` error naming the nearest ids |
| `jk://disk` | Cache and store usage |
| `jk://config` | Effective machine config |

## Prompts

| Name | Intent |
|------|--------|
| `learn-jumpkick` | `jk_manual` then follow that playbook (not Maven/Gradle) |
| `fix-failing-build` | `jk_results` → edit → `jk_run kind=build wait=true` |
| `recover-disk` | `jk_disk usage` then clean/nuke with confirm |
| `setup-ci` | `jk_config apply_preset=ci` |
| `upgrade-deps` | `jk_outdated` then `jk_run kind=lock` |
| `stall-or-cancel` | `jk_status` then `jk_job cancel` |

## Live progress

`GET {httpUrl}/mcp` with `Accept: text/event-stream` and bearer token. Each frame is
`event: message` with JSON-RPC `notifications/jk/event`. Params carry aggregate
**`progress`** (0–100 or `null`). Dashboard-only chrome is **not** sent on MCP SSE.

| Query | Effect |
|-------|--------|
| `?jid=N` | Only that job (from the tool result) |
| `?progressToken=T` | Same, after `tools/call` with `"_meta":{"progressToken":"T"}` |

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
