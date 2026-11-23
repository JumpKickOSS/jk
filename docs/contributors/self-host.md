# Self-hosting JumpKick

JumpKick builds itself. The root **`jk.toml`** is a workspace (members under `shared/`, `server/`,
`clients/`, `plugins/*` and the rule packs under `server/guard/packs/`), `jk-lock.toml` at the root
is the one lock, and every output lands under `target/`. There is no second build definition in
the tree: the IntelliJ plugin under `clients/intellij` is a member built by the `[intellij]`
battery, the VS Code extension under `clients/vscode` keeps npm because that is how VS Code ships
extensions, and the Gradle projects under `bench/jar-size/` are fixtures the fat-jar bench
compares JumpKick against.

## The gate

Run in this order, in your checkout, with the installed `jk`. The first two are the merge gate,
green before a change merges into local `main`; `jk install -g` runs every integration and e2e
class, the heart of the Full tier:

```bash
jk guard                         # formats what changed, then every house-rule lane, the tree lane and the fixture proofs
jk install                       # every module compiled, packaged and its fast test tier run, then the reinstall
jk install -g                    # engine, e2e and worker suites against the JumpKick it installs
```

The fleet's gate hosts run a whole gate from a checkout as `scripts/gate/run.sh <n> <sha>`, in
the tier `GATE_TIER` names ([tiers](test-suite-tiers.md#gate-tiers)). It takes the host's gate lock
(`GATE_LOCK`, default `/var/tmp/fleet-gate.lock`), detaches the checkout at the tip, runs
`jk guard`, then the tier's tests:

- **`GATE_TIER=queue`**, the push gate the [merge queue](#the-merge-queue) runs on every batch:
  `jk install -S` (the reinstall without tests), then under a freshly started engine
  `jk install -c` (every module's fast tier, then the reinstall), the integration suite of each
  module changed since `GATE_BASE` (`scripts/gate/changed-modules.sh`), the curated lane's
  classes in the other modules, the docs suite (`jk test -s docs`), which holds the docs to
  the code, and the tree suite (`jk test -s tree`), the tests that scan every module's sources.
- **`GATE_TIER=full`** (the default): `jk install -S` (the reinstall without tests), `jk install -g`
  under a freshly started engine (the one run of the fast tier, with the gated tiers and the tree
  suite), then
  `jk test -s docs`, `jk test -s release`, `jk test -s slow` when a member under `plugins/` or a root build file
  changed since `GATE_BASE` (none without it), the release notes, and
  `scripts/gate/docs-only.sh check`: red when a suite was seen reading a doc that
  `scripts/gate/test-reads.txt` does not list.
- **`GATE_TIER=docs`**, which the queue runs in place of the Queue tier when everything since
  `GATE_BASE` is [docs-only](#docs-only-changes): `jk guard`, `bench/scoreboard --render --check`
  and `jk test -s docs`, no install and no other test. It is red over a range that is not
  docs-only.

Both reinstall before any test runs, because a test's verdict is keyed by the engine that ran it:
a batch that changes the engine reruns every suite under its own engine, and the next gate reuses
those verdicts for every module it did not change. The Queue and Full tiers then check that the
engine which served the tests is the tip's and run the shell fixtures. Each tier writes `GREEN <sha>` or `RED` to `integ-gate-<n>.log` under
`GATE_LOG_DIR`. A gate never pushes.
The header of `scripts/gate/gate.sh` lists its settings; `scripts/test-gate.sh` runs it against a
stand-in `jk`.

### Flaky tests

The gate uses JumpKick's own [test quarantine](../user/test.md#flaky-tests-are-quarantined-jk-flaky).
JumpKick keeps each class's runs on the gate's host, and when a class has passed on a re-run with
nothing changed as often as the root's `[test] quarantine` says, it adds the class to
`.jk/test/quarantine.toml` in the gate's checkout. Every tier still runs a quarantined class, and a
run red only in quarantined classes passes. In the Full tier, a red `jk install -g` runs once more
under the same inputs, which runs only the classes that did not pass; the gate stays red.

The gate keeps its checkout clean: once its tests have run (in the docs tier, its docs suite) it
saves the file JumpKick changed as `g<n>-quarantine.toml` beside its log and restores the tip's,
before its verdict. Then
`scripts/gate/flaky.sh review` makes the file `origin/main` should hold and submits it:

- each class JumpKick added, and each entry on `origin/main` with no ticket, gets a `flake` ticket,
  recorded in `quarantine-tickets.tsv` under `GATE_LOG_DIR` until the queue lands its entry;
- each entry gets its ticket's id as its `issue`, the id alone: no `issue-url`, never a ticket
  store's URL;
- an entry whose ticket is done or canceled is taken out.

When that file differs from `origin/main`'s and the queue does not already hold it, the review
commits it on `origin/main` as the branch `gate/quarantine`, passes the Branch tier and submits it
to the merge queue (`g<n>-quarantine.log`). A class that cannot get a ticket is not submitted. The
file is not a build input, so a change to it selects no module in the Queue tier. To release a
class by hand, close its ticket, or delete its entry with `jk flaky release` and submit that.

Neither the Queue tier nor the Full tier stays red on a flake. The Queue tier's install and each of
its integration runs, and the Full tier's `jk install -g`, red only in test classes, run once more
under the same inputs, so only the failed classes run again; the classes that pass now are recorded
as above, and the step passes when the re-run does. Each such class is a `FLAKY <module> <class>
passed on the re-run` line of the gate's log, read from the `passedOnRerun` of its `class-finish`
row in `jk results -O jsonl` ([machine output](../user/machine-output.md#event-vocabulary)). A class
still red is a `FAILED <module> <class>` line of the Queue tier's log, and its failure's message and
stack go to `g<n>-failures.txt` beside it. A run red outside a class (a compile error, a dead test
JVM) stays red: the Queue tier does not run it again, and the Full tier's re-run does not count.
So does a run whose transcript the gate cannot read: `jk results` prints the latest run that kept
one, and the gate reads it only when its `session-start` names the command just run
(`scripts/gate/flaky.sh ran`); otherwise a `TRANSCRIPT` or `RERUN green, but` line names the run
it printed.

## The merge queue

`origin/main` has one writer: the merge queue, `scripts/queue/loop.sh`, on bocabox. A session
does not push `main`; it submits its branch:

```bash
scripts/queue/branch-tier.sh     # the Branch tier: jk guard, then jk test -X over what -e <merge-base> selects
scripts/queue/submit.sh [branch] # refused unless the tip passed the Branch tier
scripts/queue/status.sh [branch] # waiting, gating in batch <n>, landed as <sha>, or ejected and why
scripts/queue/status.sh --stats  # the last 20 landings: ready to landed, lock wait, gate; medians, slowest three
```

The Branch tier records a pass as a note on the tip under `refs/notes/branch-tier`, naming the
tip's tree; a commit after it needs a new run. `submit.sh` pushes the tip to the queue's
repository (`JK_QUEUE_REMOTE`, default `bocabox:src/jk-queue.git`) as `refs/queue/<branch>`,
replacing an earlier submission of the branch.

Each batch, under the host's gate lock, the loop fetches `origin/main` and cherry-picks every
waiting branch onto it, oldest first, skipping commits the batch already carries (a branch cut from
another waiting branch lands its own commits only). A branch that conflicts, or that carries a
merge commit, is ejected. It gates the result with the Queue tier of `scripts/gate/gate.sh`
(`QUEUE_TIER=full` gates with the Full tier instead; a [docs-only](#docs-only-changes) batch gets
the docs tier) and, on `GREEN`, pushes it to `origin/main` as a fast-forward. It never forces: when
`origin/main` moved while the batch gated, the push is refused. When everything that moved it is
docs-only, the verdict holds: the queue applies the batch to the new `origin/main` and pushes that
(`LANDED as <sha> after docs-only commits moved origin/main`); otherwise the next batch applies the
same branches to the new `origin/main`.
Since the gate already ran its failed classes once more, a red batch is red for a cause. A red
batch of one branch ejects it with the gate's failing lines. A red batch of several is split in two
groups, gated one after the other before anything else that waits: the branches that changed a
module one of the failed classes is in (the module whose `name` the class's artifact carries), or a
root build file, go second, and the rest first, so the rest lands while the suspects are split
again; when no branch or every branch changed one, the batch is split in halves in submission
order. One bad branch of many costs three gates when the failed class is in a module it changed:
the batch, the rest, and the bad branch alone. The next batch starts as soon as one ends.

An ejected branch's ticket is told why, with `ka comment` (`QUEUE_KA`): the conflicting files, or
the failing classes with the gate's failing lines and the failures' stacks. Its ticket is the one
the branch is named for (`w/<ticket>`), else each ticket its commit subjects start with; the
branch's record says which tickets were told and which could not be. A branch never leaves the
queue unsaid: after every batch, each branch the batch saw is still waiting, landed or ejected, or
it is recorded as `dropped` and the batch log gets a `DROPPED` line.

Where each branch stands is `queue-state/<branch>` in the queue's repository, and every batch is a
line of `queue-batches.log` beside it: number, base, tip, the group it gated, `LANDED`, `MOVED`, `RED-SPLIT` and how,
`RED-EJECTED` or `NOT-GATED`, its branches, and its `wait` (from its oldest branch's submission to
its taking the gate lock) and `gate` seconds. Each branch that lands or is ejected is a line of
`queue-timings.log`: when it was submitted (`submit.sh` records it in `queue-ready/<branch>`), when
its batch took the lock, when the gate started and ended, and when it landed or was ejected.
`status.sh --stats` reads it; the target is ten minutes or less from submission to landing at the
median. A batch that could not gate at all (a dirty
checkout, a failed fetch, no verdict) puts its branches back to waiting and stops the loop. On
bocabox it runs as

```bash
QUEUE_CHECKOUT=~/src/jk-queue-checkout scripts/queue/loop.sh
```

from a checkout other than `QUEUE_CHECKOUT`; the header of `scripts/queue/loop.sh` lists its
settings, and `scripts/test-queue.sh` holds it to the rules above in scratch repositories.

**Pausing.** While `pause` exists in the queue's repository, the queue and the
[Full tier loop](#the-full-tier-loop) start no batch or cycle: the one in flight finishes, each loop
says once that it is paused, with the file's first line as the reason (the queue on its output, the
Full loop in `full-runs.log`), and looks again every `QUEUE_POLL` or `FULL_POLL` seconds, however
long the pause lasts. `scripts/queue/status.sh` adds a line saying the queue is paused and why.
Removing the file resumes both. `scripts/bench-night.sh pause` and `resume` write and remove it
for a night of benches.

### Docs-only changes

A change is docs-only when every file it touches is a doc (a file under `docs/` or `hosting/`, or a
Markdown file at the root) that no test reads but those of the docs suite, which the docs tier
runs; `scripts/gate/docs-only.sh range <base> <tip>` says whether a range is. The docs tests read
are `scripts/gate/test-reads.txt`. Nobody writes it by hand: JumpKick records every file a suite
reads ([discovered inputs](../user/test.md#what-a-suite-read-discovered-inputs)), one record per
module and selection, and `scripts/gate/docs-only.sh write` collects the docs among them for every
workspace member from JumpKick's cache, leaving out the record of `jk test -s docs`. Run it after a
run of every suite and commit the result. The Full tier runs `scripts/gate/docs-only.sh check` once
all its suites ran, and is red naming each doc a suite read that the list lacks.

**The docs suite** holds the tests that scan the doc tree: `MachineOutputCallersTest` (every
`-O json` caller, in the docs too) and `PluginDefaultDocsTest` (every built-in plugin default a page
quotes). A test that walks a directory of docs reads every file in it, so outside this suite it
would keep every doc out of the docs-only set; tag it `@Tag("docs")`. A test that reads a few named
docs stays in its tier, and those docs stay in the list. The suite runs alone, never in a run of
several suites (`jk install -g`), because such a run records what all of them read as one, and
the docs it reads would land in the list again.

`jk install` must not rewrite the committed `jk-lock.toml`: the manifests pin exactly, so a build
that moves the lock means a pin drifted or the lock writer put something non-deterministic in the
file. `jk test -s slow` is due before the push that carries a change to a framework or language plugin; the
`network` and `bench` suites are nightly and gate nothing.

The shell fixtures and lints are part of the same bar when a script or a workflow moves:
`for f in scripts/test-*.sh; do bash "$f"; done`, `scripts/shellcheck.sh` (also the `shellcheck`
guard, G100, under `jk guard`; skipped on Windows) and `actionlint .github/workflows/*.yml`.

## The Full tier loop

The Queue tier leaves most integration classes out, so the Full tier runs behind it, back to back
on the newest `origin/main`: `scripts/gate/full-loop.sh` on a host of its own, so the queue never
waits behind a Full cycle. It reaches the queue's repository over ssh (`QUEUE_REPO`, `host:path`,
default `bocabox:src/jk-queue.git`, as the [queue's scripts](#the-merge-queue) do): it reads
`queue-batches.log` and `pause` there and writes its own state beside them, and it takes only its
own host's gate lock, never the queue's. Each cycle gates the tip of `origin/main`
with `GATE_TIER=full`, unless that commit was judged already. Its base is `green` (`GATE_BASE`), so
the gate also runs the slow tier when a plugin, or a root build file, changed since the last commit
that passed; before there is a `green`, it always does. Then:

- **Green** moves origin's `green` branch to the tip as a fast-forward (`scripts/gate/green.sh`).
  `green` is the newest commit that passed the Full tier, and every platform that
  [holds it](#windows-and-macos), and releases are cut from it ([releases](releases.md#cutting-a-release-by-hand)).
  A gate green because its failed classes passed on its re-run ([flaky tests](#flaky-tests)) is a
  green cycle, and its line in `full-runs.log` names those classes.
- **Red** finds the batch that made it red among the queue's batches landed since `green`
  (`queue-batches.log`). It bisects them: it gates batch tips with the Full tier until it knows the
  first red one, and gates no commit twice. It names that batch, with its branches and the log of
  its red gate, in `full-runs.log`.
- **A confirmed culprit is reverted**, once per batch. The loop runs the classes the batch's red gate
  failed again (`scripts/gate/rerun-classes.sh`: the reinstall without tests, then those classes
  alone): they must fail at the batch's tip and pass at its base, and a bisect of the batch's
  commits finds the one they fail with and pass without. It reverts that commit on top of the red
  tip, passes the revert through the Branch tier and submits it to the queue as
  `full-revert/<ticket>-<sha>`, which gates it like any branch; it never pushes `main`. It comments
  on the commit's ticket and reopens it (a ticket `done` or in `review` goes back to `ready`), so its
  worker fixes forward on top of the revert and submits again.
- **Otherwise it reverts nothing**: when the red gate failed outside a test class, the classes pass
  when run again, they fail before the batch too, or the revert conflicts with later work. It
  comments once on each ticket the batch's commits name (a subject `<ticket>: …`), saying why;
  revert the batch's commits or fix forward, through the queue.
- **Two red cycles in a row** (`FULL_HOLD_AFTER`) write `full-hold` in the queue's repository: the
  batch's tickets (`ticket <id>`) and commits (`commit <sha>`). While it exists the queue lands only
  a fix, a branch with a commit for one of those tickets or one that reverts one of those commits;
  the others wait, and `scripts/queue/status.sh` shows them `held`. The next green cycle removes it.
  A hold that names nothing (no batch was found red) lands nothing until a person adds a line to it.
- **A JumpKick that cannot start is an infra failure**, not a red: before each gate, and after a
  gate red at guard (which runs on the install the tip has not replaced yet), the loop runs
  `jk engine start` in its checkout. When that fails, `full-runs.log` says `INFRA` with the first
  line JumpKick printed, and the loop records no verdict, names no culprit, counts no red cycle and
  holds nothing.
  It tries the tip again `FULL_POLL` seconds later; `--once` exits 5.

It runs in a checkout and a JumpKick home of its own (`JK_HOME`), so its gates never replace the
install other work on its host uses. The home is made once, the way CI makes one; then the loop
starts from the checkout (it runs a copy of itself, so later checkouts do not change it):

```bash
git clone git@github.com:JumpKickOSS/jk ~/src/jk-full-checkout && cd ~/src/jk-full-checkout
export JK_HOME=$HOME/jk-full/home
bash scripts/ci-bootstrap.sh && bash scripts/ci-takeover.sh --install-only && git clean -qfd
mkdir -p /var/tmp/jk-full
FULL_CHECKOUT=~/src/jk-full-checkout FULL_LOG_DIR=/var/tmp/jk-full \
  nohup scripts/gate/full-loop.sh >>/var/tmp/jk-full/full-loop.out 2>&1 &
```

It pushes `green` from its checkout's origin with its host's GitHub credentials, and submits a
revert to the queue and comments on tickets (`ka`) from its host. Its gate logs are
`integ-gate-f<n>.log` in `FULL_LOG_DIR` on its host, and `full-runs.log` names each log as
`<host>:<path>`.
The re-runs of a culprit and its revert's Branch tier log to `full-<batch>.log` beside them. The
header of `scripts/gate/full-loop.sh` lists its settings; `scripts/test-full-loop.sh` holds it and
the queue's hold to the rules above in scratch repositories, and `scripts/test-rerun-classes.sh`
holds the re-run.

### Windows and macOS

Each tip the Full loop takes (it appends it to `full-taken` in the queue's repository before its
Linux gate starts) also runs on Windows, on Daybreak, and on macOS, on bocamacmini, at the same
time. One loop per platform runs on the gate host, outside the gate lock: a platform run checks one
fixed commit, so it needs no lock shared with the Linux gate, and the run holds its own host to one
run at a time.

```bash
PLATFORM_CHECKOUT=~/src/jk-platform-checkout scripts/gate/platform-loop.sh windows
PLATFORM_CHECKOUT=~/src/jk-platform-checkout scripts/gate/platform-loop.sh macos
```

Each cycle feeds `scripts/gate/platform-run.sh` to the host over ssh (Git Bash on Windows) for the
newest tip the platform has no verdict on; a platform slower than the Full tier skips to the newest.
On the host the run keeps a checkout, a JumpKick home and a local Maven repository of its own under
`~/jk-platform`, so the host's own install and engine are never touched. It installs the tip into
that home without tests (a home that does not exist yet is made the way CI makes one, with
`scripts/ci-bootstrap.sh` and `scripts/ci-takeover.sh`), then runs every module's fast tier, each
test class whose source names an OS (`@EnabledOnOs`, `OS.WINDOWS`, `os.name`, …) in the fast or
integration tier, by module, and the product smoke (`scripts/ci-product-smoke.sh`).

- **Red** files a ticket (a bug labelled `platform-windows` or `platform-macos`) that says what
  failed. While it is open, a red run with other failures comments on it, and a green run says so.
- **Trailing, then holding.** A platform's runs do not hold `green` at first. Once it has been green
  for a week with no red run between, its loop writes `platform-<platform>-blocking` in the queue's
  repository, and from then on `green` moves only to a tip the Full tier and that platform both
  passed: the Full loop leaves `green` where it is until the platform's verdict comes in, and the
  platform's loop moves it. Delete the file to make the platform trail again.

Verdicts are `platform-verdicts` and each run is a line of `platform-runs.log`, both beside the Full
loop's; the run's output is `/var/tmp/jk-platform/platform-<platform>-<n>.log` on the gate host, and
its steps' own logs are in `~/jk-platform/logs` on the platform's host. The header of
`scripts/gate/platform-loop.sh` lists its settings; `scripts/test-platform-loop.sh` holds the run,
the loop and the hold to the rules above.

## CI

**The self-host job is the release authority.** Every release tag installs the hosted release
`.jk/ci/ci-bootstrap-version` pins the way a user installs it
(`curl -fsSL https://jumpkick.build/install.sh | JK_VERSION=<pin> bash`) into an isolated
`JK_HOME`, builds the checkout with it, then hands the home to the checkout's own JumpKick
(`jk install`) and lets that JumpKick run the house-rule gate, the fast test tier, the curated
integration lane (`scripts/curated-integration.sh` over `curated-integration.txt`, budgeted at
eight minutes) and the release tier (`jk test -s release`). A guard failure is annotated with the rule that owns it.

| Question | Answer |
|---|---|
| What must be green to merge | the self-host job, the shell fixtures job, the workflow lint (actionlint, commit-pinned actions, read-only tokens: `scripts/check-workflows.sh`, the same rules `jk guard` holds as `workflow-pins-permissions`) and the authorship scan |
| What produces the `jk` under test | the hosted release the pin names bootstraps; `jk install` then swaps in the checkout's own client, engine and workers |
| Where CI installs it | `$GITHUB_WORKSPACE/../jk-home`, beside the checkout so no guard lane walks the store — never the runner's `~/.jk` |
| What proves the graph is honest | `jk build` must not rewrite the committed `jk-lock.toml`; `cmp` of `target/dist/jk` against `$JK_HOME/bin/jk` and the engine sha against `jk-engine.toml` prove the takeover |
| What audits the graph | `jk audit -s HIGH -O jsonl` over the committed lock, after the drift check, by the checkout's own JumpKick: HIGH, CRITICAL and unlabelled advisories fail the job, `scripts/ci-audit-annotate.sh` names each one, the JSON is the `jk-audit` artifact; a pin bump or an `[audit] ignore` with a reason is the fix, never a skipped step |
| What runs nightly | `jk test -s integration`, `-s slow`, `-s network`, `-s bench`, the coverage ratchet (`jk test --coverage`, `jk guard`), the heap guard, the doc examples, the audit at `LOW` (informational), the macOS product smoke and the JVM client smoke on a runner with a plain JDK and no GraalVM (`ci-nightly.yml`) |
| Wall-clock series | `bench/scoreboard --tier night` on the reference host, banked in `scoreboard.toml` ([perf](../perf/README.md)) |

Guard **G57** (`ci-cadence`) keeps the self-host job, its isolated `JK_HOME`, the verbs it
runs (`jk install`, `jk guard`, `jk test`, `jk test -s release`; the install is the build), its bootstrap through `install.sh` at the
pinned version, the nightly tiers, the JVM client smoke and the scoreboard in place. It fails on a
`gradlew` in the self-host job, on a `JK_VERSION=<digits>` literal in any workflow, and on
`continue-on-error` on any job of `ci.yml`: that flag is how a merge requirement becomes a
courtesy run without anyone deleting a job.
Guard **G63** (`curated-integration`) owns the curated registry's shape, the classes it names, the
lane script and its step in the self-host job.

### The bootstrap pin

`.jk/ci/ci-bootstrap-version` holds one line: the release every workflow bootstraps from. It is the
only place that version is written. Every workflow bootstraps through `scripts/ci-bootstrap.sh`,
which installs that release into the job's `JK_HOME` (and builds the bridge below when one is
named). G57 refuses a workflow that spells the version itself, a pin that is not one `x.y.z`, and a
pin newer than `jk.toml`'s own `version` — a bootstrap release is cut from a tree, so it is never
ahead of one.

`.jk/ci/bootstrap-reads.txt` lists the top-level lock keys JumpKick's CI builds the tree with reads,
generated by `scripts/bootstrap-reads.sh <commit>` from that commit's `LockfileReader`: the commit
the pinned release was cut from, or the bridge while one is named. G112 refuses a lock carrying a
key outside it — the change that would leave CI with no JumpKick able to read the tree. A changed reading
of a key that JumpKick already knows is invisible to it; that needs a `jk-min` floor, which G105 holds.

After a release ships to jumpkick.build:

1. Prove the release can build this tree, from a scratch home that is not your `~/.jk`:
   ```bash
   export HOME=$PWD/../bootstrap-probe JK_HOME=$HOME/.jk
   curl -fsSL https://jumpkick.build/install.sh | JK_VERSION=<new> bash
   "$JK_HOME/bin/jk" install --skip-tests && "$JK_HOME/bin/jk" guard
   ```
2. Write `<new>` to `.jk/ci/ci-bootstrap-version`, delete `.jk/ci/ci-bootstrap-reader` if a bridge was
   named, and run `scripts/bootstrap-reads.sh v<new>`.
3. In `release.yml` and the nightly `os-smoke` matrix, add a row for every platform whose client
   `releases/<new>/SHA256SUMS` lists ([releases](releases.md#platforms-without-a-hosted-client)).
4. `jk guard`, then commit the pin and the workflows together.

### The bootstrap chain

The pinned release is the only JumpKick that can build this tree from nothing, so the tree must stay
within what that release reads. Two facts keep it there, each held by a guard on every
`jk guard`: **G86** (`lock-version-is-one`) holds that the lock's `version` is the frozen schema
every hosted release reads, and **G105** (`bootstrap-pin-reads-tree`) that the lock's `jk-min`
floor never exceeds the pin, with a tree fixture that proves it bites. A branch that changes a manifest key or the
lock format so that the pinned release cannot read the tree has no bootstrap at all, so a format change ships as
two releases, in this order:

1. **Reader first.** A release whose engine reads the new format *and* the old one, while the
   tree still writes the old. Host it, prove it with the probe above, bump the pin to it.
2. **Writer second.** Only once that pin is in place does the tree start writing the new format.
   The pinned release reads it, so the self-host job keeps its bootstrap.

When the tree already carries a format the pinned release cannot read — when `clients/web` started
writing a `[node]` lock table, and when the root set `[test] cache = "cone"` — CI bridges it until a release that reads the
tree is hosted. `.jk/ci/ci-bootstrap-reader` names a bridge commit (one full sha) that the pinned
release can build and whose JumpKick reads the tree; `scripts/ci-bootstrap.sh` checks it out beside the
job, has the pinned release relock it in its own format (`jk lock -F`: the bridge checkout is
thrown away, never committed) and build and install it, and every later step of the job builds the
tree with the bridge. A bridge that adds a plugin and uses it in the same tree is built without the
members that open a table the pinned release's plugins do not own, so the release builds that plugin
and the bridge's JumpKick reads the whole tree. `.jk/ci/bootstrap-reads.txt` then records the bridge's reads
(`scripts/bootstrap-reads.sh <bridge sha>`). G57 holds the file to one full sha. It goes when the
pin moves to a release cut from the tree (step 2 above); G112 then judges the lock by that release.

By hand, for a branch under development the same chain is:

```bash
export JK_HOME=/some/scratch/home            # never your ~/.jk
git worktree add ../jk-reader <last commit the pinned release reads>
(cd ../jk-reader && curl -fsSL https://jumpkick.build/install.sh | JK_VERSION="$(cat .jk/ci/ci-bootstrap-version)" bash \
   && "$JK_HOME/bin/jk" lock --force \
   && { "$JK_HOME/bin/jk" install --skip-tests || true; } && "$JK_HOME/bin/jk" install --skip-tests)   # a jk that reads the branch
"$JK_HOME/bin/jk" install --skip-tests            # the branch, built by it
JK_HOME="$JK_HOME" jk engine stop -n
```

Your own `~/.jk` falls behind the same way: an install older than a commit that adds a plugin
and uses it in the same tree cannot build that tree. `scripts/reinstall-chain.sh` reinstalls from
the checkout anyway. It tries the plain `jk install -S` first.
When the failure is a table no installed plugin owns, it installs HEAD from a scratch worktree
without the members that open that table, so the tree's own plugin for it is installed. Any other
failure bisects the first-parent history after the installed JumpKick's source (its engine jar's
`Build-Time`) for the newest commit the installed JumpKick builds, and installs that. Then it tries the
checkout again. Every stage installs the way the checkout does, as a snapshot (`jk snapshot` on a
JumpKick old enough to have it, whose `install` would shelve the release). Scratch locks are forced
and thrown away; the checkout is never edited.

The failure mode names itself: a JumpKick older than the lock's writer refuses the lock with
`written by JumpKick <newer> and this is JumpKick <older>; upgrade JumpKick, do not re-lock` instead of suggesting
`jk lock`, which would restate the branch's lock in the old format and hand a newer tree an older
lock. A manifest key the pinned release does not
know fails its parse with the key's name and the keys that table accepts; the remedy is the same
chain. The self-host job's bootstrap step is where either shows up first, which is why the pin
moves only after step 1's release is hosted.

### What the lane catches

Blocking is the measured position, not a default. The failures to expect are the shared test
suite's timing-sensitive population, because the lane runs `jk test` on a four-core runner: an
interrupt-ordering assertion or a cache-freshness assertion that holds on a quiet machine and
loses under load. Fix the flake, not the lane. Sonatype 429s appear in every run and the
resolver's retries absorb them.

### Cache and home isolation

The CI lane sets `JK_HOME` to a directory inside the workspace, so the store, the action cache and
the engine jar all come from that run. Nothing it reads survives from another job, and nothing it
writes leaks into one. Locally the same isolation is a `JK_HOME=/some/scratch/dir` prefix; do not
point a scratch run at your real `~/.jk`, and stop the scratch engine when you are done
(`JK_HOME=… jk engine stop -n`).

## Bootstrap

Install the released JumpKick and let it build the tree; the tree's own JumpKick then takes over:

```bash
curl -fsSL https://jumpkick.build/install.sh | bash   # or JK_VERSION="$(cat .jk/ci/ci-bootstrap-version)" bash
export PATH="$HOME/.jk/bin:$PATH"
jk engine status
jk install -S   # builds the tree; this checkout's client, engine and workers replace the release's
jk install -S   # once more: the release's client stops after the pass its engine ran
```

A released JumpKick whose `jk install` installs the declared version rather than its `-SNAPSHOT` would
write the tree's version as a release that a later `jk install` then refuses. Bootstrap it once with the root
`jk.toml` `version` written `<next>-SNAPSHOT` (do not commit that edit), `jk install -S`, then restore the
version and continue with `jk install`.

`jk install` is what makes the checkout self-hosting rather than merely built: from then on the
engine running your builds is the one you just compiled ([Install JumpKick with JumpKick](#install-jumpkick-with-jumpkick)).
The native client needs a GraalVM-capable JDK on the machine: `jk build` links it with the GraalVM
`--graal` / `GRAALVM_HOME` names, else an installed one.

A platform with no hosted native client (macOS on Intel) bootstraps through the installer's JVM
client; [releases](releases.md#platforms-without-a-hosted-client) says how the first hosted client for a
platform is produced.

### The JVM client

The client module is a plain JVM program, and two things run it without a native binary:

- **`jk install` writes `bin/jk-jvm`** beside the native client — the same `java -cp` launcher
  every application install gets, over the shelf's `jk-cli` jar and its closure — and writes it
  alone when the build linked no native binary. `jk-jvm --version` and `jk-jvm build` talk to the
  engine this same install put under `lib/jk-engine/`, so a checkout that has a JumpKick of any kind can
  produce a JVM client for the machine it runs on. The tree's own build still links the native
  client, so on a machine with no GraalVM it provisions one (`--yes` answers the offer) rather
  than needing one preinstalled. An install puts the PATH client in place before the engine, and
  refuses to install the engine when `bin/jk` is still a released JumpKick older than it (no native
  client linked, or `-m server/engine` alone): that client would hand every command to itself.
- **The release's JVM client**, `jk-<version>.jar` under `releases/<version>/`, is the CLI
  module's assembly under its shipped name; `install.sh` / `install.ps1` install it on a host with
  no native client, and the jar writes its own launcher (`jk self write-launcher`) over the JDK the
  installer found — see [releases](releases.md#platforms-without-a-hosted-client) and the [user
  install page](../user/install.md#the-jvm-client).
- **The published closure**, `cc.jumpkick:jk-cli:<version>` on `jumpkick.build/repo/`, runs from
  any project that names the repository and depends on it: `jk run . -- -V` executes
  `cc.jumpkick.cli.Jk` from the resolved jars, and `jk install` of that project writes a launcher
  of the project's name that does the same. Both were exercised against the hosted 0.13.2 closure
  and print `jk 0.13.2`.

What the JVM client cannot do yet is *bootstrap* a platform with no JumpKick: a JVM client only pairs
with an engine of its own version, and the hosted pieces do not pair today — `repo/` holds the
0.13.2 closure while `releases/` holds the 0.13.3 engine (there is no `releases/0.13.2/`), so a
0.13.2 JVM client stops at `no build engine for JumpKick 0.13.2` on its first build. The owner closes
the gap by publishing the 0.13.3 closure the way [releases](releases.md#platforms-without-a-hosted-client)
describes (`jk install`, then `scripts/publish-maven-repo.sh`), after which a JVM client from the
closure materializes the hosted engine with `jk self materialize <client> <engine-jar>`. Until
then the JVM client is produced by the tree's own build, and `ci-nightly.yml`'s `jvm-client` job
runs it nightly on a runner that brings a plain JDK and no GraalVM: `jk install -S`
writes the launcher, then `jk-jvm --version` and `scripts/ci-product-smoke.sh` with
`JK_BIN=jk-jvm` build a fresh sample through the installed engine under the same `JK_HOME`.
G57 holds the job, its lack of `setup-graalvm` and both commands in place.

## Dogfood (same tree)

After `jk` is on `PATH`, stay in this checkout:

```bash
jk lock
jk install                      # builds, runs the fast tier, then installs
jk test                          # the fast tier, every module
jk install -g                   # every integration and e2e class: the heart of the Full tier
```

Workspace members: libraries, `clients/cli`, `clients/cli-terminal`, `clients/web`, `server/engine`
(assembly fat jar), the rule packs, all first-party `plugins/*` workers (thin jars;
`PluginMain` implied by `jk-plugin.toml`), and `e2e/cli`, the CLI's tests that drive a real engine
(tests only, `[publish] skip`). Those classes spawn nested engines in
isolated sandboxes, so `jk test` in this tree never touches your resident engine's state. Those
engines run this checkout's engine: `jk test -m jk-cli-e2e` packages `server/engine`'s fat jar before
the suite starts and hands it to the tests as `-Djk.engine.jar`, so an engine or library change is
exercised without `jk install` first. The workers those engines fork are the checkout's too
(`-Djk.<worker>.plugin.jar`), and so are the libraries they run on: a first-party worker jar
carries the workspace libraries it depends on (its POM names only external ones), and
`shared/plugin-sdk` and `shared/host` join its classpath from their `target/` trees, so a library
change reaches a nested engine's workers once the worker's module is rebuilt. A suite that runs the
native client gets the one `clients/cli` links (`-Djk.native.client`), and waits for the link:
never `target/dist/jk`, which the build assembles after every module's tests, so a suite that ran it
would be keyed on the previous build's client and run again on the next.

## Default repositories

With no `[repositories]` table, remotes are **Maven Central then Google Maven** (local CAS /
`repos/*` / `~/.m2` still win first). R8 and Android coords do not need an extra google stanza.

## House-rule gate

The house rules in [code-as-art.md](code-as-art.md#the-guard-registry) are the rules of
`jk-guards.toml` and the guard tests under each module's `src/guard`. The model, module,
workspace and output lanes run in every `jk build`; the tree lane (text, metric, parity,
generated) and the fixture proofs run on `jk guard` and `--guard`, which is what CI's self-host
job runs after the build.

Nullness is part of the gate: every module with Java sources compiles with Error Prone + NullAway
at error severity through its own `[javac]` and `[javac.plugins.ErrorProne]` tables, and G53
(`null-marked-packages`) keeps every production package `@NullMarked`.

```bash
jk build                 # the lanes run with the build: model, module, workspace, tree, output
jk guard                 # the lanes alone
jk guard explain <id>    # one rule, with its source and its exemptions
```

A rule the closed vocabulary can express is a `[guards.<id>]` table; a rule it cannot is a guard
test — a `@Guard` method in `HouseRules`, `ParityRules` (`server/guard/src/guard`), `EngineRules`
or `CliRules`. The registry table in code-as-art.md says which enforces each letter, and G79
(`guard-letters-registry`) holds that table to the rules and guard tests that exist.

Each lane is keyed to what it reads — the model lane to the manifests and lock, a module lane to
that module's classes, the tree lane to the text corpus — so an unchanged input skips its lane and
an edit re-runs only the lanes it can affect. A lane writes a verdict, never an artifact, and only
a success is recorded, so a red lane goes red again instead of replaying itself. Every broken rule
is reported, each with its baseline state and the exemption path, and the machine view lands in
`target/jk-guards.sarif` and `target/jk-guards.jsonl`.

## Test tiers

The default suite (what no suite claims) is the fast tier and each root `[test.suites.*]` table
that claims a tag is one slow tier; members inherit them:

```bash
jk test                          # fast tier: what no suite claims
jk test -g                       # the guard suites, which the Full tier (jk install -g) runs
jk test -s tree                  # the tests that scan every module's sources (Branch, Queue, Full)
jk test -s slow                  # framework / language e2e (nightly)
jk test -s network               # talks to a real remote (nightly)
jk test -s release               # a real browser (CI on the release tag)
jk test -s live                  # this machine's OS keychain (on demand, desktop only)
jk test -s bench                 # microbenchmarks (nightly)
jk test -A                       # every suite but the explicit ones (live, bench)
```

Those tables are the only copy: the engine's `tiers` validation (G23) re-derives the partition from it
and proves, over every subset of the tag vocabulary, that each is run by exactly one tier, and G52
(`test-tier-docs`) renders it into [test-suite-tiers.md](test-suite-tiers.md). A tag claimed by
two suites, or claimed and excluded by every one, fails the build rather than running twice or never.

## Install JumpKick with JumpKick

One command installs everything the tree builds:

```bash
jk install           # or -S; the install runs the suite like `jk build`
```

| Module kind | What `jk install` does with it |
|---|---|
| Library (`shared/*`, `server/*`, rule packs) | Thin jar + POM onto the shelf, `~/.jk/store/repos/jk-local/<g>/<a>/<v>/` |
| Plugin worker (`plugins/*`) | Same shelf entry; launch rebuilds the runtime classpath from that POM |
| `server/engine` — declares `[install] product-lib = "jk-engine"` | Assembly jar materialized into `~/.jk/lib/jk-engine/`, pointer stamped by sha; the next client invocation takes over the resident engine. The shelf is pinned to that engine in `jk-shelf.toml` beside the pointer ([the pinned shelf](#the-pinned-shelf)) |
| `clients/cli` — declares `[install] product-bin = "jk"` | Native binary replaces `~/.jk/bin/jk` (previous client parked as `.old`, `jkx` re-linked), the same swap `jk self update` performs — and `~/.jk/bin/jk-jvm` (`jk-jvm.cmd` on Windows) is written beside it: `cc.jumpkick.cli.Jk` on a JVM over the shelf's `jk-cli` closure. On a machine that built no native client (no GraalVM) the launcher is the whole install and the PATH client is left alone |

JumpKick's own tree declares `[install] destination = "jk"` at the workspace root, so every module installs to the
shelf and nothing is written to `~/.m2`; a project outside the tree installs to the shelf by default
too, and is not pinned to an engine: only a tree that declares a product layout, and a plugin
worker, are. The two `[install]` product keys exist
only because JumpKick installs itself — no other project should declare them. The shelf is also what
`scripts/publish-maven-repo.sh` stages for `jumpkick.build/repo/` ([maven-repo](maven-repo.md)).

A module the forecast finds clean is still checked against its destination: a shelf entry, engine
jar or PATH client holding other bytes than the build output is reinstalled.

### The pinned shelf

The shelf is keyed by coordinate, and two checkouts at one version publish to the same slots, so
an install from a second worktree replaces the jars the first worktree's resident engine would
fork next. The engine therefore names its workers by content, not by path:

- Every shelf publish puts the bytes into the store's artifact CAS (`<store>/sha256/`) before it
  moves them into `repos/jk-local/`, and publishes the jar and its `.jk` memo under one lock
  (`repos/jk-local/.shelf.lock`), the memo hashed from the staged copy — so the memo beside a jar
  always describes that jar, however two installs interleave.
- `jk install` (and `jk self shelve`) then writes **`~/.jk/lib/jk-engine/jk-shelf.toml`** beside
  the engine pointer: the engine's jar sha, the checkout it came from, and the thin jar of every
  module the pass shelved by `group:artifact:version` and sha256, with the POM published beside it
  under `[poms]`. The shas are the ones the engine's `cache-install` step reports on each module's
  outcome (`shelfJarSha256` / `shelfPomSha256` on `module-finish`), never a re-read of the shelf, so
  an install from another checkout landing after the pass cannot lend this manifest its bytes. A
  cwd-scoped install shelves the selected cone only and rewrites only those pins; the rest stay as
  they were. The write is a locked read-merge-write (`jk-shelf.toml.lock`), so two installs for one
  engine keep each other's pins.

  ```toml
  engine-sha256 = "…"
  source = "/home/me/src/jk"
  installed-at = "2026-09-20T12:00:00Z"

  [jars]
  "cc.jumpkick:jk-java-compiler:0.13.4" = "…"

  [poms]
  "cc.jumpkick:jk-java-compiler:0.13.4" = "…"
  ```

- An engine adopts the manifest while it names the engine's own jar (re-reading it, so a reinstall
  of the same engine moves its pins) and keeps the one it adopted once another install has written
  the file for another engine. At every fork the worker launcher resolves each `repos/jk-local` jar
  through it: a shelf jar whose bytes are the pinned ones is copied into the CAS as before; one
  another install replaced is served from the CAS at the pinned sha; a pinned sha the store no
  longer holds fails the launch naming both shas and the checkout to reinstall from. The worker's
  runtime closure is walked from the pinned POM the same way — the shelf's while it is the pinned
  one, the CAS copy once another install replaced it — so a worker never forks on another
  checkout's dependency list. A jar the manifest does not name (a third-party
  `jk install <file.jar>`) launches as the shelf has it.

Two worktrees installing in turn therefore leave the live engine the last installer's, every fork
of it running that installer's workers, and the displaced engine draining with the workers it was
installed with. `jk engine status` prints the checkout the hosted engine's shelf came from as its
`Source` row (`installSource` in `-O jsonl` and `GET /api/status`), and the install's summary
says how many jars it pinned and to which engine. An engine run from a classes directory has no
jar identity and pins nothing.

The pass is run by the engine the home names when it starts, and every artifact-shaped action key
names the engine that packaged the artifact. So when the pass materializes another engine than the
one that ran it, `jk install` runs one more pass under that engine (announced as *re-shelving*):
a `RESHELVE` plan that only `cache-install`s jars already on disk and restamps shelf packager
memos — it does not re-compile, re-test, or re-package. One command leaves the shelf packaged by
the tree's own engine. An engine serves only clients of its own version, so a client of another
version than the tree's (the released client a contributor bootstraps from, the previous release
the hosted CI installs with) stops after the first pass, succeeds, and says which client runs the
second — the PATH client the pass installed: run `<home>/bin/jk install -S` once more,
which is what the CI takeover step does. A client older than this second pass stops silently after
the first. The passes are bounded at two, so when the re-shelving pass itself ends on yet another
engine, `jk install` says so and asks for that one more run.
`scripts/check-shelf-descriptors.sh "$JK_HOME"` then proves the shelf: every first-party worker
jar's root `jk-plugin.toml` names its own module's `[plugin] table`.

No action key carries the installed engine's identity. The first **build** under a freshly
installed engine re-runs only the actions whose engine code changed: test suites when the suite
code (`KeyContract.SUITE`) moved, plugin steps, guard lanes, build-logic runs and packaging when
the artifact code (`KeyContract.ARTIFACT`) moved, native images when the native-image driver
(`KeyContract.NATIVE`) moved. Guard G167 keeps each contract's recorded digest equal to its
sources', so a change to that code always moves those keys. Compiles never re-run for the engine.
The preflight dirty memo still stores the engine's code id beside `cacheKeyVersion`, so a memo
certified under the displaced engine makes the build walk its keys again (cheap, and every
unchanged key hits) rather than vouch for keys another engine may spell differently. Any such
re-run happens in a later `jk build`, not in the install's re-shelving pass. A later
`jk install` whose forecast finds only `cache-install` left (packaging already keyed under the
live engine) uses the same thin parse + cache-install plan as re-shelving.

## Ship layout

JumpKick's ship shape is **native CLI** + **JVM engine** jar + PluginMain workers, plus the
**JVM client** jar for hosts with no native CLI. `jk build` writes it under `target/dist/`
(`.jk/scripts/after-build-dist.kts`), and `install.sh` installs it on a machine with no JumpKick yet — or into
a private home ([Try the tree on another project](#try-the-tree-on-another-project)):

```bash
jk build -S
./install.sh target/dist/jk                     # the native client
./install.sh target/dist/lib/jk-<version>.jar   # the JVM client, on a host with no native one
```

```text
target/dist/
  jk                         # native CLI
  lib/
    jk-engine-<version>.jar  # JVM engine assembly (includes web SPA)
    jk-<version>.jar         # JVM client assembly (cc.jumpkick.cli.Jk and its closure)
    jk-maven-spy-<version>.jar  # Maven core extension `jk mvn` attaches (clients/maven-spy)
  repos/
    jk-local/                # every workspace module's thin jar + POM, Maven layout: the shelf
      cc/jumpkick/jk-java-compiler/<version>/jk-java-compiler-<version>.{jar,pom}
      cc/jumpkick/guards/spring/<version>/spring-<version>.{jar,pom}
      …
```

`install.sh <dist>/jk` (on Windows, `install.cmd <dist>\jk.exe`) copies the binary, materializes the engine jar from `lib/` (`jk self
materialize`) and shelves `repos/jk-local/` onto the home's `<store>/repos/jk-local/` (`jk self
shelve`, one `.jk` memo per artifact, the materialized engine recorded as the packager and the
jars pinned to it in `jk-shelf.toml`). The
engine launches its workers from that shelf and fetches a worker it lacks from jumpkick.build at
its own version, so the shelf is what makes a dist install run the workers built beside its engine
rather than the published ones of the same version. A dist with no `repos/` installs and says so;
a binary with no engine jar beside it is refused.

A `jk build` dist carries the release version `jk.toml` names; a `jk install` dist carries the
`-SNAPSHOT` line it installs. Installed into a home that already runs this checkout, a release dist
would shelve that release over the snapshot line, and a release hides its snapshot, so every later
`jk install` there would refuse. `install.sh` refuses that install before writing anything and names the module: install the tree into your own home
with `jk install`, and a dist into a private home.

The release workflow assembles `target/dist` per platform (`scripts/assemble-release-dir.sh`);
G75 (`ship-layout-installer-jk`) holds the installer and the dist script to the same `lib/` and
`repos/` names.

## Try the tree on another project

The checkout's own client and engine, run against a project that is not this tree, with `~/.jk`
untouched: install the ship layout into a private home. `JK_HOME` is the one knob; everything the
install writes lands under it, and the `jk` on your PATH keeps running the default home.

```bash
jk build -S                                   # target/dist/: client, engine jar, shelf
export JK_HOME=$HOME/src/scratch/jk-home                # any absolute path that is not ~/.jk
export JK_SELF_UPDATE=never                             # keep the dist build: no self-update
mkdir -p "$JK_HOME" && printf '[http]\nport = 0\n' > "$JK_HOME/config.toml"   # a fresh home: leave 8910 to ~/.jk
bash install.sh target/dist/jk                          # copies the binary, materializes the engine,
                                                        # shelves repos/jk-local, starts that engine
cd /path/to/another-project
"$JK_HOME/bin/jk" build                                 # the tree's engine and workers, on that project
"$JK_HOME/bin/jk" engine stop --now                     # stops only the private engine
```

The install prints the activation line for the private bin (`eval "$("$JK_HOME/bin/jk" activate
zsh)"`) and writes no rc block: a scratch home is not the `jk` every new shell runs
([install](../user/install.md#environment-overrides)). Calling the binary by its path, as above, needs
neither. To try a change, rebuild in the checkout with the `jk` on PATH and install again: `install.sh`
stops the private engine, swaps the binary, the engine jar and the shelf, and starts the new engine.

### Beside the default home

A private home runs its own engine next to the default home's, and two settings keep it from
fighting that engine or replacing itself. Both have to be in place before `install.sh`, which
starts the private engine.

- **`[http] port = 0`** in `$JK_HOME/config.toml`. Every engine binds the same fixed loopback port
  (8910) for its Web UI and MCP endpoint, and the default home's engine already holds it, so a second
  engine on the default port reports `Address already in use` and runs with no Web UI and no MCP
  over HTTP. Port 0 takes a free port; `JK_HOME=… jk engine status` prints the Web UI and MCP URLs on it.
- **`JK_SELF_UPDATE=never`** in the environment of whatever starts the private engine. A `jk build`
  dist carries a release version, not `-SNAPSHOT`, and only development builds are exempt from
  self-update; before 1.0 the config file's `self-update = "never"` is ignored, so a fresh home
  would replace the dist's binary and engine with the latest published release on its first check
  ([updates](../user/install.md#updates)). The engine takes the setting from the client that starts
  it: the shell running `install.sh`, a later `jk` command, or an MCP server.

An agent's MCP server has to carry both `JK_HOME` and `JK_SELF_UPDATE`. A client whose home comes
from no `JK_HOME` is the default home's client, whatever path it was started from, so it talks to
`~/.jk`'s engine. Some agents start an MCP server with only a short allowlist of environment
variables (PATH, HOME and a few more), not the shell's environment. Put both variables in the
server's `env` entry of the agent's MCP config when it has one; otherwise point the server's
command at a wrapper that sets them:

```bash
#!/bin/sh
export JK_HOME=$HOME/src/scratch/jk-home JK_SELF_UPDATE=never
exec "$JK_HOME/bin/jk" "$@"
```

| Under `$JK_HOME` (private) | Shared with `~/.jk` |
|---|---|
| `bin/jk` — the binary `jk build` linked | the managed JDK root, `~/.jdks` (`JK_JDKS_DIR`): the private engine and its compilers run on JDKs the default home already installed |
| `lib/jk-engine/` — the engine jar from `target/dist/lib/`, its pointer and the shelf manifest pinning the workers to it | `~/.m2`, read for third-party jars when `[m2] integration` is on |
| `store/repos/jk-local/` — every module jar from `target/dist/repos/`: the workers the engine launches, the rule packs, the libraries | nothing else. A private home never shelves into `~/.jk/store`: `repos/jk-local` lives in the store while its pins live in this home, so a dist shelved there would shadow the default home's installs with no pin naming it. `jk self shelve` (the installer's shelf step) and `jk install` refuse when `JK_STORE_DIR` or a `store` link points this home at the default store |
| `store/` (artifacts, templates, the library catalog), `cache/` (action cache), `state/` (the engine's socket, log and build history), `config.toml`, `creds/` | |

The engine a private client starts is the one under its own `lib/jk-engine/`, paired with the
client by version, so the handshake is between two artifacts of the same build. `JK_ENGINE_EXE` is
not that path: it names a dedicated engine executable whose `main` is the engine loop, the client
appends the JVM flags to its argv and recognizes the engine process by its executable, and a shell
script wrapping `java -cp … EngineMain` is neither. Use the private install.

`JK_HOME=… jk engine status` answers for the private engine; the `jk` on your PATH knows nothing
about it, and `jk engine stop` without `JK_HOME` stops the default home's engine, not this one.

## Wall-clock series

`scripts/dogfood-wall-measure.sh` builds this tree with JumpKick on one machine and writes three rows —
rebuild, warm no-op, and one file really edited — to `target/dogfood-wall/`, each measured raw
(`[guards] on-build = false`) and with the house-rule guards on:

| File | For |
|---|---|
| `row.md` | people: the rows plus the machine they were measured on |
| `row.jsonl` | tooling: one `env` object, then one `measurement` per side per row, walls in seconds |

The scoreboard's `dogfood` dimension runs it in the night tier and ratchets each row in
`scoreboard.toml` ([perf](../perf/README.md)).
