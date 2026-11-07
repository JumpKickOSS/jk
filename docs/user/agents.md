# Agents

How AI coding agents (and scripts) should talk to JumpKick. Humans at a TTY get a terse
visual CLI; **agents should not scrape it.**

**First:** run `jk skill` (or MCP `skill` / resource `jk://skill`). That is the
system prompt for JumpKick — models have not been trained on this tool. A project's
`AGENTS.md` says the same thing in [jk's section](#jks-section-of-agentsmd).

Product stance and event names: [Machine output](machine-output.md). MCP tool reference:
[MCP](mcp.md). The “fix my failing build” recipe: [Troubleshooting](troubleshooting.md).

## Wiring a project for agents

`jk agents` wires a project for the coding agents that work in it, and `jk agents --remove` takes
jk's entries back out. It is idempotent, so it is also the command to run after upgrading jk.

jk owns one section of `AGENTS.md`, between `<!-- BEGIN:jumpkick -->` and `<!-- END:jumpkick -->`,
and its own entries in the agent files below. Everything else in those files is the project's; jk
never edits it. A file jk cannot parse as strict JSON is left as it was, with a note.

| When | What happens |
|------|--------------|
| `jk new`, `jk init`, `jk import` | `AGENTS.md` with jk's section, the files of each agent in use, and a first code graph (under `target/`, not committed). |
| The dashboard's new-project wizard, MCP `new` | The same files, for the engine's agents setting; the wizard shows them and the notes on the new project's page, and MCP `new` returns them. The code graph comes with the first build. |
| `jk agents [dir]` | The same, on demand, at the workspace root (or `dir`). Prints what it did. |
| `jk agents --all` | Every supported agent, not only those in use here. |
| `jk agents --remove` | jk's entries out of all of the above; a file left empty is deleted. |
| Any build verb (`jk build`, `jk test`, …) | A section that differs from this jk's guide is rewritten. A file without the section is left alone. |

`JK_AGENTS=none` makes `jk agents`, `new`, `init` and `import` write `AGENTS.md` only; `JK_AGENTS=all` wires every
agent. The wizard and MCP `new` have no shell of their own, so they read `JK_AGENTS` and detect agents in
the environment the engine was started in. An agent counts as present when its marker exists in the project (`.claude/`, `.cursor/`, …),
when the calling shell belongs to it, or when its CLI is on `PATH` (`claude`, `cursor-agent`,
`gemini`, `opencode`, `agy`, `codex`).

### What you still do by hand

| Situation | Command |
|-----------|---------|
| A project that existed before this | `jk agents` |
| Wire every supported agent, not only the ones here | `jk agents --all` |
| Codex, or any agent jk does not write files for | `codex mcp add jk -- jk mcp`, or `jk mcp` in the client's MCP settings |
| Commit the generated files | `git add AGENTS.md CLAUDE.md .mcp.json .claude .cursor .vscode .gemini opencode.json` (only the ones that exist) |
| Undo it | `jk agents --remove` |

Claude Code, Cursor, VS Code, Gemini CLI and opencode have project files written for them (below).
Any other agent reads `AGENTS.md` and needs the one MCP command above.

### Claude Code

| File | jk's entry |
|------|-----------|
| `CLAUDE.md` | `@AGENTS.md`, so Claude Code reads the same guide as every other agent |
| `.mcp.json` | the `jk` server (`jk mcp`) |
| `.claude/settings.json` | `mcp__jk` and `Bash(jk *)` allowed, the `jk` server pre-approved, and one `PreToolUse` hook on `Bash` and `Grep` |

The hook is `jk agent-hook`, so its behaviour changes with jk, not with the file. It denies `mvn` and
`gradle` (with the jk command to use instead) and answers a grep for a symbol, such as `OrderService`
or `Order.total`, with a pointer to `jk graph callers` / `read` / `rename`. It never blocks a grep.
Text searches pass untouched.

### Cursor, VS Code, Gemini CLI, opencode

| Agent (in use when) | File | jk's entry |
|---|---|---|
| Cursor (`cursor-agent` on `PATH`, or `.cursor/` exists) | `.cursor/mcp.json`, `.cursor/rules/jk.mdc` | `mcpServers.jk`, the `jk mcp` server; an always-on rule, owned by jk: call `rename` first, never grep after it |
| VS Code (`.vscode/` exists) | `.vscode/mcp.json` | `servers.jk`, a `stdio` server running `jk mcp` |
| Gemini CLI (`.gemini/` or `GEMINI.md` exists) | `.gemini/settings.json` | `mcpServers.jk` with `trust` set, and `AGENTS.md` added to `context.fileName` so Gemini reads the guide |
| opencode (`opencode.json`, `opencode.jsonc` or `.opencode/` exists) | `opencode.json` | `mcp.jk`, a local server running `jk mcp` |

| agy (`agy` on `PATH`, or `.agents/rules/` exists) | `.agents/rules/jk.md` | an always-on rule, owned by jk: take `callers` and `rename` answers as final instead of re-opening the files they name |

Most agents read `AGENTS.md` and a project's MCP files (`.mcp.json` and the rest) only in a folder
you have trusted, which jk cannot do on your behalf. `jk agents`, `jk new` and `jk import` list that
step, and any other step left to you (Codex's `codex mcp add`), as numbered notes after the files
they wrote.

Only Claude Code gets the hook and an allow-list written; the others get the MCP server and the
guide. `jk agents --remove` leaves `AGENTS.md` in Gemini's `context.fileName`, since a project may
list it for its own reasons.

Commit these files after a refresh as you would any generated change. Markers that do not pair up
(a begin with no end, or either one twice) are refused by `jk agents` and skipped by the build; fix
them by hand.

## Default recipe

```text
0. Playbook                  jk skill             or MCP skill
1. Name the project          register MCP as `jk mcp` (pinned to the project; no dir), or cd and use the CLI
2. What happened?            MCP run (the reply is the verdict) or jk -a test
3. Past the cap              jk results -A      or MCP diagnostics
4. Raw step log (optional)   jk results -d  or MCP details
5. Rebuild                   same selection as the failure (jk test, not -A)
6. Graph / ETA               jk why / jk explain   or MCP why / explain (extended tools/list)
7. Stalled                   jk jobs / jk cancel   or MCP status, then cancel (extended tools/list)
```

Before changing a method, ask who calls it: MCP `callers` or `jk graph callers Type.method`
(`-t` / `transitive=true` for everything that can end up running it). The answer comes from the
compiled classes and is complete, so it replaces grepping for the name and reading each match
([Code graph](graph.md#callers)). To rename a method, MCP `rename` or `jk graph rename Type.method
newName` makes every edit in one call and quotes any use it could not tell apart
([Code graph](graph.md#rename)).

Read the **verdict** first. `run` returns it; `jk -a` prints the same text. The human
markdown at `target/jk-results.md` is for a person, or for an agent with neither MCP nor
`--agent`. The verdict is one line when the run is OK. A `run` that answers `TIMEOUT … jid=N`
is still going: `run(jid=N)` waits on it again and returns its verdict ([MCP](mcp.md#waits-and-keep-alive)).

After an edit, MCP **`affected_tests`** (or `jk test -f`) writes
`target/jk-tests-affected.md` — a short ranked list of test classes for the working tree.
That file is not the last-job report. `--affected` and `--affected-since` cannot be combined.

## Tests — cheapest rung that can catch the bug

Default **`jk test` is the unit suite only.** That is the inner loop. Do not pass
`--all` as a habit.

| When | Command |
|------|---------|
| Which tests did this edit touch? | `jk test -f` / `--affected-since` (table; does not run) |
| Editing a class / fixing a unit assertion | `jk test` |
| About to push, or the change crossed DB / HTTP / FS | `jk test -g` |
| UI / compose / contract change, or reproducing CI | `jk test -s e2e` |
| Never as a habit | `jk test -A` |

Write new tests in the lowest suite that can fail for the reason you care about
(`src/test`, `src/integration`, `src/e2e` — or the simple-layout columns). Tag cost
(`slow`, `network`, `bench`); do not hide Playwright in `src/test`. After a failure,
replay the **same** selection; do not escalate to `--all` until this rung is green.

The named share-the-commit bar (`--guard`) is the product name
for “unit + integration + optional house-rule scripts.” [Why](why.md#test-rungs) ·
[Test](test.md).

## Channels

| Channel | Use when |
|---------|----------|
| **`jk skill`** | The JumpKick playbook, when a task needs more than `AGENTS.md` (MCP `skill` / `jk://skill`) |
| **MCP `run`** | The verdict for the run it just finished. `run=<id>` reads an earlier one |
| **`--agent` / `JK_AGENT=1`** | That same text on stdout |
| **`target/jk-results.md`** | Human report when MCP and `--agent` are both off |
| **`jk://runs/latest/results`** | Latest verdict, same text as `run` |
| **`-O jsonl`** | Live events on stdout (CI, watchers) |
| **`jk results -d`** | Full `details.jsonl`. MCP `details` is a budgeted tail (`jk://runs/latest/details`) |
| **`target/jk-profile.json`** | Timings (Perfetto / `chrome://tracing`), not failure triage |

Do **not** set `TERM=dumb` and scrape wedges. Do **not** use `-v` / `--verbose` as the
primary agent API — verbose is for humans and still points at the same files.

```bash
jk test -O jsonl
export JK_OUTPUT=jsonl          # same for any BuildPlan command
```

Every JSONL object includes at least `schema` (always `1` until jk 1.0), `ts`, and `type`.
Most lines also carry aggregate `progress` (0–100 or `null`).

## MCP in one paragraph

MCP is **on by default** when the engine HTTP server is on (loopback, token-gated). Discover
the URL with `jk engine status`. Send `Authorization: Bearer <token>`. Pass `dir` on the first
call — that binds the connection — then omit it; `bind` switches.

The default `tools/list` is `run`, `diagnostics`, `deps`, `callers`, `rename`, and `skill`: every
card is paid on every turn, so the list holds what a fix-and-rerun loop calls plus `callers`, which
answers who calls a method, complete, in one call instead of a grep and reads, and `rename`, which
renames a method and every use in one call instead of an edit per use. `run(kind=snapshot)` installs for a
sibling project.
`deps` edits `jk.toml` and relocks in that same call. Everything else (history, explain,
jdk, why, …) is a `tools/list` with `{"extended": true}`. `initialize` says the same:
run → read the verdict → edit → run.

Catalog prompts include **`fix-failing-build`**. Full tool table: [MCP](mcp.md).

## Editing the project as an agent

| Goal | Prefer |
|------|--------|
| Add/remove/pin a dependency | `jk add` / `jk remove`, or MCP `deps` (applies and relocks; `preview=true` does not write). A coordinate or version no configured repository serves is refused before anything is written, naming the near-miss coordinate or the newest release. The version written is an exact pin, or `managed` when a platform BOM of the manifest already supplies it |
| Bump dependency versions | `jk outdated` (read-only; the whole picture is `target/jk-outdated-dependencies.md`), then `jk update` (same major; `--major` to cross; `jk update <name>` for one handle), or MCP `update` (`apply` defaults **false** — read the `jk.toml` hunk, then `apply=true`). Never hand-edit `jk-lock.toml` |
| Change `java = N` | MCP `manifest`, or edit `jk.toml` (`java` is language level, not `jdk`) |
| Node.js for a front end | `jk node pin` (or MCP `node action=pin`); run its scripts with `jk node run <script>` and one-off package binaries with `jk npx <pkg>` (`jk npm …` for npm itself), never a host `npm` ([Node.js](node.md)) |
| Scaffold | `jk new -t …`, or MCP `new` (`preview=true` first; `action=templates` lists ids) |
| Formatting | Nothing to run: with `[format] enforce`, `jk guard` formats what changed. Without it, leave the project's formatting alone — [Format](format.md#enforce-with-jk-guard) |
| Import Maven/Gradle | `jk import`, or MCP `import` |
| Publish | MCP `publish` is a **dry-run**; `publish(upload=true)` uploads to the `[publish]` repository. Central and ad-hoc URLs stay on the CLI |

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
export JK_OUTPUT=jsonl       # if you consume stdout
# optional: JK_CLI_DETAILS=off  to skip writing details.jsonl
```

CI cache paths: [CI](ci.md). Machine config preset: MCP `config` `apply_preset=ci`.
