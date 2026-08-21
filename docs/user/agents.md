# Agents

How AI coding agents (and scripts) should talk to JumpKick. Humans at a TTY get a terse
visual CLI; **agents should not scrape it.**

Product stance and event names: [Machine output](machine-output.md). MCP tool reference:
[MCP](mcp.md). The “fix my failing build” recipe: [Troubleshooting](troubleshooting.md).

## Default recipe

```text
1. Bind the project          MCP jk_bind {dir}     (or just cd and use the CLI)
2. What happened?            target/jk-results.md  or MCP jk_results
3. Structured failures       MCP jk_diagnostics    (compiler / test)
4. Raw step log (optional)   details.jsonl         or MCP jk_details
5. Rebuild                   jk build / jk test    or MCP jk_run wait=true
6. Graph / ETA               jk why / jk explain   or MCP jk_why / jk_explain
7. Stalled                   jk jobs / jk cancel   or MCP jk_status + jk_job cancel
```

Read **`jk-results.md` first**. It is token-cheap and covers compile, test, and package
outcomes for the whole invocation.

## Channels

| Channel | Use when |
|---------|----------|
| **`target/jk-results.md`** | Always, including one-shot CLI agents with no MCP |
| **MCP tools** | Multi-turn agents; engine already running (`jk engine status` → `mcpUrl`) |
| **`--output json` / `jsonl`** | Live events on stdout (CI, watchers) |
| **`details.jsonl`** | Exhaustive session log; `tail -F` mid-run |
| **`target/jk-profile.json`** | Timings (Perfetto / `chrome://tracing`), not failure triage |

Do **not** set `TERM=dumb` and scrape wedges. Do **not** use `-v` / `--verbose` as the
primary agent API — verbose is for humans and still points at the same files.

```bash
jk test --output json
export JK_OUTPUT=json          # same for any BuildPlan command
# json and jsonl are the same mode: live events, one JSON object per line
```

Every JSONL object includes at least `schema` (always `1` until jk 1.0), `ts`, and `type`.
Most lines also carry aggregate `progress` (0–100 or `null`).

## MCP in one paragraph

MCP is **on by default** when the engine HTTP server is on (loopback, token-gated). Discover
the URL with `jk engine status`. Send `Authorization: Bearer <token>`. Bind once
(`jk_bind`), then omit `dir` on later calls.

Server instructions (also returned from `initialize`): bind first → results/diagnostics on
failure → `jk_run` to rebuild → `jk_why` / `jk_explain` for graph/ETA → `jk_job cancel`
when stalled.

Catalog prompts include **`fix-failing-build`**. Full tool table: [MCP](mcp.md).

## Editing the project as an agent

| Goal | Prefer |
|------|--------|
| Add/remove a dependency | `jk add` / `jk remove`, or MCP `jk_deps` (`apply` defaults **false** — preview first) |
| Change `java = N` | MCP `jk_manifest`, or edit `jk.toml` (`java` is language level, not `jdk`) |
| Scaffold | `jk new -t …`, or MCP `jk_new` (`preview=true` first; `action=templates` lists ids) |
| Format after edits | `jk format` — [Format](format.md) |
| Import Maven/Gradle | `jk import`, or MCP `jk_import` |
| Publish | **CLI only** for real uploads. MCP `jk_publish` / `jk_run kind=publish` is always a **dry-run** |

## Config for CI / headless agents

```bash
export JK_AOT_TRAIN=off     # skip train-on-miss on short-lived engines
export JK_OUTPUT=json       # if you consume stdout
# optional: JK_CLI_DETAILS=off  to skip writing details.jsonl
```

CI cache paths: [CI](ci.md). Machine config preset: MCP `jk_config` `apply_preset=ci`.
