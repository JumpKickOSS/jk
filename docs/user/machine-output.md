# Machine output

How JumpKick talks to **agents, scripts, and CI**. Humans at a TTY get a terse visual CLI
(wedges, bars). **Do not scrape it.**

| Consumer | Default channel |
|----------|-----------------|
| Human at a TTY | Terse visual CLI |
| Human debugging | `jk results` first; `--details` / `-v` if needed |
| Agents / scripts / CI | **`jk manual`** once, then **`target/jk-results.md`** / `jk results` (or MCP `jk_results`), then `--output json`/`jsonl` or `jk results --details` |
| Web dashboard | Engine HTTP + SSE (`/api/events`) |
| MCP clients | Tools, resources, SSE — same facts, not a second build model |

JumpKick system prompt: `jk manual` / MCP `jk_manual`. Agent playbook: [Agents](agents.md).
MCP tools: [MCP](mcp.md). Failures: [Troubleshooting](troubleshooting.md).

## `jk results`

High-level markdown for the **whole invocation** (compile, test, package, native, image,
publish). Written so you do not need to tail the TTY.

```bash
jk results              # print the latest report
jk results --details    # print that run's details.jsonl
```

On disk (the command reads the journal copy; `target/` is a latest-copy fallback):

```text
target/jk-results.md
~/.jk/state/builds/projects/<key>/runs/<build-number>/jk-results.md
```

The two markdown files are the same report. JUnit XML stays at `target/reports/test-results/`.
There is no separate `test-results.md`. MCP: **`jk_results`** and resource
`jk://runs/latest/results`. After a test run, prefer this file over `--all` guesswork:
default `jk test` is the unit suite; climb with `--suite`. [Test](test.md).

## Live JSONL

```bash
jk build --output json
jk test  --output jsonl          # identical to json
export JK_OUTPUT=json
```

- One JSON object per line, flushed promptly.
- Every object: `schema` (int, **always 1** until jk 1.0), `ts` (epoch ms), `type` (string).
- Most lines also carry **`progress`**: aggregate percent **0–100** or `null`, computed
  **in the engine**. Do not re-sum per-task `numerator`/`denominator` for the job bar.
- For whole-job % without task spam, subscribe to **`type=workspace-progress`**.
- Terminal human chrome is **suppressed** in this mode.

### One vocabulary for every build-kind verb

`jk build`, `jk test`, `jk compile`, `jk image`, `jk native`, `jk install` and `jk run` emit the
**same** workspace envelope. The verb selects which stages the build includes; it is not a
different kind of job, so a parser written against one works against all of them:

- exactly one **`workspace-start`**,
- a **`module-start`** / **`module-finish`** pair for every module entered,
- exactly one terminal **`workspace-finish`** — on *every* outcome, including failure,
  cancellation and a wire error. A stream that ends without it means jk died, not that the build
  is still running.

**`jk run` is the one exception, and only at its tail.** It streams the workspace pre-build in
full and terminates it with `workspace-finish` *before* it execs your program. jk writes nothing
more to stdout after that line: from there the stream is the program's. Treat `workspace-finish`
as end-of-stream for `jk run`.

Illustrative lines:

```json
{"schema":1,"ts":1721664000123,"type":"buildplan-start","plan":"test","denominator":42,"tasks":3,"progress":12.5}
{"schema":1,"ts":1721664000901,"type":"error","task":"run-tests","code":"test-failure","message":"…","test":"…","exceptionClass":"org.opentest4j.AssertionFailedError","progress":67.3}
{"schema":1,"ts":1721664001000,"type":"buildplan-finish","plan":"test","success":false,"duration_ms":880,"warnings":0,"errors":1,"progress":100}
```

### `jk dev`

`jk dev --output json` (and `jk watch run`) keeps the workspace envelope for every build the loop
runs — the first one and each recompile after a change — and between builds the stream carries
the session's processes, every line with its source. The app is piped under this mode, so stdout
is one JSONL stream from the first byte to the last; on a terminal the app owns stdout itself.

