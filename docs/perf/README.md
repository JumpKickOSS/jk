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

After `jk build` / `jk test`, open `out/jk-chrome-profile.json` in Perfetto or
`chrome://tracing`. Disable with `JK_CHROME_PROFILE=off` or `jk build --no-timeline`.

Incremental strategy (Java ABI vs Zinc): [incremental-zinc-decision.md](incremental-zinc-decision.md)
(JK-1046 — stay on ABI; Zinc deferred).

## Warm pool go/no-go (ticket-1030)

**Decision: DEFER** — see [warm-pool-bench.md](warm-pool-bench.md). Do not implement a resident
compiler pool until a worktree prototype beats **AOT-on** forks on wall time *and* RSS.

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
