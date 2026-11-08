# MCP

The resident engine hosts a **Model Context Protocol** server on the same HTTP listener as
the [web dashboard](web.md). MCP is **on by default** when HTTP is on.

Agent playbook (when to use which tool): [Agents](agents.md).
JumpKick skill: CLI `jk skill` / MCP **`skill`** / resource **`jk://skill`**.
Fix a failing build: [Troubleshooting](troubleshooting.md).

## Connect

**Claude Code, Cursor, VS Code, Gemini CLI and opencode need nothing by hand:** `jk new`, `jk init`,
`jk import` and `jk agents` write the registration for the ones in use
([Agents](agents.md#wiring-a-project-for-agents)). Any other client registers jk once, below.

**Per project (preferred): `jk mcp`.** Register jk as a stdio server whose command is `jk mcp`.
MCP clients start a stdio server in the project they are working on; `jk mcp` finds the project
root from its working directory (the workspace root for a member), starts the engine if needed,
and forwards to it. The connection is pinned to that project, so the tools take no `dir`, the
instructions do not mention it, and no token goes in the client's config.

```bash
claude mcp add jk -- jk mcp                  # Claude Code
codex mcp add jk -- jk mcp                   # Codex
```

Other clients take the same command, `jk mcp`, in their own MCP settings.

Or in a project's `.mcp.json`: `{"mcpServers": {"jk": {"command": "jk", "args": ["mcp"]}}}`.
Outside a project `jk mcp` still serves, and the tools take `dir` as over HTTP.

**Over HTTP**, for a client registered once for every project:

| Item | Value |
|------|--------|
| Endpoint | `POST {httpUrl}/mcp` (JSON-RPC 2.0; single object or batch) |
| Discovery | `GET {httpUrl}/mcp` |
| Live events | `GET {httpUrl}/mcp` with `Accept: text/event-stream` |
| Auth | `Authorization: Bearer <token>` (**always** required) |
| Session | `initialize` answers with an `Mcp-Session-Id` header; echo it on every later request. One id is one **session**: the runs it starts journal as `trigger: mcp · session: <clientInfo.name> <id>`. `DELETE /mcp` with the header ends it, and so does the [session expiry](#session-expiry) (a day with no activity, by default). An id the engine no longer knows (its session ended or was pruned) is a `404`, and the client sends `initialize` again for a new session |
| Protocol | `2025-11-25`, `2025-06-18`, `2025-03-26` or `2024-11-05`, negotiated at `initialize` ([below](#protocol-revisions)); Streamable HTTP. Send `MCP-Protocol-Version` on every later request |
| Server name | `jk-engine` |
| Tool list | `tools/list` answers six tools (`run`, `diagnostics`, `deps`, `callers`, `rename`, `skill`); `run(kind=snapshot)` installs for a sibling project. `{"extended": true}` on that call, or `[mcp] tools = "all"` (`JK_MCP_TOOLS=all`), lists every card. Every tool is callable by name either way |
| CLI | `jk engine status` shows **MCP** and a **Sessions** row (`3 loaded of 256, 1 active`); JSON includes `mcpUrl`, `mcpMaxSessions`, `mcpLoadedConnections` and `mcpActiveConnections` |

Token: `jk engine status` / `jk web` URL fragment, or the file under the state directory
(`~/.jk/state/engine/<key>.http-token`). Same token as the dashboard.

Register the engine once with an MCP client that speaks Streamable HTTP. The three facts are the
URL, the bearer header, and HTTP transport:

```bash
jk engine status -O jsonl   # mcpUrl
# token file: ~/.jk/state/engine/<key>.http-token
```

The engine's port is stable across restarts unless `[http] port` is `0`, so the registration
outlives the engine process.

Disable MCP only: `[mcp] enabled = false` in `~/.jk/config.toml` (or
`JK_MCP_ENABLED=false`) — `/mcp` 404s; dashboard stays up; `mcpUrl` is `null`.

SSE budget: `[mcp] max-event-streams` / `JK_MCP_MAX_EVENT_STREAMS` (default **16**).

Session limit: `[mcp] max-sessions` / `JK_MCP_MAX_SESSIONS` (default **256**) MCP connections stay
loaded in the engine. Past it, the engine sets aside the connection that has been quiet the
longest (no call outstanding, no request for 30 seconds); its client notices nothing, and the
connection comes back with the same session on its next request. Only when every connection is
busy does a new `initialize` get `503` with `Retry-After` and an error naming the limit (the
**Sessions** row of `jk engine status` and the dashboard's **MCP sessions** tile say how close it is): close idle
agents, or raise `max-sessions` and restart the engine. Calls on connections already open are
never refused.

### Session expiry

Most clients never send `DELETE /mcp`. A session with no activity (no call, no run) for `[mcp]
session-expiry` / `JK_MCP_SESSION_EXPIRY` (default **24h**; `"90m"`, `"7d"`, `"3600s"` or a number
of seconds) ends, and the engine forgets its connection, whether the client still holds it or the
session came over from a previous engine. The expiry governs every session, not only MCP ones, so
it holds with the HTTP server off. A client that comes back with the old `Mcp-Session-Id`
gets the `404` and sends `initialize` for a new session; `jk mcp` does that for the host. Ten
minutes of quiet only marks a session idle and starts a new episode on its next call.

The loop tools (`run`, `diagnostics`, `deps`, `callers`, `rename`, `skill`) and `why` answer `content` text only;
for `run` and `diagnostics` that text **is** the verdict (below). They send no
`structuredContent`, because hosts that get one show the model that instead of the text. A
failure the agent must act on (a bad `deps` coord, a failed relock) sets `isError`. The extended
tools answer `structuredContent` (`schema` + `type`, like the rest of the machine model;
`schema` stays **1** until 1.0) with a one-line text summary.

`run` runs the tests (`kind` defaults to `test`), waits, and the reply is the run. `run=<id>` (no `kind`) reads an earlier verdict.
It does not carry a dashboard link, a session id, or a trigger. The journal still records who
asked; the human report is `target/jk-results.md` and [Web](web.md). `details` and
`jk://runs/latest/*` answer for the bound `dir`, never a sibling worktree's run.

## Protocol revisions

`initialize` answers the `protocolVersion` the client asked for when jk speaks it, and
`2025-11-25` otherwise. The session keeps the revision it negotiated:

| Revision | What changes for the session |
|---|---|
| `2024-11-05`, `2025-03-26` | JSON-RPC batches are accepted. jk never asks the client anything |
| `2025-06-18` | No batches (a batch is a `-32600` error). Form-mode elicitation when the client declares `elicitation` |
| `2025-11-25` | As `2025-06-18`, plus URL-mode elicitation when the client declares `elicitation.url`. `"elicitation": {}` means form mode alone |

Over HTTP, `MCP-Protocol-Version` names the session's revision; a value jk does not speak is a
`400`. A request with neither the header nor a session is taken as `2025-03-26`. The handshake and
the default `tools/list` are the same under every revision.

**Elicitation.** A tool may ask the human behind the client a question while its call is
outstanding, and only of a client that declared that mode ([`new`](#new) asks for a template's
missing answers). Over
HTTP, when the call's `POST` sent `Accept: text/event-stream`, its response becomes an event
stream: the `elicitation/create` request comes first, and the call's own response is the last
event. The client answers the request in a new `POST` on the same session, which is accepted with
`202`. A question left unanswered past the tool's wait is withdrawn with `notifications/cancelled`.
`jk mcp` carries both directions over stdio: the request is written to stdout while the call is
outstanding, and the client's response on stdin goes back to the engine. An engine that replaced
the one the session opened on answers the same session. When the engine answers `404` instead (the
session is gone), `jk mcp` repeats the client's `initialize` for a new session, so it still knows
what the client declared, and sends the refused request once more; the client sees only that
answer.

## Verdict

An OK run is one line, plus a `W` line for each notice the run gave: a login whose secret the keychain
did not give up, so a request went without it, is a `W` line on a green run as on a red one. A failure adds one line per problem, capped at five, then
`+K more: jk results -A | diagnostics(file=<path>)`: the shell command, then this tool. With no
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
FAIL test jk · 9.5s — --class named 1 class the tag filter excluded: com.example.FormatThreadsBenchTest [bench]; pass --include-tags bench to run it; -p bench runs it
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

`jk mcp` handles requests concurrently: while a `run` waits, a `ping`, a `status` or a second call
answers at once. A `notifications/cancelled` naming an outstanding `run` cancels its job, and the
call answers with the cancelled verdict. Over HTTP, send the cancel with the call's
`_meta.progressToken` in its own `params._meta`, or call the `cancel` tool with the `jid`.

The CLI prints the same text with `--agent` or `JK_AGENT=1`, and when stdout is not a terminal
and the process was spawned by a coding-agent CLI. Human terminals keep today's output.
`-O jsonl` stays the live event stream.

## Tools

Every tool card an agent's host shows the model is paid for on every turn. The default
`tools/list` is six tools: the fix-and-rerun loop, `callers`, the one graph question measured to save an agent tokens, and `rename`, which makes in one call the edits a rename otherwise takes a turn each for. The same sentences are the skill's tool table (`jk skill` / `skill`),
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
| **`status`** | Engine vitals (pid, version, heap, the MCP session counts `mcpMaxSessions`, `mcpLoadedConnections`, `mcpActiveConnections`) and the `jobs` array — every live and queued job as the same row `jk engine status -O jsonl` and `GET /api/status` carry (`jid`, `kind`, `dir`, `state`, `since`, `workers`, `lastEventAt`, `ahead`) |
| **`project`** | Project card (coord, java, members, last run) |
| **`run`** | Start a job and, by default, wait. The reply is the verdict (see above). [`goal`](#goal) names the task. `jid` waits on a job already started instead. `decision=<id>` continues the call a [`NEEDS-YOU`](#decisions) answered. `kind` is `test` (default) \| `build` \| `guard` \| `lock` \| `update` \| `format` \| `native` \| `image` \| `assemble` \| `compile` \| `clean` \| `publish` \| `install` \| `snapshot` \| `import`. Publish is a dry-run unless `upload=true`, which sends the release to the repository `[publish]` names (see `publish`). `only` limits modules and `classes` test classes (comma-separated, as `jk test -S`). `run=<id>` with no `kind` reads an earlier verdict, or waits when `<id>` is a running job's `jid`. Optional `modules`/`suites`/`include_tags`/`exclude_tags`/`skip_tests`/`timeout_s` (default 240). `deadline_s` caps the job's wall time; default is the engine's `detached-deadline-ms` (1 hour), `0` = none. `kind=test` defaults to the **unit** suite — do not pass every suite as a habit |
| **`build`** / **`test`** / **`lock`** | Async convenience aliases (return `jid` immediately) |
| **`cancel`** | Cancel by **`jid`**, or every live job for a `dir` |
| **`history`** | Recent runs as **summaries** (filters: dir, projectId, success, kind, limit, next). Avoid `view=full` |
| **`diagnostics`** | Problems past the verdict cap, or one file with full snippets (`file`) |
| **`details`** | Budgeted tail of `details.jsonl` (default last-fail, `error` + `task-finish`, 80 events). CLI `jk results -d` dumps the full file |
| **`why`** | The dependency path and the rule that picked the version, at most five lines (`coord`) |
| **`explain`** | Forecast next build |
| **`affected_tests`** | WIP module cone + advisory ranked test classes; writes `target/jk-tests-affected.md` |
| **`outdated`** | Declared pins an update would move (read-only; Current / Compatible / Latest): dependencies, language and tool keys, rule packs; `all=true` lists every row, `checked` counts them, `file` is the `target/jk-outdated-dependencies.md` it wrote, and a row whose coordinate moved carries `relocated` |
| **`update`** | Bump declared pins in `jk.toml` to the newest stable on the same major, then relock. Params: `dir`, [`goal`](#goal), `deps` (optional list of handles), `major` (bool), `apply` (bool, default **false**). Preview returns the proposed `jk.toml` hunk without writing; `apply=true` writes, relocks, and lists every lock package the relock added, removed or moved under `lock.changes` (`coordinate`, `from`, `to`; `from`/`to` null when added/removed), with the count in `lock.updated`. Same renderer as `jk add`. Every move on a pin's major applies, across a minor and with any package the relock adds, and nobody is asked; without `major`, each newer major is listed under `available` (`handle from → to`), not applied |
| **`deps`** | Add, remove, or pin coordinates and relock, with a [`goal`](#goal). `preview=true` does not write; a version or coordinate the repositories do not serve is an error, preview or not. An add whose lock trips the project's [deny policy](publish.md#deny-policy) fails naming each rule it trips, and `jk.toml` and `jk-lock.toml` are left as they were |
| **`workspace`** | Preview/apply workspace member add/remove |
| **`manifest`** | Set whitelisted `jk.toml` keys (`java=N` is language level, not `jdk=`) |
| **`config`** | Machine config get/set, or `apply_preset=ci` |
| **`disk`** | Cache/store usage; `clean`/`nuke`/`m2-clean` require `confirm=true`, and a host that can ask the user [asks](#decisions) |
| **`jdk`** | List / install / uninstall (`confirm=true` for uninstall, and a host that can ask [asks](#decisions)) |
| **`node`** | Node.js: `list` (installs, jk's and other managers'), `list-remote` (`lts`, `major`), `install` (`spec`), `uninstall` (`spec` = exact version, `confirm=true`, and a host that can ask [asks](#decisions)), `which` (`dir`: the project's Node.js), `pin` (`dir`, optional `spec`: writes `jk.toml` and relocks) |
| **`doctor`** | Host health snapshot |
| **`new`** | Scaffold from a template, as `jk new` does (default `app`): `action=templates` lists templates, `action=form` one template's fields, `params` answers its questions, `preview=true` does not write ([below](#new)) |
| **`publish`** | Validate the publish bundle (dry-run). `upload=true` uploads it to the repository `[publish] repository` names, with the credential `jk repo login` / `JK_REPO_<ID>_*` holds and signing from `[publish]` (`JK_GPG_PASSPHRASE`). An agent never names a URL; a project with no `[publish] repository`, or with `central`, is refused with the fix — Central uploads stay on `jk publish` |
| **`snapshot`** | The inner-loop install, used as a Maven `-SNAPSHOT` is: the project goes into the local Maven repo as `<version>-SNAPSHOT` and is replaced on each run; a sibling project that must see your edits depends on `g:a:<version>-SNAPSHOT` |
| **`install`** | Install a release of the project into the local Maven repo (a version is installed once); `action=list` shows jkx tools (tool installs stay CLI-side) |
| **`import`** | Import Maven/Gradle into `jk.toml` |
| **`export`** | Write `maven` \| `gradle` \| `bom` files |
| **`ide`** | Write IDE project files (`kind=idea` \| `vscode` \| `all`) plus `.bsp/jk.json`, same generators as `jk ide`; `preview=true` lists without writing |
| **`graph`** | Compact module/dep graph (transitive expansion opt-in and budget-capped) |
| **`callers`** | Who calls a method (`address` = `Type.method`, every overload): each caller as `Owner.method` with `file:line` and the call's line, main sources before tests, counted, from the compiled classes and so complete. `transitive=true` lists every method that reaches it, by hop, up to `max_hops` (default 8). Same as `jk graph callers` ([Code graph](graph.md#callers)) |
| **`rename`** | Rename a method (`address` = `Type.method`, every overload, or `Type.method(int,String)`) to `to`, with a [`goal`](#goal), with every override, call, method reference and doc link the compiled classes record, in one call; lists any use it could not tell apart. Not read-only. Same as `jk graph rename` ([Code graph](graph.md#rename)) |
| **`query`** | A question about the code (`question`) answered in a token budget: the symbols it names as `Owner.member()` at `file:line`, and how they connect. Same as `jk graph query` ([Code graph](graph.md)) |
| **`read`** | One symbol's source or a type's members (`address` = `Type`, `Type.member`, `Type.*`, a path or glob; `#line=N,M`), in the `view` the budget fits. Same as `jk graph read` |
| **`find`** | Declarations by `kind`, `visibility`, `extends`, `implements`, `annotated` and a `name` glob. Same as `jk graph find` |

Start with **`run`**. The reply is the verdict. **`diagnostics`** is the detail past its cap. Do not dump full journal records. A tool
outside the default list is called by name once `tools/list` with `extended: true` has named it. `tools/call` accepts it either way; the bind and the session are the same.

### `goal`

`run`, `deps`, `rename` and `update` take `goal`: the task the call serves, under eight words,
repeated word for word until the task changes (`"goal": "Rename Ledger.post to record"`). It is
required in their schemas so an agent passes it, but jk never refuses a call without it. It titles
the session's [episode](web.md#sessions-and-episodes) on the dashboard: a call whose goal differs
from the last one starts a new episode, and the same goal keeps the current one. A call with no
goal joins the episode it would have anyway; when it starts one (another project, or after ten
minutes of quiet), that episode takes the session's last goal, and with none its title is inferred
from what it did. The episode records which (`titleSource`: `goal`, `lastGoal` or `inferred`).
The goal is kept to one line of at most 120 characters, and the project's secrets are masked in it,
as in every action summary, before it is stored, served or streamed. The graph's question tools
take no `goal`.

### `new`

`new` scaffolds with the same scaffolder and the same [template questions](templates.md#template-questions-jk-templatetoml)
as `jk new`.

- `action=templates` lists `{id, language, framework, description}` per template, and nothing
  else: the questions would be paid for on every listing.
- `action=form, template=<id>` lists that template's form, one line per field, as
  [`jk new --help`](templates.md) prints them: `database: none|postgres|mysql = postgres  # Database`.
  jk's own step comes first, from the same field list `jk new` renders (`lang`, `template`,
  `name`, `group`, `parentDir`, and `layout` when the template renders more than one), then the
  template's questions. With no `template` it is the `app` template's form.
- `new(template, name, params={…})` scaffolds; no `template` is `app`. `params` answers the
  template's questions (a string, or a boolean for a bool, a list of ids for a multi); `name`
  (lowercase letters, digits and `-`), `group`, `lang`, `layout` and `parentDir` (the form's
  `dir`, by default the bound project's parent) are arguments of their own. `preview=true` returns
  the file set and writes nothing.
- `template` may be a template directory: a relative one resolves against `parentDir`, else the
  bound project, for `action=form` and the scaffold alike. As with `jk new`, the project's `java`
  is the major of the machine's default JDK (`jk jdk default`), or the template's own when there
  is none, unless `params` answers `java`.
- The new project is wired for coding agents as `jk new` wires one ([Agents](agents.md#wiring-a-project-for-agents)):
  for the agent the client named itself in `initialize` (`clientInfo.name`: `claude-code`, `Cursor`,
  `gemini-cli-…`, `opencode`, `antigravity…`, `codex-…`), plus what the engine's `JK_AGENTS` and
  detected agents add. A client jk does not recognise gets the engine's setting alone, and
  `JK_AGENTS=none` in the engine's environment wires none, whoever asks. The result's `agents`
  lists the files written and the notes, the last of which names the agents wired.

A question the call leaves out takes its default; only a required question with no default holds
the call up, and an agent that passes every answer is never asked anything. What happens to one
depends on the client:

| The client declared | The call |
|---|---|
| Form elicitation | asks the human for exactly the missing fields, one step at a time (the name, then the template's questions; a question whose condition names one being asked comes in the next round), then scaffolds. Declining creates nothing and says so |
| URL elicitation only | asks the host to open the dashboard's [new-project wizard](web.md#new-project), pre-filled with every answer the call gave, and waits up to five minutes for the human to create the project there; the call then returns that project as if it had created it. Declining, or leaving the wizard unfinished, creates nothing and says so; a project created through the link afterwards is still wired for the calling client's agent, and the wizard says the call had ended |
| No elicitation | fails (`-32602`) with one line per missing question and the values it takes, so the agent can fill them in `params` and call again rather than guess |

A misfit answer (`database=oracle`) fails at once with the values the question takes, whatever the
client declared.

Token, loopback bind, and how to report a hole in that gate: [Security](security.md).

## Decisions

Your agent asks you; jk's page is where the details live. A few operations wait for a person
instead of proceeding on the agent's word. Each is a **decision** in the engine's one queue, which
the agent's host, the [dashboard](web.md#decisions) and `jk decide` all answer. Any of them may
accept or decline any decision; the first answer wins and every other surface closes.

| Operation | Asks when |
|---|---|
| `disk` `clean` / `nuke` / `m2-clean`, `jdk` uninstall, `node` uninstall | `confirm=true` and the host can ask |

Dependency work never asks: `update` applies and `deps` either applies or fails. A host that can
ask neither by form nor by link runs the operation on `confirm=true`. Decisions are a courtesy, not
a gate: they surface what a person wants to know first, in more detail than the host's own prompt.

jk asks by the first rung the session supports ([revisions](#protocol-revisions)), and stops there:

1. **Form elicitation.** The host renders the question (`answer`: yes / no) and the call resumes
   with the answer. A host that also takes links gets a third choice, *Show me the details*, which
   moves the question to rung 2.
2. **URL elicitation.** The host asks the user to open the decision's dashboard page,
   `#decision/<id>`; the call resumes when the decision is settled and jk sends
   `notifications/elicitation/complete`. A client that declared URL mode but has no way back on
   this request gets the `-32042` error carrying the link instead, and calls again once the user
   opened it.
3. **`NEEDS-YOU`.** When the host's rung runs out of time, the call answers:

   ```text
   NEEDS-YOU Clean the cache?
     ~/.jk/cache 2.1 GiB
   the user answers it: http://127.0.0.1:8910/#decision/3f9a0c1e…
   run(decision=3f9a0c1e…)
   ```

   Then `[ui] ask` ([Config](config.md#decisions)) applies once: `notify` (default) sends a desktop
   notification carrying the link (a click opens it on Windows and Linux), `open` opens the
   browser on it, `quiet` leaves the link in the result.

When another surface answers while the host's question is still open, jk withdraws it: a form
question gets `notifications/cancelled`, a link gets `notifications/cancelled` and
`notifications/elicitation/complete`, the desktop notification is closed, and the dashboard's
Needs-you banner drops the decision.

`run(decision=<id>)` makes the original call again with the decision: it proceeds once the
decision is accepted (once only), answers `DECLINED` or `EXPIRED` when it was not, and while it is
open waits as long as a call may, then answers `NEEDS-YOU` again. Calling the tool again with the
same arguments while its decision is open asks nothing new.

Every rung answers before the client's own tool timeout (55 s for Cursor, 240 s otherwise), so a
question nobody has answered yet becomes a `NEEDS-YOU` with the decision still open, not a
cancelled call. A decision expires as declined 30 minutes after it opened.

Decisions outlive the engine, as [sessions](web.md#sessions-and-episodes) do. Each one is kept
beside the session records with the call it continues, and an engine that replaces another (an
upgrade, a restart) takes over every decision with its deadline: the answer and `run(decision=<id>)`
both reach the new engine, the first answer still wins, and the operation still runs once. One whose
deadline passed while no engine ran is `EXPIRED`. When the decision a continuation names is gone,
the answer is `ASK-AGAIN`: repeat the original call without `decision` to be asked anew.

**`jk decide`** lists the open decisions; at a terminal it asks one (`jk decide <id>`, or a pick
when there are several) with the same widgets `jk new` uses. Anywhere, `-y` accepts it and `-d`
(`--decline`) declines it without asking; without a terminal one of the two is needed.

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
