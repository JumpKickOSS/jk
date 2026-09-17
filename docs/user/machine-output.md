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
publish). The first screen is the outcome, a non-zero **exit N**, and why it failed — not a
JUnit pass rate. Written so you do not need to tail the TTY.

```bash
jk results              # print the latest report
jk results --details    # print that run's details.jsonl
```

On disk (the command reads the journal copy; `target/` is a latest-copy fallback):

```text
target/jk-results.md
~/.jk/state/builds/projects/<key>/runs/<build-number>/jk-results.md
```

The header's second line names who asked: `trigger: cli`, or `trigger: mcp · session:
claude-code 3f9a` for an agent's connection (`bsp · IntelliJ-BSP 7b2c` for an IDE), then
`commit:` and the jk version. The same `trigger`/`session` fields sit on the journal record
(`jk history`, `GET /api/history`, `jk_history`) and on the `session-start` line of
`details.jsonl` — one vocabulary, every surface. [Web](web.md#who-asked). The line after it is
`tokens ≈ N`: the whole file's size at a fixed 3.6 characters per token, so an agent can decide
between this file and `details.jsonl` before reading either.

A coverage run (`jk test --coverage`, or a module with `[test] coverage = true`) adds a
`Coverage:` line to the headline and a `## Coverage` table after `## Tests`: one row per module —
`| Module | Lines | Branches |` as `85.2% (1204/1413)` — an `**all**` row for a workspace, and,
when the journal holds an earlier coverage run of the project, `_Δ vs run #N_` with a `Δ` column
of signed percentage points (`+1.3`, `−0.5`, `±0.0`, `new`) after each measure. `## Files` points
at the HTML (`target/reports/coverage/index.html`; per module under
`target/<module>/reports/coverage/`). The record carries the same rows as `coverage[]`
(`dir`, `label`, `linesCovered`, `linesMissed`, `branchesCovered`, `branchesMissed`, `html`).
[Test](test.md#coverage---coverage-test-coverage).

A step that failed without a diagnostic of its own is one row of `## Failed steps`; a step that
explained itself is also a `## Failures` entry headed `<step> — <module>`, its message fenced, then
its output. A compiler diagnostic's `file`, `line` and `col` are read from its header — javac's
`path:line: error:` with the caret line supplying the column, groovyc's `path: line:` with its
`@ line N, column M` trailer, and kotlinc's `file:///path:line:col message` — and `file` is a
filesystem path whatever the header wrote. A compiler error whose repair is mechanical ends with a `→` line, the way a guard
violation carries `instead`: javac's cannot find symbol, package does not exist, incompatible types,
unreported exception, missing return statement, variable might not have been initialized and
non-static referenced from a static context; kotlinc's unresolved reference, type mismatch, unsafe
call on a nullable receiver and no value passed for a parameter. A javac row is chosen by javac's
own key for the diagnostic (`compiler.err.cant.resolve.location`), which the compile worker records
beside every diagnostic and the record carries as `key`; the shape of the message is the fallback
for kotlinc. The hint quotes the symbol, package or types from the compiler's own message —
`symbol:` and `location:` for cannot find symbol — and names `jk add` when a dependency is the
likely repair. For package does not exist the compile step looks the package up before the
diagnostic is journaled: a lock row whose jar holds the package and is not on this module's compile
classpath is written under the error as `provided by: group:artifact (in the lock, not on this
module's compile classpath)`, else the library catalog module whose group prefixes the package as
`provided by: group:artifact (library catalog)`, and the hint names that coordinate for `jk add`.
Each lock jar's package list is read once and kept under the store's `package-index/`. A failed test's stack is cut to 24 lines, and the cut always keeps the frame inside the
test class together with the assertion frame above it — the middle is elided with a frame count —
so the test's own `File.java:NN` is in the file however deep the framework's frames run. A test JVM whose launcher never ran a test — a JUnit engine that could not start, a
launcher missing from the classpath — is that shape with code `test-launcher`: the exit, the
exception and engine the runner named, the two conflicting JUnit coordinates when the lock names
them, and the fix (`jk why <coordinate>`), with the fork's output as the fenced block. It is never
counted as a red test, so there is no `Tests:` line for it. MCP `jk_diagnostics` returns it as one
row (`code`, `message`, `detail`, `exceptionClass`). [Test](test.md#when-the-launcher-cannot-start).

The headline outcome is the run's own verdict. `FAIL` with the failed step's exit (`1`, or `4` for
red tests) is a build that stopped at a failure; `CANCELLED` with `exit 130` is a run a user or a
deadline interrupted, and only that. When one module fails, the build stops admitting the others
and the modules already in flight are stopped where they stand: their unfinished steps are one
line under `## Failed steps` — `_N steps stopped by the failure._` — the module reads `SKIPPED`
in `## Modules` and in the `Modules:` count, and a test run that was stopped is not a failure
under `## Failures`. `jk build --continue` runs every module to the end instead. Two verdicts are
the workspace's own rather than a step's: `built nothing` (`jk build` on a workspace with no module
that has sources) and `no tests ran` (`jk test` on a workspace in which no module ran a test), both
`FAIL` with `exit 2` and the one-line reason naming the modules — the project's shape, not a red
step. [Workspaces](workspaces.md#nothing-to-build).

The two markdown files are the same report. JUnit XML stays at `target/reports/test-results/`.
There is no separate `test-results.md`. MCP: **`jk_results`** and resource
`jk://runs/latest/results`. After a test run, prefer this file over `--all` guesswork:
default `jk test` is the unit suite; climb with `--suite`. [Test](test.md).

### Since the previous run

When the journal holds an earlier run from the same origin — the same trigger and session (an MCP
connection, an IDE window; a plain `cli` run matches the `cli` runs before it) — the report
carries a `## Since the previous run` section, counts first and bounded lists after:

```markdown
## Since the previous run

_vs #12 (failed, 8.4s) · this run 6.1s (−2.3s)_

- Files changed: **1** — `src/test/java/com/example/CalcTest.java`
- Diagnostics: **0** appeared, **1** gone
  - gone: error · run-tests · com.example.CalcTest#subtracts() · expected: <1> but was: <0>
- Tests: **1** fixed, **0** broke, **0** new, **0** gone
  - fixed: `com.example.CalcTest#subtracts()`
```

Files are compared by content hash between the two runs' `sources.tsv` snapshots (project tree
minus build output, hidden and `node_modules` directories; absent when either side has none);
diagnostics by severity, step, site and message (a changed stack trace is the same diagnostic);
tests by `Class#display` verdict from `test-outcomes.tsv` (absent when either run recorded no
tests). Lists show at most eight rows and say `+N more`. `- Nothing changed: same files,
diagnostics and tests.` is a re-run of the same tree. MCP `jk_results` returns the same facts
as `delta` ({ `previousBuildNumber`, `previousSuccess`, `previousMillis`, and per list
`{ count, shown }` for `files`, `appeared`, `gone`, `broke`, `fixed`, `added`, `dropped` }), and
the web dashboard renders them as the run's iteration strip ([Web dashboard](web.md)).

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

### Toolchain provisioning

When a build-kind verb — `jk build`, `jk test`, `jk run`, `jk install`, `jk dev` / `jk watch`,
`jk explain` — needs a JDK or GraalVM that is not on disk (the manifest's or lockfile's `jdk`
pin, a workspace member's own pin, the `java` floor, a `.jdk-version` file, the `--jdk`
switch, the GraalVM a native build links with), the client installs it before the build request
goes out, and the stream opens with a one-task **`toolchain`** plan ahead of the build's own
`buildplan-start` / `workspace-start`:

```json
{"schema":1,"ts":1721663990000,"type":"buildplan-start","plan":"toolchain","denominator":1,"tasks":1,"progress":null}
{"schema":1,"ts":1721663990000,"type":"task-start","task":"ensure-jdk","stage":"resolve","ticks":1,"progress":null}
{"schema":1,"ts":1721663990100,"type":"label","task":"ensure-jdk","label":"downloading Temurin 21 ▰▰▰▰▱▱▱▱▱▱ 42%","progress":null}
{"schema":1,"ts":1721663996000,"type":"label","task":"ensure-jdk","label":"installing Temurin 21 ▰▰▰▰▰▰▰▰▰▰ 100%","progress":null}
{"schema":1,"ts":1721663999000,"type":"task-finish","task":"ensure-jdk","stage":"resolve","status":"SUCCESS","duration_ms":9000,"wait_ms":0,"progress":null}
{"schema":1,"ts":1721663999000,"type":"buildplan-finish","plan":"toolchain","success":true,"duration_ms":9000,"warnings":0,"errors":0,"progress":null}
```

- `progress` is `null` on every one of the plan's lines, its finish included: the plan runs
  before the job is admitted, so there is no job percent yet, and the build's own first lines
  start their rider from `null` too — the toolchain plan never leaves a percent behind.
- The `label` lines are the ones the engine's own `ensure-jdk` task emits when it does the
  download (a client with no terminal — HTTP, MCP); inside the build that follows, that task
  finishes `SKIPPED` because the toolchain is now installed.
- A workspace pre-flights every member that names a `jdk` or `java` of its own (or carries a
  `.jdk-version` file) with the same values the engine resolves it with — the summary's
  normalized spec, so `jdk = "=temurin-21"` and `jdk = "temurin"` beside `java = 21` both
  install Temurin 21 once. Members that inherit the workspace toolchain are covered by the
  root's pre-flight.
- A toolchain already on disk emits no `toolchain` plan at all. The human lines of the install
  (why it is happening, the settled "has been installed to" chip) go to **stderr**.
- A degradation the install goes ahead despite — the JDK feed unreachable and answered from its
  cache — is a `warn` line (`task:"ensure-jdk"`, `code:"jdk"`) inside the plan, and a human
  line on stderr.
- A failed install emits an `error` line (`task:"ensure-jdk"`, `code:"jdk"`), ends the plan
  with `buildplan-finish` `success:false`, and jk exits without opening a build envelope.

`jk jdk install --output json` streams its own **`jdk-install`** plan in the same shape
(`fetch-catalog`, `select`, `install`, `set-default` tasks; the `install` task carries the same
`downloading …` / `installing …` labels).

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
(or `0`). Appended live (`tail -F`). Opens with `session-start` (command, argv, and the
run's origin: `trigger`, plus `session` when the requester has one), a `job` meta line after
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
