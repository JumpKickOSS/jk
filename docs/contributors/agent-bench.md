# Measuring an agent change

How to tell whether a change to what agents read ([agent-integration.md](agent-integration.md)) made
them better. The bench is `bench/agent-loop/` (commands and arms: its
[README](../../bench/agent-loop/README.md)); this page is the method, and the traps it has hit.

## The standard

One model and one effort per agent, the same in every bench (`change-tasks`' `DEFAULT_MODEL` and
`DEFAULT_EFFORT`, which `smoke-test`, `screen-ff` and `night-plan` use), each id as the agent's own CLI
lists it:

| Agent | CLI | Model | Effort |
|---|---|---|---|
| Claude Code | `claude` | `claude-opus-5-5` (Claude Opus 5.5) | `medium` |
| Codex | `codex` | `gpt-6.2-sol` (GPT-6.2-Sol) | `medium` |
| Copilot | `copilot` | `fusion` (Copilot Fusion, which routes to a model of its choosing) | `standard` (the Fusion tier) |
| Antigravity | `agy` | `gemini-4-neon-medium` (Gemini 4 Neon) | `medium`, part of the model id |
| Grok | `grok` | `grok-4.7` | `high` |
| Cursor | `cursor-agent` | `composer-2.5` (Composer 2.5, not the fast variant) | none: `default` is recorded |
| Muse | `muse` | `muse-spark-1.3-contributor` | `max` |

A row is priced at its model's row in the product's price table
(`shared/client-io/src/main/resources/cc/jumpkick/prices/prices.toml`); Muse's contributor model at
the standard `muse-spark-1.3` row. Fusion has no per-token list price: a Copilot row records the models
Fusion routed to and the AI units it used, and no cost. Rows are banked per model, so a new pin starts a
new series for that agent, and the claims re-render only from series on the pinned models.

**Tiers.** Every agent, one run per cell, never repeated. The no-code-tools arm (`jk-nograph`) runs
only when asked for: `screen-ff --arm agent=jk-nograph`, or the night's step 2.

| Tier | What | When |
|---|---|---|
| 0 smoke | `smoke-test`: the fixture's caller question and rename, minutes per agent | A new agent or model, or a change that may stop an agent reaching the graph at all |
| 1 screen | `night-plan`'s `TIER1`: a caller and an impact question, a rename, the delete, move, pull-up and extract-interface refactorings on petclinic and on commons-lang, a library question and what a dependency version change breaks | Every change, and every night |
| 2 sliver | the other tasks the published claims use, with the code tools | Fresh numbers for the claims |

## Method

**Breadth first, one run per cell.** With the graph, an agent repeats itself closely: tokens move a
few percent between runs and correctness does not change. A screen is every agent on a handful of
tasks, one run each, before anything runs twice. Breadth (more tasks, more agents) says more than depth.

```bash
scripts/bench-night.sh smoke                                               # tier 0, every agent: one line each
bench/agent-loop/smoke-test --driver codex --arm jk                        # tier 0, one agent: minutes
bench/agent-loop/screen-ff --series 33 --task petclinic-rename-addvisit …   # a screen, every agent
bench/agent-loop/screen-ff --series 33 --confirm --task …                  # rerun only what surprised
```

