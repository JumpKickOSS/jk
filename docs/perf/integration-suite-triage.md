# Integration / slow / bench suite triage (JK-1286)

Snapshot: **2026-08-02**. Default `./gradlew test` and self-host exclude these tags
(`integration` | `slow` | `bench`). Run keepers via:

```bash
./gradlew integrationTest          # integration + slow (not bench)
./gradlew :cli:integrationTest
./gradlew :engine:integrationTest
# optional:
./gradlew test -PincludeBench=true # if wired; else includeTags bench
```

## Inventory (112 classes)

| Tag | Count | Cadence |
|-----|------:|---------|
| `integration` | ~95 | CI optional / pre-merge confidence `checkAll` |
| `slow` | ~15 | Nightly; many need Android SDK and/or network |
| `network` | 1 | Nightly with network (`QuarkusLockPerfTest`) |
| `bench` | 1 | Manual / perf machines only |

## Classification

### A — Keepers (expect green offline-ish with installLocal workers)

Most **CLI `@Tag("integration")`** command tests and **engine** wire/plugin tests that use
`@TempDir` + local CAS. Require:

- Worker jars side-loaded (`installLocal` / monorepo layout)
- No Android SDK
- Network only when the test hits Central (many use fixtures)

**Action:** run on `checkAll` / nightly `integrationTest`. Failures here are real regressions.

### B — Network-dependent (skip offline)

| Class | Notes |
|-------|--------|
| `QuarkusLockPerfTest` | `@Tag("network")`, 30s budget, Maven Central; `assumeTrue(networkOk())` |
| Several `slow` Grails/Groovy e2e | Cold BOM resolve; warm CAS helps |
| Android suites under `slow` | Google Maven + SDK |

**Action:** keep; document network requirement; do not fail offline CI on these.

### C — Environment-gated (Android SDK / large stack)

| Class | Needs |
|-------|--------|
| `KspRoomHiltTest`, `HiltTransformTest` | Android SDK, KSP, Google Maven |
| `AndroidSpikeTest`, `AndroidWorkspaceTest`, `AndroidRemoteAarTest`, … | SDK + network |
| `NiaScratchTest`, `RobolectricUnitTest` | Android / robolectric |

**Action:** nightly with SDK image; local opt-in. Prior bug theory (lock omits unresolvable
processor) was **ruled out** — lock fails hard on unresolved deps.

### D — Worker-jar regressions (should be green after JK-1281)

| Class | Historical fail | Status |
|-------|-----------------|--------|
| `GrailsBuildE2eTest` | missing worker jar | Fix shipped JK-1281; re-run on integration |
| `GroovyBuildE2eTest` | missing worker jar | Same |

### E — Bench only

| Class | Notes |
|-------|--------|
| `ForkedJavacAotBenchTest` | Not in `integrationTest` includeTags; manual |

## Cadence recommendation

| When | Command |
|------|---------|
| PR / default | `./gradlew test` (unit only) |
| Merge confidence | `./gradlew checkAll` or `integrationTest` when wire/engine/cli spawn paths change |
| Nightly | full `integrationTest` + optional Android job for `slow` |
| Perf | `bench` tags on dedicated hardware |

## Open follow-ups

1. Confirm Grails/Groovy e2e green after thin-worker packaging on a clean agent.
2. KspRoomHilt: still needs SDK run to explain residual fixture issues (not lock silence).
3. Wire `bench` into an explicit Gradle task if nightly wants it.
