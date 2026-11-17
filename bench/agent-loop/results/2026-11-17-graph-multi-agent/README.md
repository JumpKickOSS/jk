# Graph bench across agents, 2026-11-17 (bocabox, Fedora)

Does jk 0.52.0's code graph save tokens, turns, wall time and money for each agent, against jk 0.51.1's graph
and against no graph tools? Ten agent/model configs, three arms, three tiers of tasks. TABLE.md has the numbers;
`rows-tier*.jsonl` are the raw rows (one per run, change-tasks' row format); `runner.py`, `plan.py` and
`tabulate.py` are what ran the matrix and built the table.

## Arms

| Arm | jk | What it is |
|---|---|---|
| **A** `jk` | 0.52.0 | the product as installed: `jk mcp`'s default list, graph included |
| **B** `jk-before` | 0.51.1, in its own home | the graph as it was before 0.52.0 (anchors, the wider rename and group roots are new in 0.52.0) |
| **C** `jk-nograph` | 0.52.0 | every graph tool hidden from MCP and refused, `jk graph` refused on a shell |

C is "no graph *tools*", not "no graph *data*": jk's loop tools (run, skill, …) stay listed, AGENTS.md and the
skill text still name `callers`, and `target/incremental/classes-refs/*.refs` (jk's compiled reference files)
are on disk where an agent can grep them. Some agents try the hidden graph in C and are refused; those calls
count in `jk_calls`.

## Configs

| Config | CLI (version) | Model id | Settings |
|---|---|---|---|
| claude2 / sonnet-5-5 | Claude Code 2.1.326 through `JK_BENCH_CLAUDE=claude2` (temporary) | claude-sonnet-5-5 | effort medium |
| claude2 / opus-5-5 | same | claude-opus-5-5 | effort medium |
| codex / terra | codex-cli 0.166.0 | gpt-6.1-terra | effort **low**: the catalog's default for this model (`codex debug models`), as the owner asked for default recommended settings |
| codex / sol | same | gpt-6.1-sol | effort **low** (catalog default) |
| grok / 4.7 | grok 1.0.77 | grok-4.7 | effort high, context 256000 |
| grok / 4.5 | same | grok-4.5 | effort high |
| cursor / composer | cursor-agent 2026.11.13-eacade1 | composer-2.5 (never a `-fast` id) | effort is part of the id ("default") |
| cursor / grok-4.7-high | same | grok-4.7-high (never `-fast`) | high, in the id |
| agy / hydrogen | agy 1.5.10 | gemini-4-hydrogen-medium | effort medium |
| muse / spark | Muse Code 1.4.23 (1.4.23-R8977.1) | muse-spark-1.3-contributor | effort high (the model profile's default) |

Auto-updates were off for the matrix: `MUSE_NO_AUTO_UPDATE=1` (the muse launcher) and `AGY_CLI_DISABLE_AUTO_UPDATE=1`
(agy). Every row records `cli_version`; see "CLI versions" in TABLE.md for whether any changed mid-matrix.

Every agent runs in a throwaway home (Claude Code in a throwaway `CLAUDE_CONFIG_DIR` with a copy of the login),
so no user settings, memory, rules or plugins reach a run. Cursor's account brings a `plugin-github-github` MCP
server into every home; the cursor driver disables it per run (`cursor-agent mcp disable`, 26f422577), and a
run's transcript lists only jk's server.

## Tasks and scope

The plan (990 runs, about 32 h) was cut with its cuts 1 and 2: tier-0 repeats to 1, and arm B only where it can
differ.

| Tier | Tasks | Arms | Repeats |
|---|---|---|---|
| 0 smoke (`smoke-test`, 166-line fixture) | smoke-callers, smoke-rename, smoke-rename-field, smoke-rename-class | A, B, C | 1 |
| 1 (`change-tasks`) | petclinic-callers-addpet, gs-multi-module-rename-message, gs-uploading-rename-loadasresource | A, C | 3, interleaved |
| 2 sliver (`change-tasks`) | petclinic-rename-telephone, petclinic-anchor-relaxed-env, petclinic-anchor-composed-route, petclinic-anchor-column-case | A, B, C | 3, interleaved |

Interleaved: every cell's run k before any cell's run k+1, across all configs, with the arm order rotated per
round and per config, so cache warmth from the run before does not always favour one arm.

Caps: tier 0 at 8 minutes and 30 turns a run, tier 1 at 15 minutes and 60 turns, the sliver at 20 minutes and 60
turns. A run over its cap is recorded as such.

**Docker.** This host's Docker is podman. Testcontainers (MySQL) works on it, but petclinic's
PostgresIntegrationTests uses Spring Boot's compose support, which runs `docker compose … config --format=json`,
and podman-compose has no such command, so the test fails before it starts. So:
- `petclinic-rename-addvisit` (tier 1 in screen-test's set; its verify is the whole suite) is **dropped**, and
  replaced by `gs-multi-module-rename-message` (a method rename across two modules, from the same task file).
- The three anchor tasks in the sliver ran the whole suite too. They now name their test classes: their
  acceptance class and every petclinic test class but PostgresIntegrationTests and MySqlIntegrationTests, as
  the tier's comment in tasks.toml already said (26f422577). On a host without Docker those two tests assume
  themselves away, so this matches the hosts where the tasks were written. An agent that runs the full suite
  itself still sees PostgresIntegrationTests fail, in every arm alike.

## Cost

Est. cost is the API list price of the tokens each CLI reported (`prices.toml`: each model's row has its source
page; read 2026-11-17, standard tier, long-context surcharges not applied). For subscription CLIs (Claude Code,
Codex, Cursor, agy, muse) it is an **API-equivalent estimate, not a bill**. Cache reads are priced at the cache
rate, so cost moves with cache warmth from the run before: the same cell can differ several-fold in cost with
nearly equal tokens (Sonnet's smoke-callers in the prep: $0.031, then $0.004). Muse's contributor model costs
$0.002 per million cached tokens, so its cost hardly moves with tokens. cursor / grok-4.7-high is priced as
grok-4.7.

## Harness fixes during the run

- **Claude Code rows voided by their reply** (fixed on the branch). The driver checked the agent's final reply
  for login/model-rejection phrases ("does not exist", "model id", …), so a finished run whose answer said
  e.g. "`Journal.java` does not exist" was recorded as "the claude-code CLI did not start", with no tokens and
  no grading. Four tier-0 Claude rows hit it; the check now applies only when the run never got past its
  first turn, and the four cells were rerun (both rows are in `rows-tier0.jsonl`; the table keeps the rerun).

## Host

Sequential, one agent run at a time. The host is also the release-gate host: before every run the runner waits
while `/var/tmp/jk-review2/GATE_RUNNING` exists, so no run overlaps a gate. Pauses: tier 0, 29 min before run 100.

## Reading the numbers

See TABLE.md's notes; the honest reading per tier is in the next section.

## Findings

### Tier 0 (one run per cell)

Every one of the 120 runs is green, so tier 0 says nothing about success; it says whether and how cheaply each
agent reaches the answer. One run per cell is a smoke, not a measurement: no noise estimate is possible.

- **A against C (graph tools against none):** A uses fewer tokens for all ten configs, from 33% (codex terra)
  to 88% (grok-4.7), and fewer turns and less wall time for all ten. In cost A is lower for all ten, from 30% to
  87% (muse 33%, at contributor prices that is fractions of a cent).
- **A against B (0.52.0 against 0.51.1):** these tasks (a callers question, a method rename, a field and a class
  rename on a small fixture) are mostly ones 0.51.1's graph already answered. For six configs A is *not*
  better than B in tokens: codex terra (−6%), codex sol (−5%), grok-4.7 (−1%), grok-4.5 (−7%), cursor composer
  (−6%), cursor grok-4.7-high (−8%) all used slightly more tokens in A. Single runs, so read these as "no
  difference shown". Claude Sonnet (+18%), Opus (+33%), agy (+15%) and muse (+18%) used fewer in A. For Sonnet
  and Opus nearly all of it is smoke-rename-field (20.6k against 40.3k, 20.9k against 59.4k: 0.52.0 renames the
  accessors and the template with the field, 0.51.1 left them to the agent); for agy it is the field and class
  renames; muse's is spread over all four tasks. One run each: a hint, not a result.
