# Graph bench across agents, 2026-11-17 (daybreak WSL; tier 0 also on bocabox)

Does jk 0.52.0's code graph save tokens, turns, wall time and money for each agent, against jk 0.51.1's graph
and against no graph tools? Ten agent/model configs, three arms, three tiers of tasks. TABLE.md has the numbers;
`rows-tier*-<host>.jsonl` are the raw rows (one per run, change-tasks' row format); `runner.py`, `plan.py` and
`tabulate.py` are what ran the matrix and built the table, `build-table.sh` writes TABLE.md from the rows,
`adoption.py` (with `calls.py`, the transcript extractor) counts how each agent used the graph, and
`runlog-*.txt` are the runner's logs (per run: load average, wall and monotonic seconds, least free memory).

**Hosts.** The bench started on bocabox (Fedora) and moved to **daybreak** (Windows 11, WSL Ubuntu 26.04 capped
at 12 GB) after bocabox's tier 0 and 55 of its 180 tier-1 runs, at the owner's word: daybreak is the quieter
host, where the earlier benches ran, and wall time is now one of the measures. So every tier's numbers come
from daybreak: tier 0 was rerun there, tier 1 run there from the start, then the sliver. bocabox's tier 0 is
kept as a second host's table and bocabox's 55 tier-1 rows as raw rows; neither is pooled with daybreak's.

## Defects found after tier 0, and the rows they void

Found by a separate investigation (JK-CQ5PD) and checked on daybreak; rows that a defect voids are kept raw in
files named `*-VOID-*.jsonl` and are in no table.

1. **Arm B did not run 0.51.1.** A jk home updates itself where no one answers a terminal (`self-update` is
   `auto`), and the jk-before home did. On **daybreak** it moved to 0.52.0 at 05:40:59 MST, during the warm-up
   and 15 minutes before tier 0 began (its `state/self-update.log`; `lib/jk-engine/jk-engine.toml` read version
   0.52.0, previous-version 0.51.1). **All 40 daybreak tier-0 B rows ran 0.52.0** (jk_build `d9ce8552f822`;
   A's 0.52.0 is `383cb0fc6891` only because the build id hashes the home's files): they are in
   `rows-tier0-daybreak-VOID-B-ran-0.52.0.jsonl`. Only five warm-up rows ran 0.51.1, so the "B needs no
   network" check below was made on 0.52.0 too and was repeated after the rollback. On **bocabox** the home
   moved at 05:07, two minutes into tier 0: 38 of its 42 B rows ran 0.52.0, and only Sonnet's callers, rename and
   rename-field B (jk_build `007329b86362`) ran 0.51.1. bocabox's tier-0 B column is left in its table as
   recorded, read as a second run of A. Fixed in the harness: every arm runs with `JK_SELF_UPDATE=never`,
   rows carry `jk_version`, and a jk-before home on jk's own version is refused; daybreak's B home was put
   back with `jk self rollback` (engine sha256 b99b29…, the installed 0.51.1's) and pinned with
   `self-update = "never"`.
2. **Codex's and muse's MCP server ignored the arm's jk home.** Both CLIs start MCP servers with an environment
   of their own, so their B runs' `jk mcp` reached the host's 0.52.x engine even where the home was still
   0.51.1 (their shell, prepare and verify used 0.51.1). Void anyway under 1; fixed (the server is
   `env JK_HOME=… JK_HTTP_PORT=… <jk> mcp`).
3. **petclinic's Docker tests in an agent's own gate.** The verify leaves PostgresIntegrationTests and
   MySqlIntegrationTests out; an agent's `run(kind=gate)` did not. Where those classes fail, the agent chases
   Docker. `$AGENT_LOOP_TEST_EXCLUDE` now writes them to the sandbox's `[test] exclude-classes`. Tiers 0 and 1
   have no task whose gate runs them except petclinic-callers-addpet, a question task (no gate run by any
   agent in tier 1's transcripts). Daybreak's check of those classes is under "Hosts and run conditions".
4. **Codex lost its login on daybreak during tier 1.** The codex driver copied `~/.codex/auth.json` into a
   throwaway home per run; when the access token expired codex renewed it there, its refresh token rotated, and
   the new one was deleted with the throwaway home. Every later run stopped in about 2.5 s, red, with no tokens:
   "Your access token could not be refreshed because your refresh token was already used". **All 36 codex
   tier-1 rows (terra and sol) are void** (`rows-tier1-daybreak-VOID-codex-login.jsonl`); the runner's
   fail-fast missed them because they graded red, not error. Fixed: the driver writes a refreshed login back,
   and the runner counts a row with no tokens and an agent error as an error.

Redone with the fixed harness: daybreak's tier-0 B for all ten configs, and codex's tier 0 B, tier 1 and sliver
(after a new codex login, the codex catch-up, `runlog-codex-catchup-daybreak.txt`); see Findings.

## JK-3MF95: petclinic's callers answers in every jk arm told the agent to search src/test

Found after these rows ran (agent-knobs, fixed on main in 8f2e9732c). The petclinic fixture has `[build-info]`;
the graph's own test compile failed to plan on such a project, so a `callers` answer that needs compiled facts
ended "compile skipped: the code does not compile (step 'compile-test' requires unknown 'build-info'); src/test
has no compiled classes, so the calls made there are missing: search it for calls too". It invites exactly the
verification a graph answer should save. **0.51.1 has it too** (a daybreak B warm-up transcript of
petclinic-callers-addpet carries the same sentence). Counted from the transcripts (`adoption.py`):
- **Tier 1, petclinic-callers-addpet, arm A: 30 of 30 runs, and every one of their 30 callers answers**, for all
  ten configs (codex's from the catch-up). Arm C's answers never carry it (no graph).
- **The sliver: no answer carries it** in any arm (A, B, F; C has no graph). Its tasks ask callers about anchors
  (`route:`, `column:`, `env:`, `config:`) and renames, whose answers do not need the test compile. The only
  "compile skipped" notes in the sliver have another cause, "the compile was refused: Build is already running;
  answering from the last build's classes" (an agent's own build still running when it asked): 6 answers in
  arm A, 6 in F, 1 in B, all grok-4.5 (`adoption-tier2-daybreak.md`). Codex's sliver runs carry none either.
So tier 1's petclinic task is the one number that carries the defect for every graph arm; it is re-measured with
main's fixed graph in stage R.

## Arms

| Arm | jk | What it is |
|---|---|---|
| **A** `jk` | 0.52.0 | the product as installed: `jk mcp`'s default list, graph included |
| **B** `jk-before` | 0.51.1, in its own home | the graph as it was before 0.52.0 (anchors, the wider rename and group roots are new in 0.52.0) |
| **C** `jk-nograph` | 0.52.0 | every graph tool hidden from MCP and refused, `jk graph` refused on a shell |
| **F** `jk`, own home | 0.52.1-SNAPSHOT (main a4d6277d9's product code, built from this branch at 2df07ae19) | the sliver only: main with gate 93's three graph fixes (JK-7NXBH, JK-BJDSS, JK-930DA), `jk install`ed into a private home (`self-update = "never"`) |

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
| 2 sliver (`change-tasks`) | petclinic-rename-telephone, petclinic-anchor-relaxed-env, petclinic-anchor-composed-route, petclinic-anchor-column-case | A, B, C, F | 3, interleaved |

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

- **jk-before ran 0.52.0** (fixed, JK-FT5CK), on both hosts: see "Defects found after tier 0" above.

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
- **Docker tests pass on daybreak.** petclinic's PostgresIntegrationTests and MySqlIntegrationTests (Spring's
  compose support on Docker Desktop 29.1.5, compose v5.0.1) passed in 3 of 3 forced reruns (`jk test -r -S …`
  in a prepared petclinic sandbox, 38–54 s each) after an uncached first run that passed too. So on daybreak
  `$AGENT_LOOP_TEST_EXCLUDE` is left unset: an agent's own gate runs those classes in every arm, as the product
  would, and they pass. (bocabox's podman cannot run them; the investigation there excluded them.)
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

### Tier-2 sliver on daybreak (4 petclinic tasks, 3 interleaved runs per cell, arms A, B, C, F)

480 runs: the eight configs other than codex 2026-11-17 11:01 to 2026-11-18 01:47 MST; codex's 96, which failed
fast on its lost login then (`rows-tier2-daybreak-VOID-codex-login.jsonl`, 4 rows), in the codex catch-up
02:07 to 04:25 MST after a new codex login. Every row's `jk_version` is its arm's (A and C 0.52.0, B 0.51.1, F
0.52.1-SNAPSHOT). No run was capped or spans a suspend; least free memory 5.7 GB of 12.
- **Green, all ten configs:** F 119/120, A 111/120, C 95/120, B 93/120. Without codex: F 95/96, A 92/96, C 75/96,
  B 73/96. Almost all of B's and C's misses are petclinic-anchor-relaxed-env
  (`claimed`: the agent said done, the hidden check failed), the task whose other side only 0.52's env/config
  anchors name, and agy's: it reached green in 3 of 12 runs in B and in C, 12 of 12 in A and F. A's four misses:
  composer 2 (relaxed-env, `claimed`), Sonnet 1 and muse 1 (column-case, `claimed`). Codex misses relaxed-env in
  every arm but F: A 5 of 6 runs (terra 2, sol 3), B 4, C 4, F none.
- **A against B (0.52.0 against 0.51.1):** fewer tokens in A for six of eight; **separate** ranges only for
  Sonnet (18%) and agy (69%, where B also fails 9 of 12). Opus 17%, grok-4.5 29%, cursor grok-4.7-high 9% and
  muse 7% are inside the noise; composer is level (0%); **grok-4.7 used 44% more tokens in A** (inside the
  noise: two A runs of column-case and rename-telephone took 1.1M and 0.75M tokens), though A was green 12/12
  against B's 9/12. So: 0.52.0 is clearly better than 0.51.1 here for agy and Sonnet, and in green rate for
  every grok, cursor grok-4.7-high and muse; in tokens, for the rest, not shown.
  Codex: A 22% (terra) and 23% (sol) fewer than B, inside the noise; sol green 9/12 in A against 11/12 in B.
- **A against C:** fewer tokens in A for seven of eight, separate for Sonnet, grok-4.5 and agy; muse used 7%
  fewer in C (noise). Codex: terra 19% fewer in A, sol 2% more (both noise).
- **F against A (main's graph fixes against 0.52.0):** fewer tokens in F for seven of eight (agy +2% more,
  noise): grok-4.7 62%, cursor grok-4.7-high 39%, Opus 8%, muse 8%, composer 6%, grok-4.5 3%, Sonnet 2%. **Every
  range overlaps**, so none of it is outside run-to-run noise on three runs; grok-4.7's and cursor grok's are
  the large ones (grok-4.7's rename-telephone 94k against 671k median). Green F 95 against A 92.
  **Codex goes the other way in tokens:** F used 9% (terra) and 48% (sol) *more* than A (noise; sol's
  composed-route 413k against 224k), but was green 24/24 against A's 19/24.
- **Adoption** (`adoption-tier2-daybreak.md`): every config answered with the graph in every A and F run except
  Sonnet (A 5/12, F 9/12: it often edits from a single grep) and composer's A (11/12). grok-4.7 made 9.8 jk calls a run in A
  (2.8 `skill` card reads) against 6.3 in F (1.4), and read or searched 20.6 times a run after its first graph
  answer in A against 9.9 in F. 0.52-capability calls (anchors, field renames, `also`): 0.75 to 4.25 a run in A.

### Tier 0 B redone on daybreak with 0.51.1 (one run per cell)

40 rows, every row `jk_version` 0.51.1 (after the warm-up checked the home online and with `JK_OFFLINE=1`): the
eight configs other than codex 2026-11-17 10:27 to 11:00 MST, codex's eight in the catch-up (2026-11-18 01:47;
the first four failed fast on the lost login: `rows-tier0-daybreak-VOID-codex-login.jsonl`). 39 green, one agy
`claimed`. **A against B:** A uses fewer tokens for all ten, from 19% (codex sol) to 86% (grok-4.5), and fewer
turns and lower cost for all ten; one run per cell, so no noise estimate. Unlike the void B column, this is a real
0.51.1: its field and class renames leave the accessors, template and file move to the agent.

### Tier 1 on daybreak (3 interleaved runs per cell, arms A and C)

180 of 180 green: 144 for the eight configs other than codex, and codex's 36 re-run in the catch-up (2026-11-18
01:50 to 02:07 MST; the first 36 are void, defect 4). No run was capped, none spans a suspend, and the least free
memory in any run was 7.5 GB of 12. Codex: A 53% (terra) and 40% (sol) fewer tokens than C, separate. Every
arm-A petclinic-callers-addpet answer carries JK-3MF95's note (above).
- **A against C:** A uses fewer tokens for all eight, from 34% (cursor composer) to 91% (cursor grok-4.7-high),
  and fewer turns; the summed per-task token ranges are **separate for seven** and **overlap for cursor
  composer** (160.8k against 242.7k), which is therefore inside run-to-run noise. Wall: A lower for all but
  composer (0%) and muse (9%); cost: A lower for all but muse (−10%; muse's costs are
  fractions of a cent at contributor prices).
- Per task, A's runs are tight (Sonnet's three A runs of each task within 0.1k tokens); C spreads more.

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
  **Void:** re-checked, every daybreak B row ran 0.52.0 (defect 1 above), so this paragraph compares two runs
  of 0.52.0; it is kept as first written, and the redo with 0.51.1 is reported separately.
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
- **Correction: tier 0's B column is not 0.51.1.** The jk-before home updated itself to 0.52.0 at 05:07, two
  minutes into the run (`self-update` is `auto` where no one answers a terminal). Only Sonnet's B callers, rename
  and rename-field (jk_build `007329b86362`) ran 0.51.1. The other 38 B rows (`5aa756fa03a6`) ran 0.52.0, which
  their transcripts show: every B smoke-rename-field after 05:07 got 0.52's field rename. So the B column is a
  second run of A, and the A-vs-B differences below are run-to-run noise between identical builds: −33% to +8%
  per config, up to ±65% per task. Sonnet's smoke-rename-field (20.6k tokens and 4 turns on 0.52.0 against 40.3k
  and 9 on 0.51.1) is the one real 0.51.1 comparison. The harness now runs every arm with
  `JK_SELF_UPDATE=never`, records `jk_version`, and refuses a jk-before home on jk's own version. The paragraph
  below is kept as it was written.
- **A against B (0.52.0 against 0.51.1), as first read:** these tasks (a callers question, a method rename, a field and a class
  rename on a small fixture) are mostly ones 0.51.1's graph already answered. For six configs A is *not*
  better than B in tokens: codex terra (−6%), codex sol (−5%), grok-4.7 (−1%), grok-4.5 (−7%), cursor composer
  (−6%), cursor grok-4.7-high (−8%) all used slightly more tokens in A. Single runs, so read these as "no
  difference shown". Claude Sonnet (+18%), Opus (+33%), agy (+15%) and muse (+18%) used fewer in A. For Sonnet
  and Opus nearly all of it is smoke-rename-field (20.6k against 40.3k, 20.9k against 59.4k: 0.52.0 renames the
  accessors and the template with the field, 0.51.1 left them to the agent); for agy it is the field and class
  renames; muse's is spread over all four tasks. One run each: a hint, not a result.
