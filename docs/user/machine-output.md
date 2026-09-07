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

## `details.jsonl`

```text
~/.jk/state/builds/projects/<key>/runs/<build-number>/details.jsonl
```

Same event shape as `--output json`. Default **on**; disable with `JK_CLI_DETAILS=off`
(or `0`). Appended live (`tail -F`). Opens with `session-start`, a `job` meta line after
admit, ends with `session-finish`. Includes **jid**, **buildNumber**, and **etaMs** when
known. Writing is best-effort: a missing project or full disk never fails the user command.

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
| Guard violation | `guard` — one per violation row of the last `jk guard` run, after the build's events: `code`, `kind`, `baseline` (`new`/`baselined`), `file`, `line`, `at` (fingerprint), `message`, `instead`, `why`, `source` |

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
