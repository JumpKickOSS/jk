# Performance harness

## Microbench (ticket-1024)

```bash
# jk on PATH (reinstall local first if dogfooding)
./scripts/microbench.sh
# or against a project:
./scripts/microbench.sh /path/to/project
```

Scenarios: clean-all, noop, incr-body (touch one `.java`), aot-off control (`JK_WORKER_AOT=off`).
Reports **median** of `RUNS` (default 3) wall times in ms.

## Chrome timeline (ticket-1023)

After `jk build` / `jk test`, open `target/jk-chrome-profile.json` in Perfetto or
`chrome://tracing` (engine-written; not printed to the terminal). Disable with
`JK_CHROME_PROFILE=off` or `--no-timeline`.

Incremental strategy (Java ABI vs Zinc): [incremental-zinc-decision.md](incremental-zinc-decision.md)
(JK-1046 — stay on ABI; Zinc deferred).

## Warm pool / AOT go-no-go (ticket-1030 / JK-1049)

**Decision: DEFER warm pool**; **keep PluginAot for `java …` workers**. See [warm-pool-bench.md](warm-pool-bench.md).

| Process | AOT | Temurin note |
|---------|-----|----------------|
| Bare `javac` | **off** (no train/map) | was noise vs AOT |
| **`java … java-compiler` PluginMain** | on | **~1.7×** (172 vs 299 ms microbench) |
| **`java … kotlin-compiler` PluginMain** | on | modest on hello-kotlin |

- **Graal host is ineligible** for worker AOT. Engine + workers need HotSpot 25+ (Temurin).

```bash
JDK_SPEC=temurin-25 ./scripts/aot-vs-fork-bench.sh /path/to/project   # bare javac path
./scripts/plugin-worker-aot-bench.sh kotlin /path/to/hello-kotlin
./gradlew :engine:test --tests ForkedJavacAotBenchTest -Dorg.gradle.java.home=$HOME/.sdkman/candidates/java/25.0.3-tem
```

## Engine heap monorepo (JK-1075)

**Decision: keep 256 MiB default** — peak ~36 MiB heap on a 200-module `build --skip-tests`
(~15% of cap). See [engine-heap-monorepo.md](engine-heap-monorepo.md). Re-measure with
`scripts/heap-monorepo-measure.sh`.

## Action-key source hashing (JK-1068)

`ActionKey` routes source (and plugin jar) content through `FileHashMemo`: thread-local walk
cache (one content read per path per thread for key + why-rebuilt snapshot) plus settled
disk memo under `<cache>/hash-memo/`. Action key material unchanged (still path + SHA-256 hex).

## Test parallelization (JK-1086 Phase A → B/C)

**Within-module:** default `-w0` auto + per-module `[test] workers=1` opt-out (B1–B4).  
**Cross-module:** default **on** (C2); opt out with `--serial-tests` / `--no-parallel-tests`.  
See [test-parallelization.md](test-parallelization.md) (Mill map, RAM model, C1 measure ~40% wall win
on `shared/*`). Re-measure: `scripts/test-parallel-measure.sh`.  
**JUnit in-process parallel vs `-w`:** [junit-parallel-vs-jk-workers.md](junit-parallel-vs-jk-workers.md)
(JK-1092 — do not stack).

## Within-module test workers (JK-1087)

**Decision: GO for default auto `-w0` (B3); serial pin via `-w1` or `[test] workers=1`.**  
Spike numbers: [within-module-test-parallel-spike.md](within-module-test-parallel-spike.md) (~2.4× wall at `-w4` on 24×200 ms classes; ~4× RSS).

## Resolve / lock I/O (JK-1088)

See [resolve-io.md](resolve-io.md) — local-first fetch, shared scope caches, progress bar phases.

