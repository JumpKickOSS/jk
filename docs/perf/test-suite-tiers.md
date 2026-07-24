# Test suite tiers (unit vs integration)

**Goal:** default `./gradlew test` under **~5 minutes**; keep high-signal e2e on demand. (The old
single-tier suite mixed engine/e2e, shadowJar+worker packaging deps, and Android multi-build tests
into the default `test` task with no timeouts.)

## Commands

| Command | Includes | Excludes |
|---------|----------|----------|
| `./gradlew test` | Untagged unit tests | `@Tag("integration")`, `slow`, `bench` |
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

- **PR / push unit:** `./gradlew test`  
- **Integration:** on `main` always; on PR when `clients/cli`, `server/engine`, plugins, wire, etc. change (`dorny/paths-filter`)  
- **OS nightly:** unit + integration for core modules  

## Measuring integration wall time

```bash
./gradlew :cli:integrationTest :engine:integrationTest --profile
# open build/reports/profile/profile-*.html
# or parse:
# find . -path '*/build/test-results/integrationTest/TEST-*.xml' ...
```

(Default task output dir for the custom `integrationTest` task is `build/test-results/integrationTest/`.)
