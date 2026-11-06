# Agent integration

How jk talks to coding agents (Claude Code, Cursor, Codex, grok, agy, muse), and the rules any change to
that surface must keep. The surface is everything an agent reads: the `AGENTS.md` section
(`AgentsGuide`), the MCP tools' descriptions and answers (`server/engine/.../http/mcp/`), the files
`jk agents` writes (`AgentSetup`), and the graph's answers (`plugins/graph`, `shared/code-graph`).

Change it only with a measurement before and after: [agent-bench.md](agent-bench.md).

## Who it is tuned for, in order

1. **Claude Code.** Never trade its results for another agent's.
2. **Cursor.** It must win as well: it is how teams adopt jk.
3. **Codex, grok, agy, muse**, in that order.

One generic baseline serves every agent; an agent gets its own file only where a measurement shows it
needs one. Today that is Cursor (`.cursor/rules/jk.mdc`) and agy (`.agents/rules/jk.md`): without its
rules file Cursor greps before it calls `rename`.

## Rules for what an agent reads

**Finish the task in the answer.** An agent trusts a concrete next step but does not stop on its own:
told to "run the tests", it runs them, then a full build, then a guard, to be sure. So a tool that
changes code does its own check and says so. MCP `rename` renames, runs the test classes it edited and
guard over the same modules within the caller's wait, and ends `Checked by jk: OK test …; OK guard ….
The task is done: no other build, test or search is needed.` (`McpRenameCheck`). That one sentence took
every agent's rename to a single tool call.

**Name the exact scope.** `rename` names the test classes it edited (`classes=`), not the modules'
whole suites: a rename changes no behaviour, every test source still compiles, and the tests that call
the method are the ones worth running. A module suite can be 90,000 tests.

**List nothing an agent will act on in vain.** An agent reads and edits every line an answer lists.
`rename` renames what can only mean the method (`{@code m}`, a bare `m(` or `#m` in its own types'
files, `Type.m` anywhere) and lists only the leftovers that are code-like and unresolved: never the
English word (`contains`), never another type's method (`String#indexOf`, `list.contains(`), never a
changelog. Errors are short and say what to pass (`Did you mean a.b.Type.m?`), never a list of modules.

**Answers are final.** A graph answer says it is complete from the compiled classes, so an agent does
not re-read the files it names. Keep that sentence on every graph answer, and make it true: a
question compiles a module, or a module's tests, that has no classes first, and only when that
compile cannot run does the answer drop the sentence and name the module or the tests' directory.

**One number per job.** An MCP reply names a job by its `jid` only, and `run(jid=N)`, `run(run=N)` and a
duplicate `run(kind=…)` all wait on that job. Two numbers for one job send agents chasing the wrong one.

**Nothing of the graph's is in the tree.** Its fragments live in the action cache and the linked
graph under `target/`, which agents' searches already skip, so no file or rule has to keep an agent
out of a store.

**Generic wording beats coercion.** A plain sentence in a tool answer moves every agent at once; a
rule written for one agent rarely transfers and is often worse than none. Prefer changing the answer.

**Budgets.** The `AGENTS.md` section stays under 1000 bytes and each MCP tool card under its budget
(`McpToolRegistryTest`); every word is paid for on every agent turn.

## Per-agent facts

| Agent | Loads `AGENTS.md` | Its own file | MCP | Notes |
|---|---|---|---|---|
| Claude Code | yes | `CLAUDE.md` imports `AGENTS.md`; `.claude/settings.json` (allow-list, hook) | `.mcp.json` | Uses the graph as shipped |
| Cursor | yes | `.cursor/rules/jk.mdc` (required) | `.cursor/mcp.json` | Cancels an MCP call at 60 s, so `run` answers `TIMEOUT … run(jid=N)` by 55 s; looks up each tool's schema before its first use (a turn each); its CLI takes ~11 s to start |
| Codex | yes, natively | none | user-level: `codex mcp add jk -- jk mcp` | No file tools: reads and searches through its shell (`zsh -lc '…'`) and edits with `apply_patch` |
| grok | only in a trusted folder | `.grok/rules/*.md` (not needed) | its config | Reaches MCP tools through `search_tool`/`use_tool` |
| agy | yes | `.agents/rules/jk.md` (helps) | its config | Reads its own MCP descriptors before a call; glances at config files after an answer |
| muse | only in a trusted workspace | none | its config | Tends to call `callers` before `rename` and to re-run tests after "done" |

`jk agents` writes each agent's files when the agent is detected (or with `-A`) and prints the steps
only the user can take (grok's and muse's trust, Codex's `codex mcp add`).

The measurements behind these rules are in KanArtist, `projects/jk/docs/agent-tuning-2026-11.md`.
