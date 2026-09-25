# Agents

How AI coding agents (and scripts) should talk to JumpKick. Humans at a TTY get a terse
visual CLI; **agents should not scrape it.**

**First:** run `jk skill` (or MCP `skill` / resource `jk://skill`). That is the
system prompt for JumpKick — models have not been trained on this tool. Projects scaffolded
by `jk new` include an `AGENTS.md` that says the same thing.

Product stance and event names: [Machine output](machine-output.md). MCP tool reference:
[MCP](mcp.md). The “fix my failing build” recipe: [Troubleshooting](troubleshooting.md).

## Default recipe

```text
0. Playbook                  jk skill             or MCP skill
1. Name the project          dir={root} on the first MCP call binds it (or just cd and use the CLI)
2. What happened?            MCP run (the reply is the verdict) or jk --agent test
3. Past the cap              MCP diagnostics(file=…)
4. Raw step log (optional)   jk results --details  or MCP details
5. Rebuild                   same selection as the failure (jk test, not --all)
6. Graph / ETA               jk why / jk explain   or MCP why / explain (extended tools/list)
7. Stalled                   jk jobs / jk cancel   or MCP status, then job cancel (extended tools/list)
```

Read the **verdict** first. `run` returns it; `jk --agent` prints the same text. The human
markdown at `target/jk-results.md` is for a person, or for an agent with neither MCP nor
`--agent`. The verdict is one line when the run is OK.

After an edit, MCP **`affected_tests`** (or `jk test --affected`) writes
`target/jk-tests-affected.md` — a short ranked list of test classes for the working tree.
That file is not the last-job report. `--affected` and `--affected-since` cannot be combined.

## Tests — cheapest rung that can catch the bug

Default **`jk test` is the unit suite only.** That is the inner loop. Do not pass
`--all` as a habit.

| When | Command |
|------|---------|
| Which tests did this edit touch? | `jk test --affected` / `--affected-since` (table; does not run) |
| Editing a class / fixing a unit assertion | `jk test` |
| About to push, or the change crossed DB / HTTP / FS | `jk test --guard` |
| UI / compose / contract change, or reproducing CI | `jk test --suite e2e` |
| Never as a habit | `jk test --all` |

Write new tests in the lowest suite that can fail for the reason you care about
(`src/test`, `src/integration`, `src/e2e` — or the simple-layout columns). Tag cost
(`slow`, `network`, `bench`); do not hide Playwright in `src/test`. After a failure,
replay the **same** selection; do not escalate to `--all` until this rung is green.

The named share-the-commit bar (`--guard`) is the product name
for “unit + integration + optional house-rule scripts.” [Why](why.md#test-rungs-the-execute-moat) ·
[Test](test.md).

## Channels

| Channel | Use when |
|---------|----------|
| **`jk skill`** | Once per session — the JumpKick playbook (MCP `skill` / `jk://skill`) |
| **MCP `run`** | The verdict for the run it just finished. `run=<id>` reads an earlier one |
| **`--agent` / `JK_AGENT=1`** | That same text on stdout |
| **`target/jk-results.md`** | Human report when MCP and `--agent` are both off |
| **`jk://runs/latest/results`** | Latest verdict, same text as `run` |
| **`--output json` / `jsonl`** | Live events on stdout (CI, watchers) |
| **`jk results --details`** | Full `details.jsonl`. MCP `details` is a budgeted tail (`jk://runs/latest/details`) |
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
the URL with `jk engine status`. Send `Authorization: Bearer <token>`. Pass `dir` on the first
call — that binds the connection — then omit it; `bind` switches.

The default `tools/list` is `run`, `diagnostics`, `deps`, `why`, and `skill`.
`deps` edits `jk.toml` and relocks in that same call. Everything else (history, explain,
graph, jdk, …) is a `tools/list` with `{"extended": true}`. `initialize` says the same:
run → read the verdict → edit → run.

Catalog prompts include **`fix-failing-build`**. Full tool table: [MCP](mcp.md).

## Editing the project as an agent

| Goal | Prefer |
|------|--------|
| Add/remove/pin a dependency | `jk add` / `jk remove`, or MCP `deps` (applies and relocks; `preview=true` does not write). The version written is an exact pin, or `managed` when a platform BOM of the manifest already supplies it |
| Bump dependency versions | `jk outdated` (read-only; the whole picture is `target/jk-outdated-dependencies.md`), then `jk update` (same major; `--major` to cross; `jk update <name>` for one handle), or MCP `update` (`apply` defaults **false** — read the `jk.toml` hunk, then `apply=true`). Never hand-edit `jk-lock.toml` |
| Change `java = N` | MCP `manifest`, or edit `jk.toml` (`java` is language level, not `jdk`) |
| Scaffold | `jk new -t …`, or MCP `new` (`preview=true` first; `action=templates` lists ids) |
| Format after edits | `jk format` — [Format](format.md) |
| Import Maven/Gradle | `jk import`, or MCP `import` |
| Publish | **CLI only** for real uploads. MCP `publish` / `run kind=publish` is always a **dry-run** |

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
export JK_OUTPUT=json       # if you consume stdout
# optional: JK_CLI_DETAILS=off  to skip writing details.jsonl
```

CI cache paths: [CI](ci.md). Machine config preset: MCP `config` `apply_preset=ci`.
