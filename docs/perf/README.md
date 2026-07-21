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

## Warm pool go/no-go (ticket-1030 / JK-1049)

**Decision: DEFER** (reaffirmed 2026-07-21) — see [warm-pool-bench.md](warm-pool-bench.md).  
AOT-on vs AOT-off on dogfood rebuilds is **~noise** (~612 vs ~621 ms median on spring-boot-hello
`--rebuild`). Do not implement a resident compiler pool until a worktree prototype beats
**AOT-on** forks on wall time *and* RSS.

```bash
./scripts/aot-vs-fork-bench.sh /path/to/project   # includes --rebuild arms
```

## Engine heap monorepo (JK-1075)

**Decision: keep 256 MiB default** — peak ~36 MiB heap on a 200-module `build --skip-tests`
(~15% of cap). See [engine-heap-monorepo.md](engine-heap-monorepo.md). Re-measure with
`scripts/heap-monorepo-measure.sh`.

## Action-key source hashing (JK-1068)

`ActionKey` routes source (and plugin jar) content through `FileHashMemo`: thread-local walk
cache (one content read per path per thread for key + why-rebuilt snapshot) plus settled
disk memo under `<cache>/hash-memo/`. Action key material unchanged (still path + SHA-256 hex).

## Test parallelization (JK-1086 Phase A)

**Decision: keep cross-module tests opt-in; within-module Mill path → JK-1087.**  
See [test-parallelization.md](test-parallelization.md) for Mill vs jk map, RAM model (`-j`×`-w`×`parallel-tests`), isolation checklist, and default-on go/no-go.

## Within-module test workers (JK-1087)

**Decision: GO for opt-in `-w N` (already dynamic class pull-queue); NO-GO for default raise.**  
Spike numbers: [within-module-test-parallel-spike.md](within-module-test-parallel-spike.md) (~2.4× wall at `-w4` on 24×200 ms classes; ~4× RSS).

## Resolve / lock I/O (JK-1088)

See [resolve-io.md](resolve-io.md) — local-first fetch, shared scope caches, progress bar phases.

