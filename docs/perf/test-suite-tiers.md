# Test suite tiers (unit vs integration)

**Goal:** default `./gradlew test` under **~5 minutes**; keep high-signal e2e on demand. (The old
single-tier suite mixed engine/e2e, shadowJar+worker packaging deps, and Android multi-build tests
into the default `test` task with no timeouts.)

## Commands

| Command | Includes | Excludes |
|---------|----------|----------|
| `./gradlew test` | Untagged unit tests | `@Tag("integration")`, `slow`, `bench`, `network` |
| `./gradlew integrationTest` | `integration` + `slow` | `bench` |
| `./gradlew checkAll` | unit + integration | `bench` |

Tag new heavy tests at class level:

```java
@Tag("integration") // engine/e2e / Jk.execute / workers
@Tag("slow")        // Android / multi-minute / large downloads
@Tag("bench")       // microbench only (never PR)
```

## Redundancy / prune candidates (integration tier)

Measured profiling of a full `integrationTest` is expensive; use this as a **manual prune backlog** when editing those areas:

| Cluster | Keep | Consider folding into nightly-only / fewer cases |
|---------|------|--------------------------------------------------|
| Android ladder | Spike smoke + one Hilt/KSP + Release | Drop duplicate full Hilt path from default integration if Spike+Release cover gate |
| Cache e2e | One Java + one Kotlin freshness | Merge overlapping `BuildCacheTest` / Kotlin second-build cases |
| JDK install | One install success path | Share fixtures across Ensure/Update/Command |
| Tool run | One path per language | `ToolRunCommandTest` has many near-identical error/help methods |
| EngineServer / Http | Handshake + auth + one SSE | Deep host-header matrix → keep tagged, trim methods over time |
| Git backends | jgit | CLI-git matrix can stay `slow` or one smoke |
| AOT | — | `ForkedJavacAotBenchTest` is `@Tag("bench")` only |

## CI

- **PR / push (`ci.yml`):** `./gradlew test` (unit tier) + commit-authorship scan  
- **Nightly (`ci-nightly.yml`):** `./gradlew integrationTest` on Linux  
- Local pre-merge when you touch wire/engine/CLI: `./gradlew checkAll` 

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
