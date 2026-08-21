# Machine output

How JumpKick talks to **agents, scripts, and CI**. Humans at a TTY get a terse visual CLI
(wedges, bars). **Do not scrape it.**

| Consumer | Default channel |
|----------|-----------------|
| Human at a TTY | Terse visual CLI |
| Human debugging | `target/jk-results.md` first; `-v` / `details.jsonl` if needed |
| Agents / scripts / CI | **`target/jk-results.md`**, then `--output json`/`jsonl` or `details.jsonl` |
| Web dashboard | Engine HTTP + SSE (`/api/events`) |
| MCP clients | Tools, resources, SSE — same facts, not a second build model |

Playbook: [Agents](agents.md). MCP tools: [MCP](mcp.md). Failures:
[Troubleshooting](troubleshooting.md).

## `target/jk-results.md`

High-level markdown for the **whole invocation** (compile, test, package, native, image,
publish). Written so you do not need to tail the TTY.

```text
target/jk-results.md
~/.local/state/jk/builds/projects/<key>/runs/<build-number>/jk-results.md
```

The two files are the same report. JUnit XML stays at `target/reports/test-results/`.
There is no separate `test-results.md`.

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

Illustrative lines:

```json
{"schema":1,"ts":1721664000123,"type":"buildplan-start","plan":"test","denominator":42,"tasks":3,"progress":12.5}
{"schema":1,"ts":1721664000901,"type":"error","task":"run-tests","code":"test-failure","message":"…","test":"…","exceptionClass":"org.opentest4j.AssertionFailedError","progress":67.3}
{"schema":1,"ts":1721664001000,"type":"buildplan-finish","plan":"test","success":false,"duration_ms":880,"warnings":0,"errors":1,"progress":100}
```

## `details.jsonl`

```text
~/.local/state/jk/builds/projects/<key>/runs/<build-number>/details.jsonl
```

Same event shape as `--output json`. Default **on**; disable with `JK_CLI_DETAILS=off`
(or `0`). Appended live (`tail -F`). Opens with `session-start`, a `job` meta line after
admit, ends with `session-finish`. Includes **jid**, **buildNumber**, and **etaMs** when
known. Writing is best-effort: a missing project or full disk never fails the user command.

With `-v`, the CLI prints `Details: <path>` and `Results: <target/jk-results.md>`.

## Event vocabulary

The same conceptual events appear on CLI JSONL, web SSE, verbose text, and MCP. Framing
differs; field **names** match.

| Concept | `type` (typical) |
|---------|------------------|
| Request / session start | `buildplan-start` / `workspace-start` |
| Task start / finish | `task-start` / `task-finish` |
| Progress ticks | `progress`, `tick-update` |
| Whole-job % | `workspace-progress` |
| Label | `label` |
| User/compiler output | `output` |
| Warning / error | `warn` / `error` (+ `test`, `exceptionClass`) |
| Plan / ETA | `plan`, `eta` (web; CLI via explain) |
| Module | `module-start` / `module-finish` |
| Workspace end | `workspace-finish` |

`stage` is a **closed** set: `resolve`, `generate`, `compile`, `test`, `package`,
`native`, `image`, `other`. `jid` is the public job handle on every surface.

SSE `event` name always equals `data.type`. MCP live frames are
`notifications/jk/event` with the same params. Dashboard-only chrome (`status`/`cache`
sample frames) is **not** sent on MCP SSE.

## Timeline

`target/jk-profile.json` — [Build](build.md#timeline).

## Related

[Config](config.md) (how to suppress chrome) · [Engine](engine.md)
