# Measuring an agent change

How to tell whether a change to what agents read ([agent-integration.md](agent-integration.md)) made
them better. The bench is `bench/agent-loop/` (commands and arms: its
[README](../../bench/agent-loop/README.md)); this page is the method, and the traps it has hit.

## Method

**Breadth first, one run per cell.** With the graph, an agent repeats itself closely: tokens move a
few percent between runs and correctness does not change. A screen is every agent on a handful of
tasks, one run each, before anything runs twice. Breadth (more tasks, more agents) says more than depth.

```bash
bench/agent-loop/smoke-test --driver codex --arm jk                        # tier 0: minutes
bench/agent-loop/screen-ff --series 33 --task petclinic-rename-addvisit …   # a screen, every agent
bench/agent-loop/screen-ff --series 33 --confirm --task …                  # rerun only what surprised
```

| Tier | What | When |
|---|---|---|
| 0 smoke | `smoke-test`: a 127-line fixture, one caller question and one rename | A new agent, or a change that may stop an agent reaching the graph at all |
| 1 screen | `screen-ff` on the few tasks the change can show, every agent, one run | Every change |
| 2 collection | `screen-ff` on every task (`--runs` only for the no-graph arm) | Fresh numbers for the docs, or a new agent |

**Fail fast.** Read the verdict as soon as the first agents finish. Stop a screen the moment the change
is plainly worse or plainly irrelevant; never let a run go long to "be sure". Change one thing per
screen, and pick tasks that can show it.

**Check the change reached the agent.** `screen-ff --expect TEXT` stops when a rename's answer lacks
the text under test: a change no agent saw reads as a change that did nothing.

**Depth only where it decides.** `--confirm` reruns once the cells that lost correctness or moved more
than 30% in tokens or turns against the baseline. The no-graph arm is heavy-tailed (turn caps,
failures, 2–3x swings), so it is collected with `--runs 3`, and only when the docs need it or a new
agent joins: changes to the graph tools do not touch it.

**Compare like with like.** The baseline is a series on the same build era, each agent on its
prescription (`screen-ff`'s `PRESCRIPTIONS`; `--arm agent=arm` tries another against it). Every row
records its `jk_build`.

**Split wall time.** Each transcript event carries `_t_ms`, its arrival since the agent started, so wall
time splits into model time and tool time. A rename that "got slower" is usually a test run, not
reasoning.

## Goal presence from real use

Whether agents pass `goal` on `run`, `deps`, `rename` and `update` is read from use, not a bench:
the PRD target is a `goal` on 90% of Claude Code and Cursor `run` calls. After a stretch of normal
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
  build are cached per repository, corpus commit, baseline command and installed jk build
  (`$AGENT_LOOP_HOME/prepared/`); an install starts a new entry.
- **Disk.** A finished run's sandbox is deleted once its row is written (its diff kept as
  `diff.patch`); only red and claimed runs keep theirs for `--reverify`. A matrix of kept sandboxes
  once filled a 1 TB disk. `/tmp` is a small tmpfs: scratch homes go under `~/.cache`.
- **One heavy run per machine.** A bench, `jk snapshot -p integration` and an uncached full build
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