| `type` | Fields | When |
|---|---|---|
| `sidecar-started` | `name`, `pid` | A `[dev.sidecars]` entry was spawned — once per session, and again after each `restart = "on-exit"` relaunch |
| `sidecar-output` | `name`, `stream` (`stdout` / `stderr`), `line` | One line of the sidecar's output; carriage-return repaints are collapsed to the row's final state |
| `sidecar-ready` | `name`, `url` (its `ready` URL, when it has one), `frontDoor` (only when `true`) | Its probe passed |
| `sidecar-exited` | `name`, `pid`, `exit`, `restartInMs` (only when a relaunch is scheduled), `gaveUp` (only when the five-restart budget is spent) | The sidecar exited on its own |
| `app-started` | `pid` | The app JVM was started or restarted |
| `app-output` | `stream`, `line` | One line of the app's stdout or stderr |
| `app-exited` | `pid`, `exit` | The app exited or was stopped for a restart |
| `dev-ready` | `url` (the `front-door` sidecar's `ready` URL, else the app's own `[dev] ready` URL; absent when neither names one), `app` (the app's command as displayed) | The whole stack is up: every sidecar's probe passed, and the app's own `[dev] ready` / `ready-pattern` probe when it declares one — without it the app counts as ready once forked. Emitted again after each process restart of the app when the app is the front door — a sidecar outlives the restart, the app's process does not |

```json
{"schema":1,"ts":1721664002000,"type":"sidecar-started","name":"web","pid":48213}
{"schema":1,"ts":1721664002410,"type":"sidecar-output","name":"web","stream":"stdout","line":"  VITE v8.3.0  ready in 212 ms"}
{"schema":1,"ts":1721664002655,"type":"sidecar-ready","name":"web","url":"http://localhost:5173","frontDoor":true}
{"schema":1,"ts":1721664002656,"type":"dev-ready","url":"http://localhost:5173","app":"java -cp target/classes/main demo.Api"}
{"schema":1,"ts":1721664031002,"type":"sidecar-exited","name":"web","pid":48213,"exit":1,"restartInMs":500}
```

Ordering is arrival order across processes; nothing is buffered beyond line assembly. Session
teardown — Ctrl-C, or the app ending the loop — does not emit `sidecar-exited` for the processes it
stops itself. `jk dev --no-sidecars` emits no `sidecar-*` events at all. [Run](run.md#sidecars-devsidecars).

## `details.jsonl`

```text
~/.jk/state/builds/projects/<key>/runs/<build-number>/details.jsonl
```

Same event shape as `--output json`. Default **on**; disable with `JK_CLI_DETAILS=off`
(or `0`). Appended live (`tail -F`). Opens with `session-start`, a `job` meta line after
admit, ends with `session-finish`. Includes **jid**, **buildNumber**, and **etaMs** when
known. Writing is best-effort: a missing project or full disk never fails the user command.

A `jk dev` session keeps a transcript too, whatever the output mode: `session-start` (command
`dev`), every build the loop runs, the [`jk dev`](#jk-dev) events — `sidecar-*`, `app-started` /
`app-exited`, `dev-ready`; `app-output` only under `--output json`, since on a terminal the app
owns stdout — and `session-finish`, on Ctrl-C as well (`exit` 130). The loop's builds are engine
runs, but the transcript is the session's: it lives in the first build's run dir and stays there,
and every later build adds its own `job` line (jid, build number, that run's `detailsPath`) to the
same file, so a session with twenty rebuilds is one `details.jsonl` rather than twenty to stitch in
build order. The dev events carry no `progress`.

With `-v`, the CLI prints `Details: <path>` and `Results: <target/jk-results.md>` after a
run. `jk results -v` / `jk results --details -v` print the path on stderr, then the file. MCP
`jk_details` (resource `jk://runs/latest/details`) is a **budgeted tail**; the CLI flag dumps
the whole file.

## Event vocabulary

The same conceptual events appear on CLI JSONL, web SSE, verbose text, and MCP. Framing
differs; field **names** match.

| Concept | `type` (typical) |
|---------|------------------|
| Request / session start | `buildplan-start` / `workspace-start` |
| Task start / finish | `task-start` / `task-finish` — finish carries `duration_ms` (wall, queue wait included) and `wait_ms` (time blocked on a shared worker; `duration_ms - wait_ms` is the step's own work) |
| Progress ticks | `progress`, `tick-update` |
| Whole-job % | `workspace-progress` |
| Label | `label` |
| User/compiler output | `output` |
| Warning / error | `warn` / `error` (+ `test`, `exceptionClass`) |
| Plan / ETA | `plan`, `eta` (web; CLI via explain) |
| Module | `module-start` / `module-finish` (paired) |
| Workspace end | `workspace-finish` (exactly one, on every outcome) |
| Dev session | `sidecar-started` / `sidecar-output` / `sidecar-ready` / `sidecar-exited`, `app-started` / `app-output` / `app-exited`, `dev-ready` — [`jk dev`](#jk-dev) |
| Guard violation | `guard` — one per violation row of the last `jk guard` run, after the build's events: `code`, `kind`, `baseline` (`new`/`baselined`), `file`, `line`, `at` (fingerprint), `message`, `instead`, `why`, `source` |
| Audit finding | `audit-finding` — one per `jk audit` finding, after the run's plan events: `id`, `package`, `version`, `severity`, `summary`, `fixedIn`, `ignored` (+ `reason`, `until`, `ignoreExpired`) — [Publish](publish.md#json) |

`stage` is a **closed** set, in pipeline order: `resolve`, `generate`, `compile`, `test`,
`package`, `train`, `native`, `image`, `publish`, `other`. The field is always present, but a
task outside the pipeline (`BuildStage.OTHER`) is emitted as `"stage":""` on the live wire; the
journal spells that same case `other`. `jid` is the public job handle on every surface.

SSE `event` name always equals `data.type`. MCP live frames are
`notifications/jk/event` with the same params. Dashboard-only chrome (`status`/`cache`
sample frames) is **not** sent on MCP SSE.

## Guards

Every run that evaluates a guard lane leaves `target/jk-guards.sarif` (SARIF 2.1.0: `ruleId`,
`partialFingerprints`, `baselineState` `new`/`unchanged`, `suppressions` with the baseline reason
as `justification`, `invocations[0].executionSuccessful` false on any `scanner-failed`) and
`target/jk-guards.jsonl` (every violation row). `jk guard --output sarif` prints the SARIF file
whole; `--output json|jsonl` streams the rows as `guard` events. `target/jk-results.md` stays the
agent view. Upload the SARIF to code scanning from CI — [CI](ci.md#guards).

## Timeline

`target/jk-profile.json` — [Build](build.md#timeline).

## Related

[Config](config.md) (how to suppress chrome) · [Engine](engine.md)
