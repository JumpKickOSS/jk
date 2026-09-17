# Test suite tiers (one tier per tag)

**Goal:** keep the branch gate under **~5 minutes** and high-signal e2e on demand.

## Commands

One tier per tag. The table is the root manifest's `[test]` and `[profiles.*]` tables; the
engine's `tiers` validation (**G23**) re-derives the partition from them and proves, over every
subset of the tag vocabulary, that each is run by exactly one tier, and **G52** (`test-tier-docs`)
renders the same tables into the block below, so the two cannot drift
([self-host](self-host.md#test-tiers)).

`jk build` runs every module's fast tier; `jk test --profile integration` is the pre-merge bar. The
house rules run under `jk build` and `jk guard` ([self-host](self-host.md#house-rule-gate)).

<!-- test-tiers:start -->
| Command | Includes | Excludes |
|---------|----------|----------|
| `jk test` | untagged | `integration`, `slow`, `network`, `bench` |
| `jk test --profile integration` | `integration` | `slow`, `network`, `bench` |
| `jk test --profile slow` | `slow` | `network`, `bench` |
| `jk test --profile network` | `network` | `bench` |
| `jk test --profile bench` | `bench` | — |
<!-- test-tiers:end -->

Tag new heavy tests at class level:

```java
@Tag("integration") // engine/e2e / Jk.execute / workers
@Tag("slow")        // Android / multi-minute / large downloads
@Tag("network")     // talks to a real remote (Maven Central, a forge)
@Tag("bench")       // microbench only (never PR)
```

**The fast tier is walled off from Maven Central.** The launcher hands every JVM of a launch that
includes no tag and excludes at least one `JK_HTTP_DENY_HOSTS=repo.maven.apache.org,<mirror>`;
`Http` refuses a request to a listed host before it leaves the process, redirects and the Central
failover included, with a `DeniedHostException` that names the host. A unit test that needs Central
is an integration test — tag it. Fixture locks in the fast tier resolve their injected JUnit
Platform from the sandbox store the engine seeds (`TestStoreSeed`), never from the network.

**Every tag is run by exactly one tier.** G23 verifies the executable partition, and G52 verifies
that the marked table above matches it.

## The curated integration lane (every pull request)

The fast tier runs no integration class, and the full profile is too slow to gate on, so a wire,
spawn, worker, workspace, install or lock regression could merge with a deterministic integration
test — sitting in the nightly profile — that would have caught it.

`scripts/curated-integration.sh` closes that gap. It reads `curated-integration.txt` at the
checkout root and runs one `jk test --profile integration -m <module> --class …` per module the
registry names, as the last step of the self-host job on every pull request. jk fails a run in
which no class matched, so a renamed or deleted entry fails the lane instead of shrinking it.

**Curated membership is a duplicate execution policy, not a tag.** Every listed class is
`@Tag("integration")` and still runs in the nightly profile; nothing is reclassified to make the
branch gate cheap, and the lane never replaces `jk test --profile integration`.

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
gate that needs a remote fails for reasons the change did not cause. `jk test --profile network`
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
(`target/<module>/reports/test-results/TEST-*.xml`, `system-out`). To replay one sample, put the seed on the
property: `@Property(seed = "-3119466389416338755")`. A shrunk sample that exposes a solver defect
becomes an example test in `PubGrubShrunkCounterexampleTest` so the fix stays pinned when the
generator moves on.

## Coverage ratchet (nightly)

`jk test --coverage` runs every module's unit tier under the JaCoCo agent and writes
`target/<module>/reports/jacoco.xml`; `jk guard` then evaluates `coverage-band` (G91), a `metric`
floor over `coverage.line` baselined per module in `jk-guards-baseline.toml` with a half-point band:

- more than 0.5 points **below** the entry: red, naming the module, the measured and the recorded
  value — cover the change, or `jk guard freeze coverage-band --reason "…"` and say why;
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
| AOT | — | `ForkedJavacAotBenchTest` is `@Tag("bench")`, so `benchTest` and nothing else |

## CI

- **PR / push (`ci.yml`):** the self-host job (`jk build`, `jk install`, `jk guard`, `jk test`: the
  fast tier and every house-rule lane, run by the checkout's own jk; then the curated integration
  lane and `jk audit --severity HIGH` over the lock), the shell fixtures, the workflow lint and the
  commit-authorship scan. No coverage, no benches.
- **Nightly (`ci-nightly.yml`):** `jk test --profile integration`, `--profile slow`,
  `--profile network`, `--profile bench`, the coverage ratchet (`jk test --coverage`, `jk guard`),
  the heap guard, the doc examples and the audit at `LOW` on Linux; the product smoke on macOS.
- Local gate: `jk format`, `jk guard`, `jk build`, `jk test --profile integration`;
  `scripts/curated-integration.sh` runs what the pull request's boundary lane will run.
- Never the `network` or `bench` profile as a merge gate.

## Measuring integration wall time

```bash
jk test --profile integration
# per-step walls: target/jk-profile.json; per-class times: target/**/reports/test-results/TEST-*.xml
```

## Measuring the Android lock (network tier)

`NiaWarmLockTimingTest` (`server/resolver`) locks a local `nowinandroid` overlay twice — cold process
caches, then hot — and prints the phase walls (`COLD_TOTAL_MS`, `HOT_TOTAL_MS`, the graph and
materialise splits). Its budget asserts apply only when the JVM sees every processor of the host and
the host is idle; `jk test` pins each test JVM to its share of the cores with
`-XX:ActiveProcessorCount`, so under the plain profile the test prints `TIMING_ASSERTS_SKIPPED` with
the reason and the numbers describe a machine no user has. To measure plainly, lift the pin for the
one class and run it alone:

```bash
JK_NIA_OVERLAY=~/src/oss/jk-examples/android/nowinandroid/overlay \
  jk test --profile network -m server/resolver -w 1 \
    --class cc.jumpkick.resolver.NiaWarmLockTimingTest \
    --jvm-arg -XX:ActiveProcessorCount=$(nproc)
# JVM_CPUS=… HOST_CPUS=… LOADAVG=… names the machine the numbers were taken on
```

A number taken while anything else was building is load, not lock cost. The lock's gate is the
scheduled wall measurement ([docs/perf](../perf/README.md)), not this assert.

## Isolation

A forked test JVM never sees the developer's product layout or the engine's. `TestEnv` hands
every suite a sandbox home under `<home>/test-homes/<key>/home` (`TestHomes`: keyed by the
module's real path, outside the project, warm across runs) and pins every root under it:
`JK_HOME`, `JK_STATE_DIR`, `JK_STORE_DIR`, `JK_CACHE_DIR`, `JK_JDKS_DIR`, plus the workspace's
shared `JK_M2_LOCAL` and a temp root under the module's `target/`. The three roots are pinned
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
`repos/central`, and for each artifact a version list naming exactly the versions whose POM came
along, under the key the metadata cache reads for the Central URL. A cold sandbox therefore locks
its test roots without Central, and a Central that throttles this host cannot turn the tier red; a
real index the sandbox fetched itself is left in place. Only those trees: everything else a
fixture needs is exact-pinned and fetched once into the warm sandbox, and a whole-store link would
make every sandbox weigh the whole store to the slot reaper's byte cap.

**Slots live as long as their module.** A sandbox slot's stamp names the module directory it was
handed to; every launch reaps the slots whose module is gone — a removed worktree, a fixture
project a suite made under a temp dir — before the age and size rules apply, so a full run of the
CLI tier leaves no slot per fixture behind. A slot a live launch holds is never reaped.

## Suites and tags

| Intent | Command |
|--------|---------|
| Fast / default | `jk test` (suite `test` only; `[test] exclude-tags`) |
| Extra suite | `jk test --suite integration` or put e2e under `integration/` |
| Everything | `jk test --all` / `jk build --all` (all suites, config tag excludes cleared) |
| Tag filter | `jk test --exclude-tags slow` / `--include-tags smoke` |
| One class | `jk test -m <module> --class <fqcn>` (`--profile integration` for a tagged one) |
| CI profile | `[profiles.ci] exclude-tags = […]` (overrides `[test]`) + `--profile ci` (auto on CI) |

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
