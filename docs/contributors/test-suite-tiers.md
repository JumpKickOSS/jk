# Test suite tiers (one tier per tag)

**Goal:** keep the branch gate under **~5 minutes** and high-signal e2e on demand.

## Commands

One tier per tag. The table is `buildSrc/src/main/kotlin/TestTiers.kt`; the `useJUnitPlatform { }`
filters are generated from it and guard **G23** (`checkNoOrphanTestTags`) re-derives the partition
from the same object, so the two cannot drift.

`./gradlew checkFast` is the canonical branch gate: every subproject `check` task (unit tests plus
module guards) and every root structural guard. It is network-free. `checkAll` adds the integration
tier.

<!-- test-tiers:start -->
| Command | Includes | Excludes | In `checkAll`? |
|---------|----------|----------|----------------|
| `./gradlew test` | untagged | `integration`, `slow`, `network`, `bench` | yes |
| `./gradlew integrationTest` | `integration` | `slow`, `network`, `bench` | yes |
| `./gradlew slowTest` | `slow` | `network`, `bench` | no |
| `./gradlew networkTest` | `network` | `bench` | no |
| `./gradlew benchTest` | `bench` | — | no |
<!-- test-tiers:end -->

Tag new heavy tests at class level:

```java
@Tag("integration") // engine/e2e / Jk.execute / workers
@Tag("slow")        // Android / multi-minute / large downloads
@Tag("network")     // talks to a real remote (Maven Central, a forge)
@Tag("bench")       // microbench only (never PR)
```

**Every tag is run by exactly one task.** G23 verifies the executable partition, and G52 verifies
that the marked table above matches it.

## The curated integration lane (every pull request)

`checkFast` runs no integration class, and the full tier is too slow to gate on, so a wire, spawn,
worker, workspace, install or lock regression could merge with a deterministic integration test —
sitting in the nightly tier — that would have caught it.

`./gradlew curatedIntegrationTest` closes that gap. It runs the classes named in
`curated-integration.txt` at the checkout root, under the same `integrationTest` filters, and runs
on every pull request as its own CI job.

**Curated membership is a duplicate execution policy, not a tag.** Every listed class is
`@Tag("integration")` and still runs in the nightly tier; nothing is reclassified to make the
branch gate cheap, and the lane never replaces `integrationTest`.

The registry has one line per class:

```
module | class | surface | outcomes | why it is merge-critical
```

Each class runs in a fresh JVM (`forkEvery = 1`). A subset puts classes next to each other that
the full tier never does, and a class that runs an engine in-process leaves process-wide state
behind; per-class forks cost about a second each and make the lane's verdict independent of who
else is in the registry.

Six surfaces have to stay covered — `wire`, `spawn`, `workers`, `workspace`, `install`, `lock` —
each with at least one `success` and one `failure` entry. Guard **G63**
(`checkCuratedIntegration`, both builds) rejects an entry that is missing, renamed, untagged,
tagged into a nightly tier, or claims a failure path the class does not show; it also fails when a
surface loses a path, when `ci.yml` stops running the lane, or when the nightly stops running the
full tier.

### Budget and escalation

**8 minutes of wall clock**, enforced as `timeout-minutes` on the CI step that runs the lane. The
step before it compiles the tree (`testClasses`) outside the budget: a cold four-core runner spends
about six minutes on that compile, and a budget that had to absorb it would say nothing about the
lane. Measured at 1m28s, 1m31s and 1m54s over three clean runs (`./gradlew clean` then
`curatedIntegrationTest --no-build-cache`, 20 classes / 122 tests, 24-core Linux box shared with
other builds), so the budget is headroom for a slower runner, not the current cost.

Over budget, the answer is to **drop or split an entry**, never to raise the number: the lane
exists because the full tier is what a branch gate cannot afford, and a lane that grows toward the
full tier has stopped being a lane. Adding a class is fine when it buys a surface a path it does
not have; adding a second class for a path a surface already covers is what the budget is there to
refuse.

### Why `network` is off the merge gate

