# Test suite tiers (one tier per tag)

**Goal:** keep the merge gate under **~5 minutes** and run the full e2e once per push, not once per merge.

## Commands

One tier per tag. The table is the root manifest's `[test]` and `[profiles.*]` tables; the
engine's `tiers` validation (**G23**) re-derives the partition from them and proves, over every
subset of the tag vocabulary, that each is run by exactly one tier, and **G52** (`test-tier-docs`)
renders the same tables into the block below, so the two cannot drift
([self-host](self-host.md#test-tiers)).

`jk build` runs every module's fast tier; `jk install`, which is that same build plus the reinstall,
gates every local merge into `main`;
`jk test -p integration` is the push gate, run on the local `main` before it is pushed. The
house rules run under `jk build` and `jk guard` ([self-host](self-host.md#house-rule-gate)).

<!-- test-tiers:start -->
| Command | Includes | Excludes |
|---------|----------|----------|
| `jk test` | untagged | `integration`, `slow`, `network`, `bench` |
| `jk test -p integration` | `integration` | `slow`, `network`, `bench` |
| `jk test -p slow` | `slow` | `network`, `bench` |
| `jk test -p network` | `network` | `bench` |
| `jk test -p bench` | `bench` | — |
<!-- test-tiers:end -->

Tag new heavy tests at class level:

```java
@Tag("integration") // engine/e2e / Jk.execute / workers
@Tag("slow")        // Android / multi-minute / large downloads
@Tag("network")     // talks to a real remote (Maven Central, a forge)
@Tag("bench")       // microbench only (never PR)
```

**The fast tier cannot hang, and cannot reach an engine.** The root's `fast-method-budget` is 5s:
a fast method over it is a warning, and one still running at four budgets (20s) is stopped and
fails. Every fast-tier JVM also carries `JK_ENGINE_NO_SPAWN=1`: a client that finds no engine
serving it fails at once with `EngineSpawnForbiddenException` instead of starting or taking one
over. Code under test that asks the engine for something (`ProjectInfos`, `EngineClient`) takes that
lookup as a parameter in a unit test; a test that needs a real engine is an integration test.

**The integration tier is walled off from the release site.** `[profiles.integration] env` hands
its JVMs `JK_HTTP_DENY_HOSTS=jumpkick.build`: the version under test is never published while it
is being tested, so a test that needs a published release (an engine download, a release
checksum) is a `network` test. Maven Central stays reachable for now: `jk new` looks up the
newest stable version of what it scaffolds there, and about twenty integration tests start with
`jk new`.

**The fast tier is walled off from the network.** The launcher hands every JVM of a launch that
includes no tag and excludes at least one `JK_HTTP_DENY_HOSTS=*`: `Http` refuses a request to any
host but this one (loopback names and addresses) before it leaves the process, redirects and the
Central failover included, with a `DeniedHostException` that names the host. Loopback stubs
(`LoopbackHttp`, `MockMavenServer`) still answer. A unit test that needs a real repository or
release site is a `network` test — tag it. Fixture locks in the fast tier resolve their injected JUnit
Platform from the sandbox store the engine seeds (`TestStoreSeed`), never from the network.

**Every tag is run by exactly one tier.** G23 verifies the executable partition, and G52 verifies
that the marked table above matches it.

## The shared-cache proof

`SharedCacheAcrossCheckoutsE2eTest` (engine, `integration`) builds one workspace from two
checkouts at different absolute paths through one cache: the second checkout restores every
compile, package and test step, a body-only edit in it reruns only that module and the tests on
its classpath, an API edit misses its consumer, and the first checkout keeps hitting throughout.
`ActionKeyTest` keys every family (javac, kotlinc, groovyc, artifact bags, the run-tests stamp)
from two roots through the production task id. A key factory that hashes a location fails both.

## The curated integration lane (every release tag)

The fast tier runs no integration class, and the full profile is too slow to gate on, so a wire,
spawn, worker, workspace, install or lock regression could merge with a deterministic integration
test — sitting in the nightly profile — that would have caught it.

`scripts/curated-integration.sh` closes that gap. It reads `curated-integration.txt` at the
checkout root and runs one `jk test -p integration -m <module> -S …` per module the
registry names, as the last step of the self-host job on every pull request. jk fails a run in
which no class matched, so a renamed or deleted entry fails the lane instead of shrinking it.

**Curated membership is a duplicate execution policy, not a tag.** Every listed class is
`@Tag("integration")` and still runs in the nightly profile; nothing is reclassified to make the
branch gate cheap, and the lane never replaces `jk test -p integration`.

The registry has one line per class:

```
module | class | surface | outcomes | why it is merge-critical
```

One `jk test` per module, so a module's classes run in that module's isolated sandbox under the
same workers, engine and home the fast tier used, and a red module does not stop the next one; the
lane is red when any module is.

Six surfaces have to stay covered — `wire`, `spawn`, `workers`, `workspace`, `install`, `lock` —
each with at least one `success` and one `failure` entry. Guard **G63** (`curated-integration`)
rejects an entry that is missing, renamed, untagged, tagged into a nightly tier, or claims a
failure path the class does not show; it also fails when a surface loses a path, when the lane
script or its step in `ci.yml` is gone, or when the nightly stops running the full profile. The
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

### Why `network` is off the merge gate

The gate must not depend on the network. Sonatype enforces a per-IP quota on Maven Central, so a
gate that needs a remote fails for reasons the change did not cause. `jk test -p network`
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

- **PR / push (`ci.yml`):** the self-host job (`jk install`, which builds, then `jk guard`, `jk test`: the
  fast tier and every house-rule lane, run by the checkout's own jk; then the curated integration
  lane and `jk audit -s HIGH` over the lock), the shell fixtures, the workflow lint and the
  commit-authorship scan. No coverage, no benches.
- **Nightly (`ci-nightly.yml`):** `jk test -p integration`, `-p slow`,
  `-p network`, `-p bench`, the coverage ratchet (`jk test --coverage`, `jk guard`),
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
- Local gate: `jk guard` (it formats what changed), `jk install` (the build plus the reinstall), `jk test -p integration`;
  `scripts/curated-integration.sh` runs what the pull request's boundary lane will run.
- Never the `network` or `bench` profile as a merge gate.

## Measuring integration wall time

```bash
jk test -p integration
# per-step walls: target/jk-profile.json; per-class times: **/target/surefire-reports/TEST-*.xml
```

## Measuring the Android lock (network tier)

`NiaWarmLockTimingTest` (`server/resolver`) locks a local `nowinandroid` overlay twice — cold process
caches, then hot — and prints the phase walls (`COLD_TOTAL_MS`, `HOT_TOTAL_MS`, the graph and
materialise splits). Its budget asserts apply only when the JVM sees every processor of the host and
the host is idle; a JVM pinned below the host (a cgroup quota, an `-XX:ActiveProcessorCount` in the
jvm-args) prints `TIMING_ASSERTS_SKIPPED` with the reason, since its numbers describe a machine no
user has. Run the one class alone on a quiet host:

```bash
JK_NIA_OVERLAY=~/src/oss/jk-examples/04-clients/nowinandroid/overlay \
  jk test -p network -m server/resolver -w 1 \
    --class cc.jumpkick.resolver.NiaWarmLockTimingTest
# JVM_CPUS=… HOST_CPUS=… LOADAVG=… names the machine the numbers were taken on
```

A number taken while anything else was building is load, not lock cost. The lock's gate is the
scoreboard ([docs/perf](../perf/README.md)), not this assert.

## One heavy run per machine

Many integration tests start a nested engine that runs a whole build, and each engine keeps its own
compiler pool, so jk's full integration tier holds far more forked JVMs than any user build: on a
15 GiB host it peaked at 13.8 GiB, 6.6 GiB of it in 18 compiler workers and 4 GiB in 26 test JVMs.
Two such runs at once, or one beside another heavy job, can exhaust the host. Run one heavy job
per machine: `jk test -p integration`, an uncached full `jk build`, or a benchmark
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
child state dir and a jqwik database of its own under its temp root.

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
`test-homes`, outside every sandbox home, and jk's deletes never follow a link. So `jk self nuke`,
`jk storage nuke`, `jk clean` and the slot reaper remove a sandbox's links and leave the pool alone,
and the reaper's size walk does not count the pool against a slot.

The pool has a reaper of its own, bounded by a cap of 4 GiB: about twice what the integration,
slow and network tiers fetch together (1.9 GiB in about 4,500 units). On CI (`CI=1` or
`CI=true`) the cap is 8 GiB, since one runner serves many branches. Set `JK_TEST_POOL_MAX_MB`
(or `-Djk.test.pool.max.mb`) in the engine's environment to change it.

- **What goes.** Whole units, never single files: an artifact's version directory under
  `repos/<id>`, a tool version under `tools/<slug>`, an Android SDK component (the accepted
  licenses stay). Over the cap, the least recently used units go first, until the pool fits.
- **Last use.** Reads leave no trace jk can rely on, since access times are off on many
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
| Fast / default | `jk test` (suite `test` only; `[test] exclude-tags`) |
| Extra suite | `jk test -s integration` or put e2e under `integration/` |
| Everything | `jk test -A` / `jk build -A` (all suites, config tag excludes cleared) |
| Tag filter | `jk test -E slow` / `--include-tags smoke` |
| One class | `jk test -m <module> -S <fqcn>` (`-p integration` for a tagged one) |
| CI profile | `[profiles.ci] exclude-tags = […]` (overrides `[test]`) + `-p ci` (auto on CI) |

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
