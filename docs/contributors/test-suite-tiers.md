# Test suite tiers (one tier per tag)

**Goal:** default `./gradlew test` under **~5 minutes**; keep high-signal e2e on demand. (The old
single-tier suite mixed engine/e2e, shadowJar+worker packaging deps, and Android multi-build tests
into the default `test` task with no timeouts.)

## Commands

One tier per tag. The table is `buildSrc/src/main/kotlin/TestTiers.kt`; the `useJUnitPlatform { }`
filters are generated from it and guard **G23** (`checkNoOrphanTestTags`) re-derives the partition
from the same object, so the two cannot drift.

| Command | Includes | Excludes | In `checkAll`? |
|---------|----------|----------|----------------|
| `./gradlew test` | Untagged unit tests | `integration`, `slow`, `network`, `bench` | yes |
| `./gradlew integrationTest` | `integration` + `slow` | `network`, `bench` | yes |
| `./gradlew networkTest` | `network` | `bench` | **no** — nightly only |
| `./gradlew benchTest` | `bench` | — | **no** — on demand |

Tag new heavy tests at class level:

```java
@Tag("integration") // engine/e2e / Jk.execute / workers
@Tag("slow")        // Android / multi-minute / large downloads
@Tag("network")     // talks to a real remote (Maven Central, a forge)
@Tag("bench")       // microbench only (never PR)
```

**Every tag is run by exactly one task, and G23 fails the build if that stops being true.** It was
not true before JK-2447: `test` excluded four tags and `integrationTest` re-included only two, so
`@Tag("bench")` was run by nothing at all and `ForkedJavacAotBenchTest` had not executed in any gate
since it was written. Adding a tag to `TestTiers.slowTags` without giving it a tier now fails the
build on the same commit, before any test carries it.

### Why `network` is off the merge gate

`checkAll` must not depend on the network. Sonatype enforces a per-IP quota on Maven Central and
this repo has a documented history of 429s from it (JK-1277); a gate that needs a remote fails for
reasons the change did not cause, and a red gate nobody believes is worse than no gate. Until
JK-2447 the one `@Tag("network")` class reached `checkAll` anyway, because it also carried
`@Tag("slow")` — the gate was network-dependent by accident rather than by decision. `networkTest`
runs nightly in `ci-nightly.yml`, where a 429 costs a re-run instead of a blocked merge.

`bench` is off the gate for the opposite reason: a microbench prints medians and asserts nothing
about deltas, so gating on it would gate on CI noise.

## If a test task execs a tool jk does not build, that tool's version is an input

Gradle's up-to-date check sees a task's declared inputs and nothing else. A test that shells out to
`node`, `git`, `protoc` or `bundletool` therefore has a hole in it: the program that decides the
outcome is invisible, so upgrading it **replays a cached green produced by a different runtime**
(JK-2465 — same family as JK-2461, where the eight worker jars were not inputs, and JK-2441, where
the JS sources were not).

Declare it once, in the table at the top of `buildSrc/src/main/kotlin/jk.java-conventions.gradle.kts`:

```kotlin
val externalTestRuntimes: Map<String, List<Pair<String, String?>>> = mapOf(
        ":web:test" to listOf("node" to null),
        ":engine:test" to listOf("git" to "JK_GIT"),   // second element: the product's override var
        ":engine:integrationTest" to listOf("git" to "JK_GIT"))
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

**JK-2192 (2026-08-19): this backlog was worked and largely disproved at assertion
level** — the Cache-e2e, JDK-install, and Android-ladder rows below looked like
duplicates but cover different layers/assertions; see the ticket for the per-item
disproofs before re-adding anything here.

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

- **PR / push (`ci.yml`):** `./gradlew test` (unit tier) + commit-authorship scan  
- **Nightly (`ci-nightly.yml`):** `./gradlew integrationTest` **and** `./gradlew networkTest` on Linux  
- Local pre-merge when you touch wire/engine/CLI: `./gradlew checkAll` — every module's `check`
  (unit tier + every guard) plus `integrationTest`. Never `networkTest` or `benchTest`.
- `./gradlew benchTest` is manual; it gates nothing.

## Measuring integration wall time

```bash
./gradlew :cli:integrationTest :engine:integrationTest --profile
# open build/reports/profile/profile-*.html
# or parse:
# find . -path '*/build/test-results/integrationTest/TEST-*.xml' ...
```

(Default task output dir for the custom `integrationTest` task is `build/test-results/integrationTest/`.)

## Pure-jk product parity (JK-1134–1138)

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