`checkAll` must not depend on the network. Sonatype enforces a per-IP quota on Maven Central, so a
gate that needs a remote fails for reasons the change did not cause. `networkTest` runs nightly in
`ci-nightly.yml`, where a transient failure costs a re-run instead of a blocked merge.

`bench` is off the gate for the opposite reason: a microbench prints medians and asserts nothing
about deltas, so gating on it would gate on CI noise.

## If a test task execs a tool jk does not build, that tool's version is an input

Gradle's up-to-date check sees a task's declared inputs and nothing else. A test that shells out to
`node`, `git`, `protoc` or `bundletool` therefore has a hole in it: the program that decides the
outcome is invisible, so upgrading it **replays a cached green produced by a different runtime**
(same family as missing worker-jar inputs or missing JS sources on a test task).

Declare it once, in `buildSrc/src/main/kotlin/ExternalTestRuntimes.kt`:

```kotlin
object ExternalTestRuntimes {
    val table: Map<String, List<Pair<String, String?>>> =
        mapOf(
            ":web:test" to listOf("node" to null),
            ":engine:test" to listOf("git" to "JK_GIT"),   // second element: the product's override var
            ":engine:integrationTest" to listOf("git" to "JK_GIT"))
}
```

Adding `protoc` or `bundletool` is a line there, not a new pattern in a module script. What lands on
the task is an `inputs.property` holding the tool's resolved path and version line — coarse enough
that reinstalling the same release does not invalidate a long suite, and precise enough that an
upgrade, a `PATH` change or the tool **disappearing** does. Absence matters: jk's own probes
assume-skip when a tool is missing (`GitCliExtension.detect()`), and a silently halved parity matrix
is exactly the fake-green this rule exists to remove.

The probe forks the tool once and memoises the answer under `build/external-tool-probe/`, keyed on
the binary's absolute path, size and mtime — steady state is two `stat` calls per tool per build,
and the fork happens only on the build after the tool actually changes.

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

- **PR / push (`ci.yml`):** `./gradlew checkFast` (unit tier + every structural guard), the curated
  integration lane in its own job, and the commit-authorship scan. No coverage, no benches.
- **Nightly (`ci-nightly.yml`):** Linux `integrationTest`, `slowTest`, `networkTest`, `benchTest`,
  and `coverageReport -Pjk.coverage`. macOS and Windows run `scripts/ci-product-smoke.sh`.
- Local branch gate: `./gradlew checkFast`, plus `./gradlew curatedIntegrationTest` to run what the
  pull request's boundary lane will run.
- Local pre-merge when you touch wire/engine/CLI: `./gradlew checkAll` (`checkFast` plus
  `integrationTest`). Never `networkTest` or `benchTest` as a merge gate.
- `./gradlew benchTest` runs nightly; it still gates nothing on deltas.
- `./gradlew coverageReport -Pjk.coverage` is the coverage inventory; it is not part of
  `checkFast` or `checkAll`.

## Measuring integration wall time

```bash
./gradlew :cli:integrationTest :engine:integrationTest --profile
# open build/reports/profile/profile-*.html
# or parse:
# find . -path '*/build/test-results/integrationTest/TEST-*.xml' ...
```

(Default task output dir for the custom `integrationTest` task is `build/test-results/integrationTest/`.)

## Pure-jk product parity

Gradle tiers remain how **this monorepo** is bootstrapped. Once dogfooding with `jk test`:

| Intent | Command |
|--------|---------|
| Fast / default | `jk test` (suite `test` only; optional `[test] exclude-tags`) |
| Extra suite | `jk test --suite integration` or put e2e under `integration/` |
| Everything | `jk test --all` / `jk build --all` (all suites, config tag excludes cleared) |
| Tag filter | `jk test --exclude-tags slow` / `--include-tags smoke` |
| CI profile | `[profiles.ci] exclude-tags = []` (overrides `[test]`) + `--profile ci` (auto on CI) |

Prefer **directory suites** for structural separation (Mill-shaped); use **JUnit tags** for
cross-cutting cost filters inside a suite.