A night on an otherwise idle host runs the tiers in order, every step to its end:
`scripts/bench-night.sh` ([README](../../bench/agent-loop/README.md#a-night-of-benches)). Its `--until`
is a soft deadline: past it the night logs what is done and left and its estimate for the rest, and
`scripts/bench-night.sh status` says the same at any time. A new agent goes first and alone through
tiers 0 to 2, so a problem with it shows early; then the no-code-tools baseline; then every other agent
with the code tools. It ends with a table per agent and per task of the tool hit rate, wall time,
turns, tokens, dollars and diagnostics, with the code tools against without.

**Fail fast.** Read the verdict as soon as the first agents finish. Stop a screen the moment the change
is plainly worse or plainly irrelevant; never let a run go long to "be sure". Change one thing per
screen, and pick tasks that can show it.

**Check the change reached the agent.** `screen-ff --expect TEXT` stops when a rename's answer lacks
the text under test: a change no agent saw reads as a change that did nothing.

**Depth only where it decides.** `--confirm` reruns once the cells that lost correctness or moved more
than 30% in tokens or turns against the baseline. The no-graph arm is heavy-tailed (turn caps,
failures, 2–3x swings), and changes to the graph tools do not touch it: it runs only when the claims
need a new baseline or a new agent joins, one run per cell like every other arm.

**Compare like with like.** The baseline is a series on the same build era, each agent on its
prescription (`screen-ff`'s `PRESCRIPTIONS`; `--arm agent=arm` tries another against it). Every row
records its `jk_build`. A change to a task's planted code, hidden files or prompt is another measure:
raise the task's `series` in `tasks.toml` in the same commit. Rows record it as `task_series`, and
`screen-ff` and the scoreboard leave out rows of an older one.

**Split wall time.** Each transcript event carries `_t_ms`, its arrival since the agent started, so wall
time splits into model time and tool time. A rename that "got slower" is usually a test run, not
reasoning. Every change-task row records `wall_s` (the agent's start to its final answer), `tool_s`
(the union of the spans from each tool call to its result), `jk_s` (the same over JumpKick's MCP tools and
shell calls that run `jk`) and `model_s` (the rest), and the table shows all four: a JumpKick tool that saves
three turns but takes 40 s itself shows up. The split needs a transcript that pairs calls with results
(Claude Code's stream-json does); it is empty for a driver whose events do not. Fewer turns is not
assumed to mean less wall: compare `wall_s`.

**Hold walls comparable.** Every `jk` arm runs `jk graph build` in the sandbox before the agent starts,
untimed (`warmup_s`), so no arm's first call pays a cold engine or graph worker. Only one heavy job
runs on the machine at a time, and the prepared baseline (build, tests, graph) is built before
timing and cached per JumpKick build. Network fetches inside a run (a dependency the agent adds) are not
held constant and are read from the transcript when a wall surprises.

**Net gain, not only a gain over no graph.** `--arm jk-before` runs the graph as it was: another JumpKick (a
release) installed in its own home, named by `$JK_BEFORE_HOME` (its `bin/jk` is the client), used for
the run's prepare, agent and verification, on a free HTTP port. Stop that home's engine after installing
it (`JK_HOME=$JK_BEFORE_HOME jk engine stop -A`): the installer starts one on JumpKick's default port, and the
build under test's engine then serves no MCP. A graph change ships when it beats `jk-before` summed over
the tasks it does not target and does not lose on the ones it does; `jk-nograph` alone says only that
a graph helps.

## Goal presence from real use

Whether agents pass `goal` on `build`, `deps`, `rename` and `update` is read from use, not a bench:
the PRD target is a `goal` on 90% of Claude Code and Cursor `build` calls. After a stretch of normal
work, read the local session journal (read-only; the engine need not run):

```bash
bench/console/goal-share --days 7        # $JK_STATE_DIR, else $JK_HOME/state, else ~/.jk/state
bench/console/goal-share --json
```

One row per MCP client (`clientInfo.name`): its sessions, its episodes by title source (`goal`,
`lastGoal`, `inferred`), the runs in those episodes, and the goal share of runs. The journal keeps
episodes, not each call's arguments, so a goal-less run that joined a goal's episode counts as one
with a goal: the share is an upper bound, and the `lastGoal` and `inferred` runs certainly carried
none. Bench sessions (`agent-loop-harness`, `scoreboard`) are rows of their own; read the agents'.

## What a verdict means

`screen-ff` prints, per agent and task, tokens, turns and wall time against the baseline, then
**better** (median ratio under 0.9, no task 30% worse, no correctness lost), **worse** (median over
1.1, two or more tasks 30% worse, or correctness lost) or **same**.

## Traps

- **Setup is most of a cell.** Copying the repository, its baseline build and tests, and the graph
  build are cached per repository, corpus commit, baseline command, planted edits, task series and
  installed JumpKick build (`$AGENT_LOOP_HOME/prepared/`); an install starts a new entry.
- **Disk.** A finished run's sandbox is deleted once its row is written (its diff kept as
  `diff.patch`); only red and claimed runs keep theirs for `--reverify`. A matrix of kept sandboxes
  once filled a 1 TB disk. `/tmp` is a small tmpfs: scratch homes go under `~/.cache`.
- **One heavy run per machine.** A bench, `jk install -g` and an uncached full build
  never overlap; a build during a screen inflates its wall times. Install between screens, not during.
- **Waiters.** Wait on a bench with `pgrep -f '^python3 -u ./screen-ff'` (the interpreter's command
  line), never a pattern your own shell's command line also matches.
- **Docker.** petclinic's baseline runs a Testcontainers Postgres test; `screen-ff` skips that task
  while Docker is down.
- **Tokens of a killed agent are unknown** (Cursor reports usage only at the end): the row records
  `null`, never 0, and medians leave it out.
- **Gates in a pipe.** `jk guard | tail` exits 0 when guard fails: use `set -o pipefail`, and chain a
  gate and a push with `&&`.
- **A red build rewrites `jk-guards-baseline.toml`**: restore it before committing.
