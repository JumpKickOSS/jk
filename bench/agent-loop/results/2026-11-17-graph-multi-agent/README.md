# Graph bench across agents, 2026-11-17 (daybreak WSL; tier 0 also on bocabox)

Does jk 0.52.0's code graph save tokens, turns, wall time and money for each agent, against jk 0.51.1's graph
and against no graph tools? Ten agent/model configs, three arms, three tiers of tasks. TABLE.md has the numbers;
`rows-tier*-<host>.jsonl` are the raw rows (one per run, change-tasks' row format); `runner.py`, `plan.py` and
`tabulate.py` are what ran the matrix and built the table, `build-table.sh` writes TABLE.md from the rows, and
`runlog-*.txt` are the runner's logs (per run: load average, wall and monotonic seconds, least free memory).

**Hosts.** The bench started on bocabox (Fedora) and moved to **daybreak** (Windows 11, WSL Ubuntu 26.04 capped
at 12 GB) after bocabox's tier 0 and 55 of its 180 tier-1 runs, at the owner's word: daybreak is the quieter
host, where the earlier benches ran, and wall time is now one of the measures. So every tier's numbers come
from daybreak: tier 0 was rerun there, tier 1 run there from the start, then the sliver. bocabox's tier 0 is
kept as a second host's table and bocabox's 55 tier-1 rows as raw rows; neither is pooled with daybreak's.

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
| agy / hydrogen | agy 1.5.10 (daybreak: 1.5.9 for its first run, see below) | gemini-4-hydrogen-medium | effort medium |
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

## Hosts and run conditions

**daybreak (the measurement).** Windows 11 Home with WSL Ubuntu 26.04.1 (12 GB, 24 CPUs), jk 0.52.0 in `~/.jk`
and jk 0.51.1 installed by the installer (`JK_VERSION=0.51.1`) into its own home under the bench's scratch.
The repository checkout ran detached at this branch's tip; the corpus (spring-petclinic, gs-multi-module,
gs-uploading-files) was set up there with `bench setup`. Sequential, one agent run at a time; no gate host.
- **Arm B needs no network for jk.** 0.51.1 will be deleted from the download site after this bench, so
  before tier 0 every task was prepared once in each arm (a two-turn Sonnet run per task and arm), and then
  arm B again for all eleven tasks with `JK_OFFLINE=1` in fresh scratch: every baseline, graph build, MCP
  start and verification ran from 0.51.1's store with no error.
- **Awake.** daybreak's power plan never sleeps on AC (standby and hibernate 0). The batch also held a Windows
  keep-awake for its whole length (a PowerShell `SetThreadExecutionState(ES_CONTINUOUS|ES_SYSTEM_REQUIRED)`
  started through WMI so it outlives the ssh session; `powercfg /requests` lists it). A process started from
  WSL does not do: a Windows program launched through WSL interop from a detached shell dies with the ssh
  session that started it, and `systemd-inhibit` in WSL is refused without polkit (and would not hold
  Windows anyway).
- **No suspend.** The runner logs each run's wall-clock and monotonic seconds and marks a run whose wall clock
  ran more than 5 s past the monotonic one; none was marked, and Windows' System log has no sleep or resume
  event (Kernel-Power 42/107/506/507, Power-Troubleshooter 1) during any batch.
- **Memory.** The least MemAvailable seen during any tier-0 run was 7.9 GB of 12; no build was killed.
- **Other load.** Another session's `jk new … android/compose` processes sat stopped (state T, no CPU) on the
  host throughout; the runner's per-run load average stayed low (see `runlog-*`).
- **CLI updates.** `AGY_CLI_DISABLE_AUTO_UPDATE=1` did not stop agy 1.5.9 from replacing itself with 1.5.10 during
  its first tier-0 run, so that one row (smoke-callers, arm A) is on 1.5.9 and every later agy row on 1.5.10
  (bocabox's version). Muse updated itself to 1.4.23 (bocabox's version) before tier 0 began. Every other CLI is
  at the version in the configs table on both hosts.

**bocabox (tier 0 and part of tier 1).** Fedora, sequential. The host is also the release-gate host: before
every run the runner waited while `/var/tmp/jk-review2/GATE_RUNNING` existed, so no run overlapped a gate.
Pauses: tier 0, 29 min before run 100.

## Reading the numbers

See TABLE.md's notes; the honest reading per tier is in the next section.

## Findings

### Tier 0 on daybreak (one run per cell)

All 120 green (three grok-4.7 runs hit the 8-minute cap and were green on their one retry; see below).
- **A against C:** A uses fewer tokens for all ten configs, from 31% (Opus) to 91% (grok-4.7), and fewer turns,
  less wall time and lower cost for all ten (muse's cost by 4%: at contributor prices tokens hardly cost).
- **A against B: no difference shown.** Single runs, and the per-task cells swing both ways between hosts. On
  bocabox, Sonnet and Opus used 18% and 33% fewer tokens in A, nearly all on smoke-rename-field (B 40.3k and
  59.4k); on daybreak their B runs of that task took 20.6k and 20.8k, and A used 24% (Sonnet) and 11% (Opus)
  *more* tokens than B over the four tasks. Muse is 50% lower in A here (18% on bocabox), from two B runs of
  590k and 418k against A's 224k and 186k; agy 16%, grok-4.5 14%; codex, grok-4.7 and cursor within ±5%
  except cursor composer, 18% more in A. With one run per cell none of this is a result: tier 1's three
  repeats are the first numbers with a noise estimate.
- **grok-4.7's slow window.** Between 06:12 and 06:49 grok-4.7's tool calls took minutes: one `jk__rename`
  never returned before the cap, another run's shell tool took 340 s, and one run never produced a turn.
  The three capped runs (smoke-rename-field A and C, smoke-rename-class C) were green on their one retry
  (26 to 247 s; the table keeps the retry). Two runs from the window finished under the cap and stand as
  recorded: smoke-rename A (302.6 s, 293 s of it in jk) and smoke-rename-field B (371.4 s). They make
  grok-4.7's A-against-B wall (A 18% lower than B) unreliable. No other config was slow then.

### Tier 0 on bocabox (one run per cell)

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
