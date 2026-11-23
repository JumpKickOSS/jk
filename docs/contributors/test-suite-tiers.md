# Test suite tiers (one tier per tag)

**Goal:** keep the merge gate under **~5 minutes**, the gate that blocks a push under **10**, and
run the full e2e behind the push, nightly and on every release, not once per merge.

## Commands

One tier per tag. The table is the root manifest's `[test.suites.*]` tables (members inherit
them); the engine's `tiers` validation (**G23**) re-derives the partition from them and proves,
over every subset of the tag vocabulary, that each is run by exactly one suite, and **G52**
(`test-tier-docs`) renders the same tables into the block below, so the two cannot drift
([self-host](self-host.md#test-tiers)).

`jk build` runs every module's fast tier (the default suite: what no suite claims); `jk install`,
which is that same build plus the reinstall, gates every local merge into `main`; `jk install -g`,
which runs `[test] guard-suites = ["test", "integration", "e2e", "tree"]` against the JumpKick it installs, is
the heart of the Full tier ([gate tiers](#gate-tiers)). The house rules run under `jk build` and `jk guard`
([self-host](self-host.md#house-rule-gate)).

<!-- test-tiers:start -->
| Command | Claims | Excludes | In `--all` |
|---------|--------|----------|------------|
| `jk test` | what no suite claims | — | yes |
| `jk test -s integration` | `integration` | `slow`, `network`, `release`, `live`, `bench` | yes |
| `jk test -s docs` | `docs` | `integration`, `slow`, `network`, `release`, `live`, `bench` | yes |
| `jk test -s tree` | `tree` | `docs`, `integration`, `slow`, `network`, `release`, `live`, `bench` | yes |
| `jk test -s slow` | `slow` | `network`, `release`, `live`, `bench` | yes |
| `jk test -s network` | `network` | `release`, `live`, `bench` | yes |
| `jk test -s release` | `release` | `live`, `bench` | yes |
| `jk test -s live` | `live` | `bench` | no |
| `jk test -s bench` | `bench` | — | no |
<!-- test-tiers:end -->

**Suites are scope, tags are cost.** A suite that claims a tag takes its tests from every test
source a module has: `jk test -s integration` runs `e2e/cli`'s `src/e2e` integration test
(`E2eSuiteEngineTest`) beside every `src/test` integration class, and the `jk test -s integration`
step of `ci.yml` (release tag) and `ci-nightly.yml` is its CI run. A directory suite keeps what no
tag suite claims, and `e2e` is in `guard-suites`, so the Full tier (`jk install -g`) runs whatever `src/e2e` holds untagged. The
ci-cadence guard holds that every suite `--all` runs is run by a CI step (`jk test -s <name>`) or is
one of the `guard-suites` the Full tier runs. Members carry no `[test.suites.*]` of their own for these tiers: the root's tables
are the only routing, and a member may only add settings to them.

Tag new heavy tests at class level:

```java
@Tag("integration") // engine/e2e / Jk.execute / workers
@Tag("slow")        // Android / multi-minute / large downloads / waits out a real clock for 1s or more
@Tag("network")     // talks to a real remote (Maven Central, a forge)
@Tag("release")     // too slow for every queue batch, run before each release: a real browser, a multi-minute end-to-end (the Full tier, CI on the release tag)
@Tag("live")        // writes to this machine's own services (the OS keychain); desktop, on demand
@Tag("bench")       // microbench only (never PR)
@Tag("docs")        // walks the doc tree (docs/, the root Markdown); every gate tier runs it alone
@Tag("tree")        // scans every module's production sources; the Branch, Queue and Full tiers run it
```

**A test that scans the whole tree runs in the tree suite.** A suite is keyed on every file its
tests read, so one method that walks every module's `src/main/java` keys its module's whole fast
tier on the whole tree: an edit to an engine method body would re-run all of `shared/core`.
Tag that method `@Tag("tree")` (the method, not the class, when the rest of the class reads only
its own module) and the edit re-runs only the tree suite. A test that reads a few named files
outside its module stays where it is.

**The fast tier reads nothing that names the checkout or the host.** The same key is why: a read of
the checkout's `.git` (in a linked worktree, a file naming that worktree) or of `/etc/passwd`
keys the suite on which checkout or host ran it, so no other worktree, box or queue runner reuses
its result. `HermeticReads` tells the checkout's git directories from a stat and the common
directory's `worktrees` list, never by reading `.git`; it points JGit's user configuration and its
own settings (`jgit/config`) into the sandbox, so neither `~/.gitconfig` nor `~/.config/jgit` is
read. A test that starts an engine's HTTP front hands it a hostname through the front's environment
seam rather than let it read the host's `HOSTNAME` or `/etc/hostname`. A test that must compare
against the host's own files is tagged `integration`.

**No blocking tier waits on a clock.** A test in the fast or integration tier never sleeps,
polls or waits out a timeout for a second or more: it moves a `FakeClock`, waits on a latch, or
shortens the wait through the code's test seam (`…ForTests`). A test that can only prove its point
by letting a real second pass carries `@Tag("slow")` on that method, so it runs with the slow
suite (`jk test -s slow`) and not in the gates a landing waits on.

**A slow-tier failure the host caused says so.** On a box whose memory cgroup is smaller than the
slow tier's peak (a 10 GiB fleet box running a native link beside other suites' engines), the
kernel kills a forked worker and the build fails with an error that names neither memory nor the
kernel. A test that builds through workers takes `MemoryKills.mark()` before the work and, once
the work has failed, calls `abortIfAny(what)`: when the cgroup's `oom_kill` counter moved in
between, the test is skipped with a reason naming the count and the cgroup, instead of failing.
It never turns a passing test into a skip. `MinifiedPluginTest` and `TemplateCatalogE2eTest` do
this.

**The fast tier cannot hang, and cannot reach an engine.** The root's `fast-method-budget` is 5s:
a fast method over it is a warning, and one still running at four budgets (20s) is stopped and
fails. Every fast-tier JVM also carries `JK_ENGINE_NO_SPAWN=1`: a client that finds no engine
serving it fails at once with `EngineSpawnForbiddenException` instead of starting or taking one
over. Code under test that asks the engine for something (`ProjectInfos`, `EngineClient`) takes that
lookup as a parameter in a unit test; a test that needs a real engine is an integration test.

**The CLI's tests that need a real engine live in `e2e/cli`.** `clients/cli` declares no test
edge to `jk-engine` and no `test-plugin-jars`, so its suites read only the wire module, the shared
libraries and stubs, and an engine change that leaves `shared/wire` alone re-runs none of them.
`e2e/cli` (tests only, never shipped) takes the classes that spawn an engine or compile against
engine classes; its `jk-engine` test edge is what hands its JVMs the engine and worker jars this
build made and isolates their `JK_HOME` and `JK_STATE_DIR`. The harness both share (output capture,
the isolated roots, the engine extension e2e/cli registers) is `clients/cli`'s `src/fixtures`.

**A wire contract keeps both sides honest without an engine in the CLI's suite.** `shared/wire`'s
`src/fixtures` holds `WireContract`: exchanges written as literal JSON, each a request and the lines
the engine answers it with, naming the fields whose value is the sender's own. `server/engine`'s
`WireContractEngineTest` sends each request to the real `EngineConnection` and checks the answer
conforms; `clients/cli`'s `WireContractClientTest` points the client code a command runs at
`WireContractStub`, which serves the same answers, and checks what the client wrote and what it
read. Both are fast-tier classes, so a wire change that breaks one side fails that side's suite. A
case added to the contract fails the CLI's suite until the test drives it.

**The integration tier is walled off from the release site.** `[test.suites.integration] env` hands
its JVMs `JK_HTTP_DENY_HOSTS=jumpkick.build`: the version under test is never published while it
is being tested, so a test that needs a published release (an engine download, a release
checksum) is a `network` test. Maven Central stays reachable for now: `jk new` looks up the
newest stable version of what it scaffolds there, and about twenty integration tests start with
`jk new`.

**The fast tier is walled off from the network.** The launcher hands every JVM of a run of the
default suite alone (no `-s`, `-g`, `--all` or `-I`) in a module that routes tiers `JK_HTTP_DENY_HOSTS=*`: `Http` refuses a request to any
host but this one (loopback names and addresses) before it leaves the process, redirects and the
Central failover included, with a `DeniedHostException` that names the host. Loopback stubs
(`LoopbackHttp`, `MockMavenServer`) still answer. A unit test that needs a real repository or
release site is a `network` test — tag it. Fixture locks in the fast tier resolve their injected JUnit
Platform from the sandbox store the engine seeds (`TestStoreSeed`), never from the network.

**Every tag is run by exactly one tier.** G23 verifies the executable partition, and G52 verifies
that the marked table above matches it.

## Gate tiers

Five tiers judge a change on its way to a release, each cheaper and narrower than the next. The
first two block: a branch does not reach the queue without the Branch tier, and `origin/main`
does not move without the Queue tier. The Full tier blocks a release, which is cut from `green`,
the last commit it passed.

| Tier | Command | What runs | When | Blocks |
|------|---------|-----------|------|--------|
| Branch | `scripts/queue/branch-tier.sh` | `jk guard`, then `jk test --exclusive` over the modules `--affected-since <merge-base>` selects: the fast tier of the modules the branch changed and their dependents (every module when a file outside them changed). The modules they build on compile but their suites do not run, and no native client is built; the Queue tier runs both. Then `jk test -s tree`, and `scripts/gate/docs-only.sh check` as a warning | Before a branch is submitted to the merge queue | The submission |
| Queue | `GATE_TIER=queue scripts/gate/run.sh <n> <sha>` | `jk guard`; `jk install -S` (the reinstall without tests, so every test runs under the batch's own engine); one `jk install -c` (every module's fast tier, then the reinstall); the integration suite of each module the batch changed (`scripts/gate/changed-modules.sh`); the [curated lane](#the-curated-integration-lane)'s classes in every other module; `jk test -s docs`; `jk test -s tree`; the shell fixtures | Every batch the merge queue gates | The push to `origin/main` |
| Full | `scripts/gate/full-loop.sh`, each cycle `GATE_TIER=full scripts/gate/gate.sh <n> <sha>` | `jk guard`; `jk install -S`; `jk install -g -c` (the fast tier once, with every `integration`, `e2e` and `tree` class); `jk test -s docs`; `jk test -s release`; `jk test -s slow` when a plugin (a member under `plugins/`) or a root build file changed since `green` (`GATE_BASE`); the shell fixtures; the release notes | Back to back on the newest `main`, on a host of its own that shares no lock with the queue ([the Full tier loop](self-host.md#the-full-tier-loop)) | A release: it is cut from `green`. `main` may be red on it for a cycle or two: a confirmed culprit commit is reverted through the queue, and after two red cycles the queue lands only the fix |
| Windows, macOS | `scripts/gate/platform-loop.sh windows` and `macos`, each run `scripts/gate/platform-run.sh` on the host | In a checkout and home of their own on Daybreak (Git Bash) and bocamacmini: `jk install -S`; every module's fast tier; each fast or integration test class with an OS condition; the product smoke | Each tip the Full loop takes, beside its Linux gate; a busy platform skips to the newest ([Windows and macOS](self-host.md#windows-and-macos)) | Nothing at first: a red run files a ticket. Once a platform has been green for a week, `green` moves only to tips it passed too |
| Nightly | `ci-nightly.yml` | `integration`, `slow`, `network`, `bench`, the coverage ratchet, the examples | Its schedule, when the workflow is enabled | Nothing |
| Release | `ci.yml` on the release tag | The fast tier, `docs`, the curated lane, `release`, the whole `integration` tier, Windows and macOS | `jk release` pushes a tag | The release |

**What the Queue tier calls a changed module.** `changed-modules.sh` maps every file the batch
changed since `origin/main` onto the workspace member that holds it (the deepest one, so a rule
pack is not its parent). A file under `docs/`, `scripts/`, `.github/` or `bench/`, or a Markdown
file at the root, selects no module: guard, the fast tier and the shell fixtures read those. Any
other file outside every member (the root `jk.toml`, `jk-lock.toml`, `.jk/`, `templates/`)
selects them all, and the Queue tier runs the whole `integration` suite. The changed modules'
suites run with `--exclusive`: a module's dependents are not run, which is what the curated lane is
for. In the other modules a curated class runs alone, so no class runs twice.

**The slowest end-to-ends wait for the Full tier.** The Queue tier's integration step runs the
integration suites of up to three heavy modules side by side, and its wall is set by their total
CPU. A class of a minute or more that a release must pass, but that a batch rarely breaks, carries
`@Tag("release")` instead: the Helidon scaffolds served end to end (`HelidonTemplatesE2eTest`,
`HelidonQuickstartE2eTest`), publish and release against a local repository (`PublishCommandTest`,
`ReleaseCommandTest`), the cross-host key proof (`CrossHostKeyEqualityTest`), the forecast-against-run
matrix (`ForecastRunParityMatrixTest`), the mirror and group-root walks (`UserConfigMirrorTest`,
`GroupRootE2eTest`). The Full tier runs them on every cycle, so a break blocks the next release, not
the batch.

**A docs-only batch skips the Queue tier.** When every file a batch changed is a doc that no test
reads but those of the docs suite ([docs-only](self-host.md#docs-only-changes)), the queue gates
it with `GATE_TIER=docs`: `jk guard`, `bench/scoreboard --render --check` and `jk test -s docs`, no
install and no other test. The Full tier still runs everything on it.

**The docs suite runs in every gate tier.** `jk test -s docs` holds the tests that walk the doc tree.
They hold the docs to the code, so the Queue tier runs them after its integration runs, the Full tier
beside the release suite, and CI's self-host job after the fast tier; each runs the suite alone,
since a run of several suites records what they read as one
([docs suite](self-host.md#docs-only-changes)).

**Quarantine and re-run.** The gate uses JumpKick's own quarantine: a run red only in classes
`.jk/test/quarantine.toml` holds passes, in every tier, and every tier still runs them. A run of the
Queue or Full tier red in other classes, and in nothing but classes, runs once more under the same
inputs, which runs only those classes: it passes when they pass there, JumpKick records them as
flaky, and the gate's log names each with a `FLAKY` line.
The gate files a ticket for each class JumpKick quarantines and submits the file to the merge queue
([flaky tests](self-host.md#flaky-tests)).

## The shared-cache proof

`SharedCacheAcrossCheckoutsE2eTest` (engine, `integration`) builds one workspace from two
checkouts at different absolute paths through one cache: the second checkout restores every
compile, package and test step, a body-only edit in it reruns only that module and the tests on
its classpath, an API edit misses its consumer, and the first checkout keeps hitting throughout.
`ActionKeyTest` keys every family (javac, kotlinc, groovyc, artifact bags, the run-tests stamp)
from two roots through the production task id. A key factory that hashes a location fails both.

## The curated integration lane

The fast tier runs no integration class, and the full suite is too slow to gate on, so a wire,
spawn, worker, workspace, install or lock regression could merge with a deterministic integration
test — sitting in the nightly suite run — that would have caught it.

`scripts/curated-integration.sh` closes that gap. It reads `curated-integration.txt` at the
checkout root and runs one `jk test -s integration -c -m <module> -S … -m <module> -S …` over
every module the registry names, as a step of the self-host job on every run of `ci.yml`. The
Queue tier runs the same command (`--print`), with `--except` leaving out each module the batch
changed, since that module's whole integration suite runs anyway. Guard G63 fails on an entry whose
class is renamed or deleted, so the lane does not quietly shrink.

**Curated membership is a duplicate execution policy, not a tag.** Every listed class is
`@Tag("integration")` and still runs in the nightly suite run; nothing is reclassified to make the
branch gate cheap, and the lane never replaces `jk test -s integration`.

The registry has one line per class:

```
module | class | surface | outcomes | why it is merge-critical
```

One `jk test` for all the modules, so their classes run side by side, each module's in its own
isolated sandbox under the same workers, engine and home the fast tier used. `-c` keeps a red
module from stopping the next one, and the lane is red when any module is.

Six surfaces have to stay covered — `wire`, `spawn`, `workers`, `workspace`, `install`, `lock` —
each with at least one `success` and one `failure` entry. Guard **G63** (`curated-integration`)
rejects an entry that is missing, renamed, untagged, tagged into a nightly tier, or claims a
failure path the class does not show; it also fails when a surface loses a path, when the lane
script or its step in `ci.yml` is gone, or when the nightly stops running the full suite. The
registry's `integration-floor` line states the smallest `@Tag("integration")` population the
guard accepts from its scan; under it the guard refuses to judge, because a lane carved out of a
tier that shrank is not the coverage it claims.

### Budget and escalation

**8 minutes of wall clock**, enforced as `timeout-minutes` on the CI step that runs the lane. The
steps before it built and installed the tree and ran the fast tier, so the test classes are
compiled and the budget is the lane alone; the step prints its elapsed seconds into the run summary
so the budget is checked against a measurement.

Over budget, the answer is to **drop or split an entry**, never to raise the number: the lane
exists because the full tier is what a branch gate cannot afford, and a lane that grows toward the
full tier has stopped being a lane. Adding a class is fine when it buys a surface a path it does
not have; adding a second class for a path a surface already covers is what the budget is there to
refuse.

**A regression the Queue tier missed is the exception.** When the Full tier, nightly or a release
catches a real regression (not a flake) with an integration class that the Queue tier did not run,
because the batch changed some other module, that class joins the lane in the same change that
fixes the regression, under the surface it guards, whatever that surface already holds. Its `why`
says which change outside its own module breaks it. If the lane then runs over budget, split it or
drop a class that never caught anything; never the one that did.

### Why `network` is off the merge gate

The gate must not depend on the network. Sonatype enforces a per-IP quota on Maven Central, so a
gate that needs a remote fails for reasons the change did not cause. `jk test -s network`
runs nightly in `ci-nightly.yml`, where a transient failure costs a re-run instead of a blocked
merge.

`bench` is off the gate for the opposite reason: a microbench prints medians and asserts nothing
about deltas, so gating on it would gate on CI noise. The one bench that does assert — the fat-jar
size comparison (`JarSizeBenchTest`, fixtures in `bench/jar-size/`, banked sizes in
`jar-size-baseline.toml`) — needs the installed `jk`, Maven, the fixtures' own Gradle wrappers and
Maven Central, which is why it lives in this tier rather than the gate; see
[docs/perf](../perf/README.md#fat-jar-size).

## Property tests (unit tier)

The resolver and the lockfile carry property-based tests on [jqwik](https://jqwik.net): generated
dependency universes for `PubGrubSolver` (`PubGrubSolverPropertyTest`, checked against an exhaustive
search), the `VersionSet` algebra (`VersionSetPropertyTest`), the lockfile writer/reader round trip
and the reader's one typed error on arbitrary text (`LockfilePropertyTest`), and
`MinimalToml.quote`/`unquote` (`MinimalTomlPropertyTest`). They carry no tag, so they run with the
fast tier on every `jk build` and `jk test`; each property is budgeted in tries so the four classes
add a few seconds.

A failing property prints its shrunk sample and `seed = …` in the test report
(`<module>/target/surefire-reports/TEST-*.xml`, `system-out`). To replay one sample, put the seed on the
property: `@Property(seed = "-3119466389416338755")`. A shrunk sample that exposes a solver defect
becomes an example test in `PubGrubShrunkCounterexampleTest` so the fix stays pinned when the
generator moves on.

## Coverage ratchet (nightly)

`jk test --coverage` runs every module's unit tier under the JaCoCo agent and writes
`<module>/target/site/jacoco/jacoco.xml`; `jk guard` then evaluates `coverage-band` (G91), a `metric`
floor over `coverage.line` baselined per module in `jk-guards-baseline.toml` with a half-point band:

- more than 0.5 points **below** the entry: red, naming the module, the measured and the recorded
  value — cover the change, or `jk guard freeze coverage-band -W "…"` and say why;
- more than 0.5 points **above** the entry: the entry is tightened in the same run. Locally that is
  a modified baseline to commit; in the nightly job it is reported as a would-tighten, for a
  contributor to bank;
- a module with no entry yet is a fresh violation until frozen at its measured value.

There is no percentage target and no badge. The number only moves without a hand in one direction.
To re-baseline after an intentional drop (a deleted test tier, a module split), edit the line and
say why in the commit; the ratchet does not lower a line itself.

## Redundancy / prune candidates (integration tier)

**Worked (2026-08-19) and largely disproved at assertion level** — the Cache-e2e,
JDK-install, and Android-ladder rows below looked like duplicates but cover different
layers/assertions; keep that distinction before re-adding anything here.

Measured profiling of a full `integrationTest` is expensive; use this as a **manual prune backlog** when editing those areas:

| Cluster | Keep | Consider folding into nightly-only / fewer cases |
|---------|------|--------------------------------------------------|
| Android ladder | Spike smoke + one Hilt/KSP + Release | Drop duplicate full Hilt path from default integration if Spike+Release cover gate |
| Cache e2e | One Java + one Kotlin freshness | Merge overlapping `BuildCacheTest` / Kotlin second-build cases |
| JDK install | One install success path | Share fixtures across Ensure/Update/Command |
| Tool run | One path per language | `ToolRunCommandTest` has many near-identical error/help methods |
| EngineServer / Http | Handshake + auth + one SSE | Deep host-header matrix → keep tagged, trim methods over time |
| Git backends | jgit | CLI-git matrix can stay `slow` or one smoke |

## CI

Which tier runs where. GitHub minutes are spent nightly and on a release, never on a push or a
pull request:

| Run | Started by | Tiers |
|-----|------------|-------|
| Merge gate | a developer, on their own machine | `jk install` (fast tier) |
| Queue and Full tiers | the merge queue's gate host | the Queue tier on every batch; the Full tier behind it ([gate tiers](#gate-tiers)) |
| No workflow | a push to `main`, a pull request | — |
| `ci.yml` | a release tag, or a dispatch | fast tier, curated lane, `release`; the whole `integration` tier on a tag and on `main`, and on another branch when the dispatch's `integration` input is on |
| `ci-nightly.yml` | its schedule, when the workflow is enabled | `integration`, `slow`, `network`, `bench`, coverage, the audit at `LOW` |

A commit that reaches `main` has passed the Queue tier; whatever only the Full tier runs judges it
when the Full tier next runs, or the next nightly or release run. `main` has no required GitHub
check, since no workflow runs on a push: the merge queue is its only writer. A `ci.yml` run on
`main` always includes the whole `integration` tier, so a commit there that fails an integration
test cannot show a green CI run. Nothing runs the integration or slow tier on Windows.

- **Release (`ci.yml` on the release tag):** the self-host job (`jk install`, which builds, then
  `jk guard`, `jk test`: the fast tier and every house-rule lane, run by the checkout's own `jk`;
  then the curated integration lane and `jk audit -s HIGH` over the lock), the shell fixtures, the
  workflow lint and the commit-authorship scan. No coverage, no benches. After the curated lane, the
  self-host job runs `jk test -s release`: the tests a release must pass that are too slow or too
  heavy for a queue batch, such as the dashboard's new-project wizard in Playwright's Chromium
  (`NewProjectWizardE2eTest`); the Full tier runs them every cycle. The tier may fetch (the browser,
  the dashboard's CDN, Maven Central). Beside it the
  `integration` job runs `jk test -s integration --continue`, and the Windows and macOS jobs wait
  for it. A red job stops the release.
- **Nightly (`ci-nightly.yml`):** `jk test -s integration`, `-s slow`,
  `-s network`, `-s bench`, the coverage ratchet (`jk test --coverage`, `jk guard`),
  the heap guard, the nightly [jk-examples](https://github.com/JumpKickOSS/jk-examples)
  scenarios and the audit at `LOW` on Linux; the product smoke on macOS.
- **Examples job:** every
  jk-examples scenario whose `scenario.toml` says `cadence = "nightly"` (or `pr`) is relocked and
  checked from a clean checkout with the commands its manifest lists
  (`scripts/scenarios.py list --cadence nightly --have docker`, then `scripts/scenarios.py check`),
  each node scenario on the Node.js its lock pins. Scenarios marked `dev = true` then run
  under `jk dev` until their front doors answer (`scripts/example-dev-smoke.sh --examples`, with
  each manifest's `dev-checks`). Afterwards no committed `jk-lock.toml` may
  change beyond first-party plugin checksums and no `package-lock.json` may change at all
  (`scripts/examples-lock-drift.sh jk-examples`). The same scripts run locally against a sibling
  checkout: `../jk-examples/scripts/scenarios.py check <scenario>`,
  `scripts/example-dev-smoke.sh --examples ../jk-examples` and
  `scripts/examples-lock-drift.sh ../jk-examples`.
- Local gate: `jk guard` (it formats what changed), `jk install` (the build plus the reinstall), `jk install -g`;
  `scripts/curated-integration.sh` runs the curated lane, as the release CI and the Queue tier do.
- Never the `network` or `bench` suite as a merge gate.

## Measuring integration wall time

```bash
jk test -s integration
# per-step walls: target/jk-profile.json; per-class times: **/target/surefire-reports/TEST-*.xml
```

## Measuring the Android lock (network tier)

`NiaWarmLockTimingTest` (`server/resolver`) locks a local `nowinandroid` overlay twice — cold process
caches, then hot — and prints the phase walls (`COLD_TOTAL_MS`, `HOT_TOTAL_MS`, the graph and
materialise splits). Its budget asserts apply only when the JVM sees every processor of the host and
the host is idle; a JVM pinned below the host (a quota on its own cgroup, an `-XX:ActiveProcessorCount`
in the jvm-args) prints `TIMING_ASSERTS_SKIPPED` with the reason, since its numbers describe a machine no
user has. Run the one class alone on a quiet host:

```bash
JK_NIA_OVERLAY=~/src/oss/jk-examples/04-clients/nowinandroid/overlay \
  jk test -s network -m server/resolver -w 1 \
    --class cc.jumpkick.resolver.NiaWarmLockTimingTest
# JVM_CPUS=… HOST_CPUS=… LOADAVG=… names the machine the numbers were taken on
```

The host's processors are the online ones, narrowed to a CPU quota on the root cgroup, so a WSL or
container host whose root cgroup allows 7 of 20 online CPUs is a 7-CPU host. A number taken while
anything else was building is load, not lock cost. The lock's gate is the scoreboard's `lock.nia.cold`
and `lock.nia.hot` rows ([docs/perf](../perf/README.md)), not this assert.

## One heavy run per machine

Many integration tests start a nested engine that runs a whole build, and each engine keeps its own
compiler pool, so JumpKick's full integration tier holds far more forked JVMs than any user build: on a
15 GiB host it peaked at 13.8 GiB, 6.6 GiB of it in 18 compiler workers and 4 GiB in 26 test JVMs.
Two such runs at once, or one beside another heavy job, can exhaust the host. Run one heavy job
per machine: `jk test -s integration`, an uncached full `jk build`, or a benchmark
(`bench/agent-loop`, `bench/wall`, [`bench/memory`](../../bench/memory/README.md), the jk-examples corpus). Benchmarks also need the host otherwise
idle, because they measure wall time and memory. Editing, narrow `-m` / `--class` runs and
`jk guard` in parallel worktrees are fine.

## Isolation

A forked test JVM never sees the developer's product layout or the engine's. `TestEnv` hands
every suite a sandbox home under `<home>/test-homes/<key>/home` (`TestHomes`: keyed by the
module's real path, outside the project, warm across runs) and pins every root under it:
`JK_HOME`, `JK_STATE_DIR`, `JK_STORE_DIR`, `JK_CACHE_DIR`, `JK_JDKS_DIR`, plus the workspace's
shared `JK_M2_LOCAL` and a temp root at `<slot>/tmp` (`TestHomes.tmpFor`: outside the project,
real-path spelling, emptied at each launch by `TestTmpDir`). The three roots are pinned
explicitly because `WorkerEnv` lets the engine's own spellings of them through by name: an engine
started with `JK_STATE_DIR` or `JK_STORE_DIR` in its shell would otherwise hand every suite its
real build history — and a nested `jk self nuke` its real store. With `W > 1` each runner gets a
child state dir and a jqwik database of its own under its temp root. The CLI's suite, whose tests
start real engines, also gives each runner a child home (`<home>/w<N>`, with its own `gitconfig`
and keychain), so its integration classes shard like any other: a `config.toml`, an installed
engine or a `jk engine stop` in one runner never reaches another's, while the store, cache, JDKs
and Maven local repository stay shared and warm.

The OS keychain is sandboxed the same way: `JK_KEYCHAIN_SANDBOX` names `<home>/keychain`, so
`SecretVault.current()` in a test JVM (and in every `jk` and engine it starts) is a file-backed
`SandboxKeychainBackend`, and a `SecretServiceBackend` or `MacKeychainBackend` over the real tool,
or the real `WindowsCredentialBackend` on Windows, throws `KeychainSandbox.Refused` (an `Error`)
before it reaches anything. `HermeticReads` fails a class that made one refuse, even when the code
swallowed it, and one that started `secret-tool`, `security` or a `busctl`/`gdbus` call to
`org.freedesktop.secrets`. Only the root's `live` suite sets `JK_KEYCHAIN_SANDBOX = "off"`; its
keychain tests write and remove items named `test/<uuid>` and nothing else.

The sandbox is shared by every suite of the module and kept between runs, so a loopback stub's
identity must not be its port: `LoopbackHttp` (and `MockMavenServer` on it) spells its base
`http://127.0.0.1:<port>/<token>` with a token no other start has had, because version lists,
fetch memos and the store's `repos/<id>` are all keyed by URL and a kernel reuses ephemeral ports.
The reaper that bounds `test-homes` walks a slot once and records its size beside the stamp; an
idle slot is not walked again until a launch re-stamps it or its suite's hold closes.

**One download pool per host.** A sandbox's slot is keyed by its module's path, so a new worktree
gets new slots. Its store would start cold too, and the network and slow tiers would spend minutes
re-downloading what another worktree already holds. Instead, every sandbox shares one pool,
`<home>/test-store` (`TestStorePool`):

- The public-origin repository trees (`repos/central`, `repos/google`, `repos/jumpkick`) and the
  build-tool distributions (`tools/kotlin`, `tools/gradle`, `tools/maven`) of every sandbox store
  are links into the pool at the same relative path. The engine places them when it prepares the
  sandbox, replacing a private tree left from before.
- The managed Android SDK is `<home>/test-store/android-sdk`. A suite reaches it through
  `TestCaches.pooled("android-sdk")`, which the launcher's `-Djk.test.pool.dir` points at the
  pool. A suite that writes into its SDK (a fake system image, an AVD: `AndroidSpikeTest`) keeps a
  private one under `TestCaches.dir`.

That content is safe to share because it is verified and published whole:

- A repository tree is keyed by the origin that served it, so a loopback stub, a `file:` repository
  or a fixture's own repository fills a tree of its own, never Central's. Every artifact lands
  through a temp file and an atomic rename, with its checksum verified.
- A tool distribution is checked against its pin or its publisher's checksum, staged beside its
  version directory and published by one rename. An Android component is checked against the
  feed's SHA-1 and published the same way, and an accepted license file is replaced whole.

So engines in several sandboxes can fill the pool at once. Everything else stays per sandbox: the
`jk-local` shelf a suite installs onto, version lists, the store CAS and the tool closure indexes
into it, Node and the other tools, JDKs, state, locks and ledgers. The pool sits beside
`test-homes`, outside every sandbox home, and JumpKick's deletes never follow a link. So `jk self nuke`,
`jk storage nuke`, `jk clean` and the slot reaper remove a sandbox's links and leave the pool alone,
and the reaper's size walk does not count the pool against a slot.

The pool has a reaper of its own, bounded by a cap of 4 GiB: about twice what the integration,
slow and network tiers fetch together (about 1.9 GiB), so a full run fits beside the last one
instead of evicting what the next run needs. A cap near one fill would evict on every full run.
What keeps the pool from becoming an archive is the week rule below, not the cap. On CI (`CI=1` or
`CI=true`) the cap is 8 GiB, since one runner serves many branches. Set `JK_TEST_POOL_MAX_MB`
(or `-Djk.test.pool.max.mb`) in the engine's environment to change it.

- **What goes.** Whole units, never single files: an artifact's version directory under
  `repos/<id>`, a tool version under `tools/<slug>`, an Android SDK component (the accepted
  licenses stay). A unit unused for a week goes whatever the pool weighs. Over the cap, the least
  recently used units go first, until the pool fits.
- **Last use.** Reads leave no trace JumpKick can rely on, since access times are off on many
  filesystems. So a pooled tree carries a `.jk-pool` marker, and in a marked tree the repository
  store, the tool registry and the SDK installer set a unit's directory mtime to now when a build
  uses the unit (`PoolUse`), at most once an hour per unit per process. Trees without the marker,
  a user's own store among them, are never stamped.
- **Never under a running suite.** A unit used within the last day is never evicted, over the cap
  or not, and its mtime is read again just before it goes. An evicted unit is renamed into
  `.trash` in one step and deleted there, so a reader finds the whole unit or none of it. A miss
  fetches it again, and a file a reader already has open stays readable until it is closed.
- **Leaks.** The same walk removes what a killed process left staged for over an hour, at any
  depth: an artifact's `.*.part` beside its version, a download's `.put-*.tmp`, a tool's
  `jk-tool-stage-*`, an SDK unpack's `.extract-*`, an SDK zip under `.downloads`, and whatever a
  killed reap left in `.trash`.
- **Cost.** Preparing a sandbox reaps the pool at most once an hour per host: `.reaped-at` records
  the last start, and `.reap.lock` keeps a second process from walking beside the first.

A test that writes fake bytes under a public coordinate or a real tool version, mirrors Central at
a stub (a `settings.xml` mirror fills the tree of the repository it stands in for), deletes from the
ambient store's trees, or must prove a cold download (first-run provisioning, checksum
confirmation) takes a private store: `@IsolatedStore` on the `:cli` class or method (guard
`cli-test-shared-store-trees`), or `@SysProps.TempRoots("jk.env.JK_STORE_DIR")` elsewhere. A store
named that way has no links, so it starts cold.

**The overlay rule.** Layout settings come in two layers: the `jk.env.<NAME>` system properties
(the in-process seam a test sets per class or method, forwarded by the engine spawner to the
engine it starts) over the real environment. A root override binds to the home it was set beside,
so a home named by the overlay takes its store, cache and state from the overlay alone — an
environment `JK_STORE_DIR` describes the shell's home and never reaches into an overlay home.
`JK_HOME=/scratch JK_STORE_DIR=~/.jk/store` in one shell still shares the store.

**Unit tests that read the state root** (`JkDirs.state()` / `JkDirs.builds()`: lock timings, host
calibration, learned weights) build it under a throwaway: `@SysProps.TempRoots("jk.env.JK_STATE_DIR")`
on the class, and `Calibration.invalidateMemo()` where a process-wide memo could carry a previous
reader's file. `:cli` classes use `@IsolatedState` / `@IsolatedStore` (guard `cli-test-isolated-state`).

**A test that writes the user's `config.toml`** (a mirror, a repository with a `${file:}` or
`${cmd:}` secret, a `[network]` proxy) takes a home of its own: `@IsolatedHome` on the `:cli` class
or method. The tier's home is shared by every class of the module, so a `config.toml` written there
reaches every later resolve. The isolated home has its own store, cache, state and engine; its
`config.toml` starts with `[m2] integration = false`, so a repository at Central's URL stores under
the home rather than in the pooled Maven local repository, and Maven's `settings.xml` is pointed
at a file that does not exist. Its `[templates] official` is a local bare repository built from
the checkout's `templates/` (`LocalTemplateCatalog`, beside the sandbox slot's home and named by the
content), so the home's engine clones its catalog without github.com; a test about the real
catalog's origin opts out with `@IsolatedHome(officialCatalogFromOrigin = true)`. A suite's shared
home names the same repository: `SharedHomeTemplateCatalog` (core fixtures), a launcher listener
that a suite starting engines lists in its `META-INF/services`, writes it into that home's
`config.toml` before the first test, so no engine of the tier clones from github.com. `RecordingProxy` (host fixtures), named as the
home's `[network] proxy`, is the request log of everything dialed past the loopback stubs.

**The fixtures' injected test roots are warm by construction.** Every fixture lock injects
`junit-platform-launcher` (and `junit-jupiter` when the fixture declares no test dependencies) as
`latest`, which needs a version list. When the engine prepares a module's sandbox it seeds that
sandbox's store from its own (`TestStoreSeed`): the JUnit Platform trees hard-linked under
`repos/central` (the pool, through the sandbox's link), and for each artifact a version list naming exactly the versions whose POM came
along, under the key the metadata cache reads for the Central URL. A cold sandbox therefore locks
its test roots without Central, and a Central that throttles this host cannot turn the tier red; a
real index the sandbox fetched itself is left in place. A store filled from a lock holds the jars
but not their POMs (a fresh CI runner's, a new machine's), so before its first test launch the
engine locks a throwaway project once when its store has no launcher or Jupiter POM, which puts the
test roots' closure into its own store with the network it has. Only those trees come from the
engine's store: everything else a fixture needs is exact-pinned and fetched once into the pool.

**Slots live as long as their module.** A sandbox slot's stamp names the module directory it was
handed to; every launch reaps the slots whose module is gone — a removed worktree, a fixture
project a suite made under a temp dir — before the age and size rules apply, so a full run of the
CLI tier leaves no slot per fixture behind. A slot a live launch holds is never reaped.

## Suites and tags

| Intent | Command |
|--------|---------|
| Fast / default | `jk test` (suite `test` only: what no declared suite claims) |
| Extra suite | `jk test -s integration` or put e2e under `integration/` |
| Everything | `jk test -A` / `jk build -A` (every suite but the explicit ones) |
| Tag filter | `jk test -E slow` / `--include-tags smoke` |
| One module | `jk test -m <module>` (its prerequisites' suites run and count too; `-X` for that module's alone) |
| One class | `jk test -m <module> -S <fqcn>` (`-s integration` for a tagged one) |

Prefer **directory suites** for structural separation (Mill-shaped); use **JUnit tags** for
cross-cutting cost filters inside a suite.

## Failure paths in the fast tier

A test of "the transport failed" never points at a closed port. `http://127.0.0.1:1/` is refused
instantly on a bare Linux or macOS host and hangs to the 10 s connect timeout wherever loopback is
relayed (WSL2, some VPN and container stacks); under the six-attempt retry ladder that is a minute
per fetch, and three suites once cost the fast tier six minutes that way. Guard **G107** bans the
literal. Use, in this order of fit:

| The failure under test | Fixture (`:host` fixtures, `cc.jumpkick.testing`) |
|------------------------|----------------------------------------------------|
| a reset / dropped connection | `DeadEndpoint.open()` — accepts and drops every connection, one attempt |
| a 404 / a repo that serves nothing | `LoopbackHttp` with nothing seeded |
| a repo that is never dialed | a `file:` URI of an empty temp dir |

Pair a dead endpoint with `Http.failFast()` where the test chooses the client: production's ladder
adds ~3 s of backoff to a failure that is otherwise instant. A suite whose failure path still needs
a real timeout is a `slow` suite, and says so with the tag.
