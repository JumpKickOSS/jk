# The big-suite harness

JumpKick against a large, test-heavy repository's own Maven or Gradle build, on the three clocks a
team feels: the inner loop (one edit to a verdict), CI (every commit's pipeline) and the morning
build (a fresh checkout of a commit CI already built). The scoreboard's `suite` dimension runs it
and banks its rows (`dim = "suite"`) in [`scoreboard.toml`](../../scoreboard.toml): the edit arm in
the night tier, all three arms in the release tier.

```bash
bench/suite/measure --arm edit                          # jk test against mvn test, five edits each
bench/suite/measure --arm replay --commits 200 --max-minutes 50 --out DIR   # resumable; rerun to go on
bench/suite/measure --arm replay --cache-remote http://localhost:8080 --out DIR
bench/suite/measure --arm morning --cache-remote http://localhost:8080
bench/scoreboard --dim suite                            # the edit arm, behind the quiet gate, banked
bench/scoreboard --tier release --dim suite             # all three: hours, behind the quiet gate
bench/suite/measure --selftest                          # the harness's arithmetic, no fixture needed
```

Clones, working copies, private engine state and action caches live under `$SUITE_HOME` (default
`$JK_BENCH_HOME/suite`), never in this tree. Maven and Gradle are the versions in
[`../tools.toml`](../tools.toml), run directly, as the [wall harness](../wall/README.md) runs them.

## Fixtures

[`fixtures.toml`](fixtures.toml) pins each repository to a commit and names the file the edit arm
changes. A fixture is a public repository whose suite is green under its own build and under
JumpKick after `jk import` with nothing added by hand:

| Fixture | Build | Suite at the pinned commit |
|---|---|---|
| [TheAlgorithms/Java](https://github.com/TheAlgorithms/Java) | Maven, one module | 10,265 tests under Maven, 10,268 under JumpKick |

Every JumpKick checkout is imported (`jk import`, `jk lock`) before anything is timed: a project
that has adopted JumpKick commits its `jk.toml` and lock. Maven runs `test` with the repository's
lint and coverage plugins switched off (`maven-args`), as JumpKick runs neither by default.

## Arms

| Arm | Before the timed command | What is timed |
|---|---|---|
| edit | one warm working copy per tool and one untimed test run; then a private method appended to the fixture's `edit` file, restored after the run | `jk test` against `mvn test` (`gradle test` with its daemon and build cache): seconds to a verdict, `--runs` times |
| replay | every one of the last `--commits` first-parent commits, oldest first, on a fresh checkout | the test pipeline per commit: the build tool (warm dependency repository, as a CI dependency cache keeps it; Gradle without its daemon or build cache); JumpKick cold, a fresh engine and an empty action cache with the dependency store warm; with `--cache-remote`, JumpKick on a fresh runner that reads and writes the shared cache every earlier commit wrote to (`CI=1`) |
| morning | a fresh checkout of the pinned commit, `--runs` times | `jk test` cold, `jk test` reading the shared cache the replay filled (it never writes), and the build tool's `test` |

JumpKick runs on private engines (their own `JK_STATE_DIR`), stopped by process id when their run
is over, so the user's engine and cache are never touched. The shared cache's credential comes
from the environment as JumpKick reads it (`JK_REPO_<HOST>_TOKEN`); every shared-cache run carries
one team key seed, kept in `$SUITE_HOME/team-cache-seed`, as a team's CI and laptops would
([Cache](../../docs/user/cache.md#results-that-read-your-environment)). A local cache service in
development mode serves the arm when no hosted one is at hand; the row then measures a cache on
the same machine, with no network between them, and says so (`shared-cache = "same machine"`). Such
rows are banked to track JumpKick over time and are never published: only a cache on another
machine, reached over the network (`shared-cache = "network"`), stands for what a team's CI sees.

## What a row says

- `suite.edit`, seconds: median and p90 of the timed runs, the test count of the last run and how
  many runs ended red.
- `suite.ci.pipeline`, minutes per commit: median and p90 over the replayed commits, failed commits
  and runner minutes per 100 commits (the mean times 100). Rows carry minutes, never dollars: a
  page that shows a price applies a runner's list price where it renders, and names the price's
  date. JumpKick rows add the steps it did not run, by kind, and the hit rate: steps served from an
  action cache over every step a cache can serve (compile, test compile, the suite, and any step
  that hit).
- `suite.morning`, seconds: median and p90 of the fresh checkouts, with the hit rate.

Each replayed commit is kept in `DIR/replay.jsonl` as it finishes, so a replay cut short, or one
stopped by `--max-minutes`, resumes from the same `--out`. A shared-cache replay resumed against a
cache that lost what earlier commits wrote measures something else; start it again in a new
directory. Under the scoreboard (`SUITE_OUT=DIR`) a resumed replay's rows are recorded `noisy` and
never banked: its earlier commits ran before the batch's quiet gate and calibration. Every row records the host block, the JumpKick and Maven or Gradle versions, the fixture
commit and the load as each commit or run started.
