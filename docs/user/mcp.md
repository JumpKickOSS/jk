# MCP

The resident engine hosts a **Model Context Protocol** server on the same HTTP listener as
the [web dashboard](web.md). MCP is **on by default** when HTTP is on.

Agent playbook (when to use which tool): [Agents](agents.md).
JumpKick skill: CLI `jk skill` / MCP **`skill`** / resource **`jk://skill`**.
Fix a failing build: [Troubleshooting](troubleshooting.md).

## Connect

**Claude Code, Cursor, VS Code, Gemini CLI and opencode need nothing by hand:** `jk new`, `jk init`,
`jk import` and `jk agents` write the registration for the ones in use
([Agents](agents.md#wiring-a-project-for-agents)). Any other client registers JumpKick once, below.

**Per project (preferred): `jk mcp`.** Register JumpKick as a stdio server whose command is `jk mcp`.
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
| Session | `initialize` answers with an `Mcp-Session-Id` header (32 hex digits, 128 random bits, never reused); echo it on every later request. One id is one **session**: the runs it starts journal as `trigger: mcp · session: <clientInfo.name> <tag>`, where the tag is the id's first four hex digits, or more when four would repeat the label of a session still open. `DELETE /mcp` with the header ends it, and so does the [session expiry](#session-expiry) (a day with no activity, by default). An id the engine no longer knows (its session ended or was pruned) is a `404`, and the client sends `initialize` again for a new session |
| Protocol | `2025-11-25`, `2025-06-18`, `2025-03-26` or `2024-11-05`, negotiated at `initialize` ([below](#protocol-revisions)); Streamable HTTP. Send `MCP-Protocol-Version` on every later request |
| Server name | `jk-engine` |
| Tool list | `tools/list` answers six tools (`build`, `diagnostics`, `deps`, `usages`, `rename`, `skill`); `install` installs for a sibling project. `{"extended": true}` on that call, or `[mcp] tools = "all"` (`JK_MCP_TOOLS=all`), lists every card. Every tool is callable by name either way |
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

The loop tools (`build`, `diagnostics`, `deps`, `usages`, `rename`, `skill`) and `why` answer `content` text only;
for `build` and `diagnostics` that text **is** the verdict (below). They send no
`structuredContent`, because hosts that get one show the model that instead of the text. A
failure the agent must act on (a bad `deps` coord, a failed relock) sets `isError`, and so does an
argument a tool refuses (a missing `dir`, an unknown field, a value of the wrong kind): the text
says what to send instead. An unknown tool is a JSON-RPC error. The extended
tools answer `structuredContent` (`schema` + `type`, like the rest of the machine model;
`schema` stays **1** until 1.0) with a one-line text summary.

`build` runs the tests (`kind` defaults to `test`), waits, and the reply is the verdict. `id=<id>` (no `kind`) reads an earlier one. `run` starts the application ([below](#run)); a `run` call with a `kind` is refused, pointing at `build`.
It does not carry a dashboard link, a session id, or a trigger. The journal still records who
asked; the human report is `target/jk-results.md` and [Web](web.md). `details` and
`jk://runs/latest/*` answer for the bound `dir`, never a sibling worktree's run.

## Protocol revisions

`initialize` answers the `protocolVersion` the client asked for when JumpKick speaks it, and
`2025-11-25` otherwise. The session keeps the revision it negotiated:

| Revision | What changes for the session |
|---|---|
| `2024-11-05`, `2025-03-26` | JSON-RPC batches are accepted. JumpKick never asks the client anything |
| `2025-06-18` | No batches (a batch is a `-32600` error). Form-mode elicitation when the client declares `elicitation` |
| `2025-11-25` | As `2025-06-18`, plus URL-mode elicitation when the client declares `elicitation.url`. `"elicitation": {}` means form mode alone |

Over HTTP, `MCP-Protocol-Version` names the session's revision; a value JumpKick does not speak is a
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
did not give up, so a request went without it, is a `W` line on a green run as on a red one. So is,
in a strict module (`[env] inherit = false`), a variable a test looked up that the shell sets, with
a `FIX` line under it naming the `[env]` declaration ([Test](test.md#keeping-a-module-strict)). A failure adds one line per problem, capped at five, then
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

In a module with [`[test] failure-snapshot = true`](test.md#the-state-at-a-failure-test-failure-snapshot),
a failed test's block ends with `state when it threw:` and one line per frame: the frame that threw,
then the project frames above it, each with its arguments and locals.

A failed resolve's block ends with the conflict it ran into, for each contested coordinate:
`conflict <group:artifact>`, one line per version wanted of it and who wants it (`jk.toml` for
your own declaration, and the BOM or managed entry that pinned it), `picked none:` and why, and
the [`why`](#tools) call for that coordinate. The `diagnostics` answer carries the same lines.

```
FAIL build app · 1 error · 90ms
E resolve-deps: │ ch.qos.logback:logback-classic 1.5.6 depends on org.slf4j:slf4j-api 2.0.13
  │ org.apache.zookeeper:zookeeper 3.9.2 depends on org.slf4j:slf4j-api 1.7.36 (pinned by org.apache.zookeeper:zookeeper-bom:3.9.2)
  conflict org.slf4j:slf4j-api
    2.0.13 wanted by ch.qos.logback:logback-classic 1.5.6
    1.7.36 wanted by org.apache.zookeeper:zookeeper 3.9.2, pinned by org.apache.zookeeper:zookeeper-bom:3.9.2
    picked none: no version satisfies every want
    why(coord=org.slf4j:slf4j-api)
```

A failure with no failed step, test or source error carries its reason on the headline, after
` — `: the run-level error that decided it, or else the warning the run left.

```
FAIL test jk · 9.5s — --class named 1 class the tag filter excluded: com.example.FormatThreadsBenchTest [bench]; drop --exclude-tags bench to run it
```

When another live session, in this checkout or another checkout of the same project, has an open
[episode](web.md#sessions-and-episodes) whose runs saw a file changed that this run's episode
changed too, the verdict ends with one line naming that session and the types (or, for a file
that is not JVM source, the files) both are editing. Two different files meet too when the
[graph](graph.md#anchors) of each checkout says one defines an anchor (a config key, a variable, a
column, a route) that the other defines or uses, and the line names the anchor: editing
`application.properties` and a class bound to one of its keys is an overlap, two classes that only
read one key are not. Each session hears of one shared file or anchor once, in its next verdict; a
verdict with no overlap carries nothing more, and a checkout with no graph compares files alone.

```
OK test core · 41 tests · 3.2s
W codex 91be is also editing Pricing, QuoteTest, config:hotel.rate.weekend
```

Paths are relative to the project directory. A quoted source row is its line number, `|`, a
tab, then the line exactly as the file has it, indentation included: everything after the first
tab is an Edit's `old_string`. When that line occurs more than once in the file, the rows on
either side come with it so the quote is unique. `wait=false` answers `RUNNING build jid=42` and
`build(jid=42)`. A wait that expires answers `TIMEOUT build jid=42` with the same continuation:
`build(jid=42)` waits on that job again, starts nothing, and returns the verdict when it finishes.

## Waits and keep-alive

`build` waits up to `timeout_s` (default **240**, at most 3600), then answers `TIMEOUT` and the job
keeps going. The default sits under the shortest tool-call timeout among common MCP clients
(some stop a call after 300 s whatever it reports), so a default wait always answers before the
client gives up. A client with a longer timeout can pass a larger `timeout_s`.

When a `tools/call` carries `_meta.progressToken`, the engine sends `notifications/progress` for
the token while the call waits on its job: the running step and the percent (`progress` of
`total` 100), within a second of a change and at least every 5 s, and the job's hold for memory.
Over HTTP they come on the call's own response, which turns into an event stream when the request
accepts `text/event-stream`; `jk mcp` passes them on. MCP clients that reset their timeout on
progress (the spec allows it) keep a long wait alive; the rest still get the `TIMEOUT`
continuation. The engine's event stream (below) carries the same job's every event.

`jk mcp` handles requests concurrently: while a `build` waits, a `ping`, a `status` or a second call
answers at once. A `notifications/cancelled` naming an outstanding `build` cancels its job, and the
call answers with the cancelled verdict. Over HTTP, send the cancel with the call's
`_meta.progressToken` in its own `params._meta`, or call the `cancel` tool with the `jid`. A
progress token is the session's own: the cancel finds it only under the `Mcp-Session-Id` the call
went out on, so two agents that pick the same token never cancel each other's job.

The CLI prints the same text with `--agent` or `JK_AGENT=1`, and when stdout is not a terminal
and the process was spawned by a coding-agent CLI. Human terminals keep today's output.
`-O jsonl` stays the live event stream.

## Tools

Every tool card an agent's host shows the model is paid for on every turn. The default
`tools/list` is six tools: the fix-and-rerun loop, `usages`, the one graph question measured to save an agent tokens, and `rename`, which makes in one call the edits a rename otherwise takes a turn each for. The same sentences are the skill's tool table (`jk skill` / `skill`),
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
| **`build`** | Start a build or test job and, by default, wait. The reply is the verdict (see above). [`goal`](#goal) names the task. `jid` waits on a job already started instead. `decision=<id>` continues the call a [`NEEDS-YOU`](#decisions) answered. `kind` is `test` (default) \| `build` \| `compile` \| `gate` (`jk test -g`: the guard suites with the guard lanes, the bar before work is handed back) \| `guard` (`jk guard`: the lanes, no tests) \| `assemble` \| `native` \| `image` \| `clean` \| `format`. Any other kind is refused, naming the tool that starts it: `lock` is `deps(action=lock)`, `update` is `update(apply=true)`, and `publish`, `install` and `import` are their own tools. `modules` limits modules and `classes` test classes (an array or one comma-separated string; the CLI repeats `-S`); `suites`, `include_tags`, `exclude_tags`, `affected` and `skip_tests` select as the CLI flags do, and an argument `build` does not know is refused by name. Its card names `kind`, `dir`, `modules`, `classes`, `suites`, `jid`, `id` and `decision`; the other fields are accepted as listed here. The job fields are the body of `POST /api/build`, so the same JSON starts the same job on either. `id=<id>` with no `kind` reads an earlier verdict, or waits when `<id>` is a running job's `jid`. `wait=false` answers the `jid` at once; `timeout_s` (default 240) bounds one wait. `deadline_s` caps the job's wall time; default is the engine's `detached-deadline-ms` (1 hour), `0` = none. `kind=test` defaults to the **unit** suite — do not pass every suite as a habit. With `dir` at a [group root](groups.md#agents), the job runs in each selected member in turn (`modules` names members) and the reply is each member's verdict and a total. A `build`, `test`, `gate`, `guard` or other job that builds the tree and passes in a checkout with a graph waits for the graph's refresh (10 s at most) only when a file that defines or uses an anchor changed since the last one, and, when a hand edit changed a name another file still uses (a SQL column, a config key, the variable that sets it, a route), ends with `Your edit changed these names; these places still use the old one:` and those places, in five files at most, and the `usages` call that lists the rest ([After a hand edit](graph.md#after-a-hand-edit)); otherwise it adds nothing, and `[graph] anchor-tail = "off"` turns it off. The CLI never prints these lines |
| **`run`** | Run the application ([below](#run)). `action` is required: `start` builds it as `jk run` does (tests skipped), starts it and waits until it answers, the reply a verdict; `logs` tails its stdout and stderr, then the file `log` or `[run] log` names (`request` narrows both to one request's lines, `lines` caps them); `stop` stops it. An app is stopped by the next start in its project, when the session that started it ends, and when the engine stops. A `kind` is refused: builds and tests are `build` |
| **`cancel`** | Cancel by **`jid`**, or every live job for a `dir` |
| **`history`** | Recent runs as **summaries**, a page at a time (filters: dir, projectId, success, kind, limit, next); `details` and `diagnostics` read one run in depth |
| **`flaky`** | The project's flaky and quarantined test classes, as `jk flaky`. `action` is `list` (default): `flakes` and `runs` (the root's `[test] quarantine` threshold) and `classes`, each with `class`, `module`, `quarantined`, `flakes` in the `runs` that count (the window, since its last release), `history` (oldest first: `.` passed, `F` failed, `R` passed on a re-run, `\|` released) and the entry's `since`, `reason` and `issue` (`{ id, url }`); `show` with `class` (fully qualified or simple) answers that class's rows, flaky or not; `issue` with `class` and `issue` (an id, or a URL when that is all there is) and an optional `url` tracks the class under that issue, as `jk flaky issue`, and answers its entries under `tracked`; `release` with `class` deletes its entries from `.jk/test/quarantine.toml`, resets its count, and answers them under `released` ([Test](test.md#flaky-tests-are-quarantined-jk-flaky)) |
| **`diagnostics`** | Problems past the verdict cap, or one file with full snippets (`file`) |
| **`details`** | Budgeted tail of `details.jsonl` (default last-fail, `error` + `task-finish`, 80 events). CLI `jk results -d` dumps the full file |
| **`why`** | The dependency path and the rule that picked the version, at most five lines (`coord`) |
| **`explain`** | Forecast the job `build` would start: takes `build`'s selection fields (`modules`, `classes`, `suites`, `include_tags`, `exclude_tags`, `affected`, `skip_tests`) and answers the dirty modules, cached steps and ETA, the same object as `GET /api/explain` and the same forecast as `jk explain` |
| **`affected_tests`** | WIP module cone + advisory ranked test classes; writes `target/jk-tests-affected.md` |
| **`outdated`** | Declared pins an update would move (read-only; Current / Compatible / Latest): dependencies, language and tool keys, rule packs; `all=true` lists every row, `checked` counts them, `file` is the `target/jk-outdated-dependencies.md` it wrote, and a row whose coordinate moved carries `relocated` |
| **`update`** | Bump declared pins in `jk.toml` to the newest stable on the same major, then relock, as `jk update`. Params: `dir`, [`goal`](#goal), `deps` (optional list of handles), `major` (bool), `apply` (bool, default **false**). The preview writes nothing and lists each move under `rewrites` (`manifest`, `module`, `table`, `handle`, `coordinate`, `from`, `to`, and `adds`: the lock packages its relock would add), at most 100 with `moreRewrites` counting the rest; without `major`, each newer major is listed under `available`, not applied. A move whose locked and new jars can both be read, in either list, carries `breaks`: how many places in the project's compiled classes (as the last compile left them) call, read or extend public API of that jar the move removes, renames or changes the signature of, and `usages`: the address the `usages` tool (or `jk code usages`) lists those places under, `dep:<group:artifact>@<version>` ([Code graph](graph.md#what-another-version-of-a-dependency-breaks)). It is the preview `jk update --preview` prints and `GET /api/project/update` returns. `apply=true` starts the `update` job `jk update` runs and waits as `build` does: it is in history as the agent's, with a `jid` that `cancel` stops. Every move on a pin's major applies, across a minor and with any package the relock adds, and nobody is asked |
| **`deps`** | Add, remove, or pin coordinates and relock, with a [`goal`](#goal). `preview=true` does not write; a version or coordinate the repositories do not serve is an error, preview or not. An edit whose lock trips the project's [deny policy](publish.md#deny-policy) fails naming each rule it trips, and `jk.toml` and `jk-lock.toml` are left as they were. A coordinate the action cannot use (`pin` without a version, `add` without a group) is refused. The same edit as `jk add`/`jk remove` and `POST /api/project/deps`. `action=lock` edits nothing: it runs the `lock` job `jk lock` runs and waits as `build` does |
| **`workspace`** | Preview/apply workspace member add/remove |
| **`manifest`** | Set whitelisted `jk.toml` keys (`java=N` is language level, not `jdk=`) |
| **`config`** | Machine config get/set, or `apply_preset=ci`. `set` takes `nerd-font` and `engine.max-heap-mb`, which is `0` (uncapped) or 128 to 65536 MiB |
| **`disk`** | Cache/store usage; `clean`/`nuke`/`m2-clean` require `confirm=true`, and a host that can ask the user [asks](#decisions) |
| **`jdk`** | List / install / uninstall (`confirm=true` for uninstall, and a host that can ask [asks](#decisions)) |
| **`node`** | Node.js: `list` (installs, JumpKick's and other managers'), `list-remote` (`lts`, `major`), `install` (`spec`), `uninstall` (`spec` = exact version, `confirm=true`, and a host that can ask [asks](#decisions)), `which` (`dir`: the project's Node.js), `pin` (`dir`, optional `spec`: writes `jk.toml` and relocks) |
| **`doctor`** | The health rows `jk doctor` checks, `{ok, rows: [{id, label, status, detail, fix?}]}`: `config`, `dirs`, `state`, `jdk`, and for `dir` (default: bound dir) `lock` and `m2`, then `engine`; the rows only the client can answer (`client`, `shell`, `path`, `mvn`, `logins`, `tools`) are `unavailable`, not left out; on Windows the host rows and their fixes ([Install](install.md#windows-host-checks)). The same object as `GET /api/doctor`. Disk usage is `disk`, settings `config` |
| **`machine`** | Windows: `action=list` (default) the host and its WSL distros with the JumpKick each has, its engine and an operation's progress; `install` or `remove` JumpKick in the distros `distros` names, as `jk self setup-wsl` does ([Engine](engine.md#http-and-mcp)). Installing and removing always [ask](#decisions) |
| **`new`** | Scaffold from a template, as `jk new` does (default `app`): `action=templates` lists templates, `action=form` one template's fields, `params` answers its questions, `preview=true` does not write ([below](#new)) |
| **`publish`** | Validate the publish bundle (dry-run). `upload=true` uploads it to the repository `[publish] repository` names, with the credential `jk repo login` / `JK_REPO_<ID>_*` holds and signing from `[publish]` (`JK_GPG_PASSPHRASE`). An agent never names a URL; a project with no `[publish] repository`, or with `central`, is refused with the fix — Central uploads stay on `jk publish` |
| **`install`** | The inner-loop install, used as a Maven `-SNAPSHOT` is: the project goes into JumpKick's `repos/jk-local` as `<version>-SNAPSHOT` and is replaced on each run; a sibling project that must see your edits depends on `g:a:<version>-SNAPSHOT`. `action=list` shows jkx tools (tool installs stay CLI-side) |
| **`import`** | Import Maven/Gradle into `jk.toml` |
| **`export`** | Write `maven` \| `gradle` \| `bom` files |
| **`ide`** | Write IDE project files (`kind=idea` \| `vscode` \| `all`) plus `.bsp/jk.json`, same generators as `jk ide`; `preview=true` lists without writing |
| **`graph`** | Compact module/dep graph (transitive expansion opt-in and budget-capped) |
| **`usages`** | Who calls a method (`address` = `Type.method`, every overload): each caller as `Owner.method` with `file:line` and the call's line, main sources before tests, counted, from the compiled classes and so complete. `transitive=true` lists every method that reaches it, by hop, up to `max_hops` (default 8). `address` = `Type.field` lists every use of the field, writes apart from reads; `Type` or `@Annotation` every use of the type; `Type.Type` every constructor call; each Java use at its exact line from javac's reference index, and any Kotlin, Groovy or edited source that spells the name marked `(inferred)`, in which case the answer says it is not complete. `address` = an anchor (`column:owners.telephone`, `route:GET /owners/{id}`, `env:DATABASE_URL`, `config:server.port`, `port:8080`) lists its definitions and uses, each graded, inferred ones marked ([Anchors](graph.md#anchors)); `route:*` (any kind, or a glob such as `route:GET /owners/*`) lists that kind's anchors with their counts. `address` = `file:<path>` (or `file:<path>:<line>`) lists the anchors a file defines or uses, each with the other places that name it, after the places the file's last edit left under an old name. The `address` description names the forms; a field's answer also carries, bounded and must-change first, the places of each anchor it maps to (column, JSON or bean property, configuration key), its accessors' callers and the test classes that reach it, and says what it looked at; an accessor's or handler's answer carries its anchors' places, a type's one line per anchor, an anchor's the field it maps to ([What a change reaches](graph.md#what-a-change-reaches)). A long answer is cut to `token_budget` (at most 32,000 here, as for `query`, `read` and `find`; a larger value is clamped) and says the budget that shows all of it, or narrow with `scope`, `modules` or `source_set`. Same as `jk code usages` ([Code graph](graph.md#uses-of-a-field-a-type-an-annotation-or-a-constructor)) |
| **`rename`** | Rename a method (`Type.method`, every overload, or `Type.method(int,String)`), a field with its accessors (`Type.field`), a type (`Type`; a qualified `to` moves it) or a package (`a.b`) to `to`, with a [`goal`](#goal). Edits every use a compiler bound, lists the rest as candidates `c1`… (declared or inferred) and hazards `h1`… with their pins; `also` (ids, `declared`, `all`) applies chosen ones. The engine then compiles the edited modules and proves the new name has the old one's references, or undoes the rename. Its last sentence names the listed uses nobody applied, which no compile or test reads, by kind and `file:line`: `Not done` while one that should change is left. Not read-only. Same as `jk code rename` ([Code graph](graph.md#rename)) |
| **`query`** | A question about the code (`question`) answered in a token budget: the symbols it names as `Owner.member()` at `file:line`, and how they connect; with `library` (a glob over the locked libraries, `*` for all) the public types and methods of those libraries that match, with signatures. Same as `jk code query` ([Code graph](graph.md#asking-a-library)) |
| **`read`** | One symbol's source or a type's members (`address` = `Type`, `Type.member`, `Type.*`, a path or glob; `#line=N,M`), in the `view` the budget fits. Same as `jk code read` |
| **`find`** | Declarations by `kind`, `visibility`, `extends`, `implements`, `annotated` and a `name` glob; with `library`, the types of the locked libraries it names instead. Same as `jk code find` |
| **`delete`** | Delete a method (`address` = `Type.method`, every overload), a field (`Type.field`) or a type (`Type`) only when nothing uses it, then compile the modules it touched, undoing the delete whole when they do not compile; `kind` (`method`, `field`, `type`) when the name is ambiguous. With a use left (a call or reference, an anchor, an override, a framework annotation, an entry point, a doc link, a string naming it) it writes nothing and lists each use as `usages` does, each annotation at the `file:line` that writes it. Not read-only. Same as `jk code delete` ([Code graph](graph.md#delete-move-pull-up-and-extract-an-interface)) |
| **`move`** | Move a static method (`address` = `Type.method`, every overload) or static field (`Type.field`) to the class, enum or record `to`, editing every use javac bound (qualifiers, method references, doc links, static imports) and the imports of the moved code and its callers, then compile the modules it touched and prove each moved symbol has the references the old one had, undoing the move whole otherwise. An instance method moves through a route to a `to`: a field of its type holding one, or a parameter of that type; every call goes through it, and what the method used of its old type is passed in. When exactly one route reaches `to` from every call it moves at once, saying why any other was not taken; otherwise it writes nothing and lists the routes (`f1`, ... for fields, `p1`, ... for parameters) with what each call would read, and `via` names one. What would break or change without a compile error writes nothing and is listed: an instance field, an override, a call a route cannot take, a member one side would not see, a name the target already has, a static hidden on either side, a framework annotation, a use through an expression. A string naming it is listed and left, and the answer ends `Not done` while one is. Not read-only. Same as `jk code move` ([Code graph](graph.md#delete-move-pull-up-and-extract-an-interface)) |
| **`pull_up`** | Pull an instance method (`address` = `Type.method`, every overload) or instance field (`Type.field`) up to the class its type extends (`to`, optional: a class it does not extend is refused): the code moves there with the imports it needs and every use stays as written, binding to the inherited member; another subclass that declares the method gets `@Override`. Then it compiles the modules it touched and proves each pulled-up symbol has the references the old one had, undoing the edit whole otherwise. What would break or change without a compile error writes nothing and is listed: a member of the subclass the code uses that the superclass lacks, a member a caller would no longer see, a `super` call, an override every subclass would inherit, a name the superclass already has, an overload in another subclass, a string naming it. Not read-only. Same as `jk code pull-up` ([Code graph](graph.md#delete-move-pull-up-and-extract-an-interface)) |
| **`extract_interface`** | Create the interface `to` (a simple name lands beside the class, a qualified one in that package) from public instance methods of the class `address`: `methods` names them (`price,total`, every overload of each), by default every public instance method that overrides nothing, and the answer names them. The signatures are copied as written with the imports they need; the class implements the interface with `@Override` on each method. In the same edit each variable, parameter, field and return type of the class's type that uses only the interface's methods is switched to the interface (a return type when every caller's use of the result allows it). What retyping could break stays and is listed at `file:line` with its reason: a use of another member, a value passed on, an override, an overload, a method reference, a lambda parameter, a record component, a field another file reads, a constructor call. `switch: false` creates the interface alone and lists the places that could take it. Then it compiles the modules it touched, undoing the edit whole, the new file included, when they do not compile. A generic class, a static or non-public method, or a name already taken writes nothing. Not read-only. Same as `jk code extract-interface` ([Code graph](graph.md#delete-move-pull-up-and-extract-an-interface)) |

Start with **`build`**. The reply is the verdict. **`diagnostics`** is the detail past its cap. Do not dump full journal records. A tool
outside the default list is called by name once `tools/list` with `extended: true` has named it. `tools/call` accepts it either way; the bind and the session are the same.

### `goal`

`build`, `deps`, `rename` and `update` take `goal`: the task the call serves, under eight words,
repeated word for word until the task changes (`"goal": "Rename Ledger.post to record"`). It is
required in their schemas so an agent passes it, but JumpKick never refuses a call without it. It titles
the session's [episode](web.md#sessions-and-episodes) on the dashboard: a call whose goal differs
from the last one starts a new episode, and the same goal keeps the current one. A call with no
goal joins the episode it would have anyway; when it starts one (another project, or after ten
minutes of quiet), that episode takes the session's last goal, and with none its title is inferred
from what it did. The episode records which (`titleSource`: `goal`, `lastGoal` or `inferred`).
The goal is kept to one line of at most 120 characters, and the project's secrets are masked in it,
as in every action summary, before it is stored, served or streamed. The graph's question tools
take no `goal`.

### `run`

`run` runs the application an agent is debugging without a shell job, a sleep, or a `curl` in a
loop. Every call names its `action`; a call with a `kind` is refused with one line, because builds
and tests are [`build`](#tools). `run(action=start)` builds the project as
[`jk run`](run.md#jk-run) does, then starts the program that plan names, with stdout and stderr in
`target/jk-app.log`, and waits for it:

| What the project says | What `run` waits for |
|---|---|
| `port` on the call | that port to accept a connection |
| [`[dev] ready`](run.md#the-apps-own-readiness-dev-ready) | that URL to answer 2xx/3xx, or `ready-pattern` to match the app's output |
| nothing | the first port the app's output says it listens on (`Tomcat started on port 8080`, `Listening on http://0.0.0.0:8080`) to accept a connection |

The wait is `[dev] ready-timeout`, 60 s by default. A port that already answers before the app
starts is a failure: something else listens there. The reply is one line:

```
READY app shop pid 4242 · http://localhost:8080 · 7.3s
```

A build that fails answers its own verdict (`FAIL build …`), and nothing starts. An app that exits
first, or does not answer in time (it is stopped), is `FAIL` with the last 40 lines of its output:

```
FAIL app shop pid 4242 · exited with 1 before port 8080 answered · 3.1s
***************************
APPLICATION FAILED TO START
…
log: target/jk-app.log
```

`UP app shop pid 4242 · its output named no port in 60s; port=N waits for one` is an app that is
still running but said nothing about a port: pass `port`. A second start in the same project
stops the first and starts again on the new build. `args` are the program's arguments, and
`timeout_s` bounds the wait for the build as it does for `build`.

`run(action=logs)` reads the app's stdout and stderr: that is what `run` promises to keep. An app
that writes its own log file as well names it with `log=<path>` on the call or with `[run] log`
in its `jk.toml` (relative to the module); its tail follows, and the two share the budget. Each
source answers its last 60 lines (`lines`, at most 400; 16,000 characters in all). `request` keeps
only the lines of one request, each with the exception, stack frames and `Caused by:` lines under
it, so one failed request reads alone. `request=/…/` is a Java regex; anything else is text the
line must contain (a request id, a path, a thread name). A regex that runs for two seconds stops
and answers what it matched before then, and says so.

```
LOG app shop · running pid 4242
== stdout and stderr · 2 lines for req=a1
INFO  [exec-1] GET /api/orders/7 req=a1
ERROR [exec-1] order 7 failed req=a1
== logs/app.log · 1 line for req=a1
WARN  order 7 retried req=a1
```

`run(action=stop)` stops it (`STOPPED app shop pid 4242 · ran 3m 12s`): the app is asked to exit,
and whatever of it is left after five seconds is killed. An app is also stopped when the session
that started it ends (`DELETE /mcp`, or its [expiry](#session-expiry)) and when the engine stops,
so one an agent forgets does not outlive it. The dashboard lists every running app with the
session that started it ([Web](web.md#the-console)). `run` is on the extended list, not the
default one.

### `new`

`new` scaffolds with the same scaffolder and the same [template questions](templates.md#template-questions-jk-templatetoml)
as `jk new`.

- `action=templates` lists `{id, language, framework, description}` per template, and nothing
  else: the questions would be paid for on every listing.
- `action=form, template=<id>` lists that template's form, one line per field, as
  [`jk new --help`](templates.md) prints them: `database: none|postgres|mysql = postgres  # Database`.
  JumpKick's own step comes first, from the same field list `jk new` renders (`lang`, `template`,
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
  detected agents add. A client JumpKick does not recognise gets the engine's setting alone, and
  `JK_AGENTS=none` in the engine's environment wires none, whoever asks. The result's `agents`
  lists the files written and the notes, the last of which names the agents wired.

A question the call leaves out takes its default; only a required question with no default holds
the call up, and an agent that passes every answer is never asked anything. What happens to one
depends on the client:

| The client declared | The call |
|---|---|
| Form elicitation | asks the human for exactly the missing fields, one step at a time (the name, then the template's questions; a question whose condition names one being asked comes in the next round), then scaffolds. Declining creates nothing and says so |
| URL elicitation only | asks the host to open the dashboard's [new-project wizard](web.md#new-project), pre-filled with every answer the call gave, and waits up to five minutes for the human to create the project there; the call then returns that project as if it had created it. Declining, or leaving the wizard unfinished, creates nothing and says so; a project created through the link afterwards is still wired for the calling client's agent, and the wizard says the call had ended |
| No elicitation | answers `isError` with one line per missing question and the values it takes, so the agent can fill them in `params` and call again rather than guess |

A misfit answer (`database=oracle`) fails at once with the values the question takes, whatever the
client declared.

Token, loopback bind, and how to report a hole in that gate: [Security](security.md).

## Decisions

Your agent asks you; JumpKick's page is where the details live. A few operations wait for a person
instead of proceeding on the agent's word. Each is a **decision** in the engine's one queue, which
the agent's host, the [dashboard](web.md#decisions) and `jk decide` all answer. Any of them may
accept or decline any decision; the first answer wins and every other surface closes.

| Operation | Asks when |
|---|---|
| `disk` `clean` / `nuke` / `m2-clean`, `jdk` uninstall, `node` uninstall | `confirm=true` and the host can ask |
| `machine` `install` / `remove` | Always, through the queue when the host cannot ask |

Dependency work never asks: `update` applies and `deps` either applies or fails. A host that can
ask neither by form nor by link runs the operation on `confirm=true`. Decisions are a courtesy, not
a gate: they surface what a person wants to know first, in more detail than the host's own prompt.

JumpKick asks by the first rung the session supports ([revisions](#protocol-revisions)), and stops there:

1. **Form elicitation.** The host renders the question (`answer`: yes / no) and the call resumes
   with the answer. A host that also takes links gets a third choice, *Show me the details*, which
   moves the question to rung 2.
2. **URL elicitation.** The host asks the user to open the decision in the dashboard,
   `#decision/<id>`; the call resumes when the decision is settled and JumpKick sends
   `notifications/elicitation/complete`. A client that declared URL mode but has no way back on
   this request gets the `-32042` error carrying the link instead, and calls again once the user
   opened it.
3. **`NEEDS-YOU`.** When the host's rung runs out of time, the call answers:

   ```text
   NEEDS-YOU Clean the cache?
     ~/.jk/cache 2.1 GiB
   the user answers it: http://127.0.0.1:8910/#decision/3f9a0c1e…
   build(decision=3f9a0c1e…)
   ```

   Then `[ui] ask` ([Config](config.md#decisions)) applies once: `notify` (default) shows it in a
   dashboard you are using, or shows a browser notification from a dashboard that may notify, or
   else sends a desktop notification carrying the link (a click opens it on Windows and Linux)
   ([Web](web.md#notifications)); `open` opens the browser on it, `quiet` leaves the link in the
   result.

When another surface answers while the host's question is still open, JumpKick withdraws it: a form
question gets `notifications/cancelled`, a link gets `notifications/cancelled` and
`notifications/elicitation/complete`, the desktop or browser notification is closed, and the
dashboard's bell and Needs-you banner drop the decision.

`build(decision=<id>)` makes the original call again with the decision: it proceeds once the
decision is accepted (once only), answers `DECLINED` or `EXPIRED` when it was not, and while it is
open waits as long as a call may, then answers `NEEDS-YOU` again. Calling the tool again with the
same arguments while its decision is open asks nothing new.

Every rung answers before the client's own tool timeout (55 s for Cursor, 240 s otherwise), so a
question nobody has answered yet becomes a `NEEDS-YOU` with the decision still open, not a
cancelled call. A decision expires as declined 30 minutes after it opened.

Decisions outlive the engine, as [sessions](web.md#sessions-and-episodes) do. Each one is kept
beside the session records with the call it continues, and an engine that replaces another (an
upgrade, a restart) takes over every decision with its deadline: the answer and `build(decision=<id>)`
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
| `jk://runs/latest` | Summary of the bound dir's newest finished run (`bind` first) |
| `jk://runs/latest/results` | That run's verdict (same text as `build`) |
| `jk://runs/latest/details` | Budgeted tail of that run's `details.jsonl` (same as `details`) |
| `jk://guards` | Guard catalog (same as `jk guard explain`); `jk://guards/<id>` is one rule's card (same as `jk guard explain <id>`). Reads the connection's bound dir (`bind` first); an unknown id is a `-32602` error naming the nearest ids |
| `jk://disk` | Cache and store usage |
| `jk://config` | Effective machine config |

## Prompts

| Name | Intent |
|------|--------|
| `learn-jumpkick` | `skill` then follow that playbook (not Maven/Gradle) |
| `fix-failing-build` | `build kind=build` (the reply is the verdict) then edit then `build` again |
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
| `?progressToken=T` | The job a `tools/call` with `"_meta":{"progressToken":"T"}` on the same `Mcp-Session-Id` runs or waits on (send the header with the `GET`); the stream may open before that call |

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
