# Agents

How AI coding agents (and scripts) should talk to JumpKick. Humans at a TTY get a terse
visual CLI; **agents should not scrape it.**

**First:** run `jk manual` (or MCP `jk_manual` / resource `jk://manual`). That is the
system prompt for JumpKick — models have not been trained on this tool. Projects scaffolded
by `jk new` include an `AGENTS.md` that says the same thing.

Product stance and event names: [Machine output](machine-output.md). MCP tool reference:
[MCP](mcp.md). The “fix my failing build” recipe: [Troubleshooting](troubleshooting.md).

## Default recipe

```text
0. Playbook                  jk manual             or MCP jk_manual
1. Bind the project          MCP jk_bind {dir}     (or just cd and use the CLI)
2. What happened?            target/jk-results.md  (read/grep) or jk results or MCP jk_results
3. Structured failures       MCP jk_diagnostics    (compiler / test)
4. Raw step log (optional)   jk results --details  or MCP jk_details
5. Rebuild                   same selection as the failure (jk test, not --all)
6. Graph / ETA               jk why / jk explain   or MCP jk_why / jk_explain
7. Stalled                   jk jobs / jk cancel   or MCP jk_status + jk_job cancel
```

Read **`target/jk-results.md` first** (same markdown as `jk results`). File tools beat
shelling out `jk results` when MCP is not connected. The report is token-cheap and covers
compile, test, and package outcomes for the whole invocation.

After an edit, MCP **`jk_affected_tests`** (or `jk test --affected`) writes
`target/jk-tests-affected.md` — a short ranked list of test classes for the working tree.
That file is not the last-job report. `--affected` and `--affected-since` cannot be combined.

## Tests — cheapest rung that can catch the bug

Default **`jk test` is the unit suite only.** That is the inner loop. Do not pass
`--all` as a habit.

| When | Command |
|------|---------|
| Which tests did this edit touch? | `jk test --affected` / `--affected-since` (table; does not run) |
| Editing a class / fixing a unit assertion | `jk test` |
| About to push, or the change crossed DB / HTTP / FS | `jk test --gate` (alias `--pre-merge`) |
| UI / compose / contract change, or reproducing CI | `jk test --suite e2e` |
| Never as a habit | `jk test --all` |

Write new tests in the lowest suite that can fail for the reason you care about
(`src/test`, `src/integration`, `src/e2e` — or the simple-layout columns). Tag cost
(`slow`, `network`, `bench`); do not hide Playwright in `src/test`. After a failure,
replay the **same** selection; do not escalate to `--all` until this rung is green.

The named share-the-commit bar (`--gate`, alias `--pre-merge`) is the product name
for “unit + integration + optional house-rule scripts.” [Why](why.md#test-rungs-the-execute-moat) ·
[Test](test.md).

## Channels

| Channel | Use when |
|---------|----------|
| **`jk manual`** | Once per session — the JumpKick playbook (MCP `jk_manual` / `jk://manual`) |
| **`target/jk-results.md`** | Preferred triage when MCP is off: read/grep the file (same markdown as `jk results`) |
| **`jk results`** | CLI print of that report when you cannot read the file |
| **MCP `jk_results`** | Multi-turn agents; engine already running (`jk engine status` → `mcpUrl`). Resource: `jk://runs/latest/results` |
| **`--output json` / `jsonl`** | Live events on stdout (CI, watchers) |
| **`jk results --details`** | Full `details.jsonl`. MCP `jk_details` is a budgeted tail (`jk://runs/latest/details`) |
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

## Hooks and protected files

`jk guard hooks install` writes two git hooks into the repository's hooks directory (the
common git dir, so worktrees share them):

- **`commit-msg`** runs `jk guard commit-msg <file>`: the project's `commit` rules judge the
  message (forbidden trailers, required or banned patterns) and a violation refuses the commit
  with the rule's `Instead`.
- **`pre-commit`** protects the guard files: a staged `jk-guards-baseline.toml` is refused unless
  `jk guard freeze` produced it, and a staged line shaped like a guard suppression comment is
  refused — there is no suppression syntax, only `allow` entries with a reason.

`jk guard hooks` prints the scripts. `install` also writes `target/jk-guards.protected`, the list
of files an agent must not edit by hand, for harnesses that read one. The hooks are advisory:
`--no-verify` skips them, and the engine's guard lanes plus CI remain the enforcement.

## Config for CI / headless agents

```bash
export JK_AOT_TRAIN=off     # skip train-on-miss on short-lived engines
export JK_OUTPUT=json       # if you consume stdout
# optional: JK_CLI_DETAILS=off  to skip writing details.jsonl
```

CI cache paths: [CI](ci.md). Machine config preset: MCP `jk_config` `apply_preset=ci`.
