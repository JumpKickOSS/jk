# Netty monorepo compile benchmark (JK-1175)

## Scope

Head-to-head **main-source compile** for the full Netty multi-module tree
(`netty-4.1.115.Final`), matching Mill’s published methodology (compile focus,
not multi-hour unit suites).

| Tree | Location |
|------|----------|
| JumpKick port | [jk-examples](https://github.com/jkbuild/jk-examples) `jvm/netty/checkout` |
| Mill workspace | same pin under `jvm/netty/mill-workspace` (gitignored; from `scripts/prepare-mill-netty.sh`) |
| Mill source | `../mill` (com-lihaoyi/mill), `example/thirdparty/netty/build.mill` |

## Fairness

| Dimension | Choice |
|-----------|--------|
| Pin | **same** git tag + SHA |
| Surface | **main compile only** — `jk build --skip-tests` vs `mill _.compile` (not `__.compile`, which also builds tests) |
| Cold | wipe project outputs every run; **jk uses `--redo`** so CAS cannot restore classfiles; Mill wipes `out/` |
| Warm | leave outputs; no-op rebuild |
| Dirty | touch one `common` source; rebuild that module / dependents as the tool decides |
| Dep caches | warm (jk store / coursier) unless noted |
| Workers | document `-j 1` vs default parallel separately |

Shared JDK major preferred; this machine’s Mill launcher pulled **Azul 25.0.2** while PATH java is **25.0.3** (Graal/Oracle) — note on every row.

## How to re-run

```bash
# mill clone (once)
git clone https://github.com/com-lihaoyi/mill.git ../mill   # next to jk or set MILL_REPO

cd /path/to/jk-examples/jvm/netty
./setup.sh
MILL_REPO=/path/to/mill ./scripts/prepare-mill-netty.sh

# serial (fair headline)
RUNS=3 PARALLEL=1 ./scripts/bench-netty.sh both | tee /tmp/netty-bench.log

# parallel (tool defaults)
RUNS=3 PARALLEL= ./scripts/bench-netty.sh both
```

## Results

### 2026-08-02 — BocaBox (serial `-j 1`)

| Field | Value |
|-------|--------|
| Host | BocaBox · Linux 7.1.5-201.fc44.x86_64 · 24 threads |
| PATH java | 25.0.3 LTS (Oracle GraalVM) |
| jk | **0.10.1** |
| mill | **1.2.0-RC1** (from monorepo at `2496e61765` / `1.2.0-RC1-48-g2496e61765`) |
| Netty | `netty-4.1.115.Final` @ `04f9b4a827` |
| Runs | 3 · median reported |

| Scenario | JumpKick median | Mill median | runs (jk) | runs (mill) |
|----------|----------------:|------------:|-----------|-------------|
| **Cold** full main recompile | **16 853 ms** | **25 347 ms** | 17398 16853 16719 | 25347 31639 25183 |
| **Warm** no-op | **404 ms** | **440 ms** | 404 406 400 | 615 440 414 |
| **Dirty** one `common` source | **411 ms** | **124 ms** | 409 411 415 | 136 85 124 |

Commands:

- jk cold: `rm -rf target && jk -j 1 build --skip-tests --redo`
- mill cold: `rm -rf out && ./mill -j 1 _.compile`
- jk warm / dirty: `jk -j 1 build --skip-tests` (dirty: touch + `-m common`)
- mill warm / dirty: `./mill -j 1 _.compile` / `common.compile`

### Interpretation (this machine)

- **Cold full tree:** JumpKick ~**1.5×** faster than Mill at `-j 1` (true recompile; CAS not restoring outputs).
- **Warm no-op:** roughly tied (~0.4 s).
- **Dirty single module:** Mill ~**3×** faster (tighter incremental for one module).

These numbers are **not** a full-test-suite comparison. Unit-test head-to-heads need a shared include/exclude list (see `jvm/netty/PARITY.md`).

### 2026-08-03 — BocaBox, after install-optimize / engine self-heal AOT (JK-1392)

Re-run after the install optimize path landed (worker AOT pre-train + host calibration via
engine self-heal, JK-1385..1400): fresh `state/aot` worker caches were cleared, the engine
self-healed `java-compiler-*`/`kotlinc-*` caches on start, then the same bench as above.

| Field | Value |
|-------|--------|
| Host | BocaBox · Linux 7.1.5-201.fc44.x86_64 · 24 threads |
| Engine java | openjdk 25.0.4 (Temurin, jk-managed) |
| jk | **0.10.1** (native client, tree @ a0474dbe) |
| mill | **1.2.0-RC1** (same workspace pin as 2026-08-02) |
| Netty | `netty-4.1.115.Final` @ `04f9b4a827` |
| Runs | 3 · median reported · serial `-j 1` |

| Scenario | JumpKick median | Mill median | runs (jk) | runs (mill) |
|----------|----------------:|------------:|-----------|-------------|
| **Cold** full main recompile | **16 462 ms** | **24 791 ms** | 19562 16462 16090 | 24422 24832 24791 |
| **Warm** no-op | **209 ms** | **454 ms** | 209 211 201 | 655 454 413 |
| **Dirty** one `common` source | **201 ms** | **122 ms** | 199 201 204 | 137 81 122 |

Deltas vs 2026-08-02 baseline:

- **Warm no-op: 404 → 209 ms (−48%)** — jk now clearly ahead of Mill's 454 ms.
- **Dirty one file: 411 → 201 ms (−51%)** — the gap to Mill narrowed from ~3× to ~1.6×.
- **Cold: ~unchanged** (16.9 → 16.5 s; still ~1.5× faster than Mill).

**Did AOT map on dirty Java?** No — and by design. Netty's dirty path is the plain
subprocess-javac backend (`JavaIncrementalCompile`): no source-generating annotation
processors, so the ToolProvider worker (the thing that maps `java-compiler-*.aot`) is not
forked. The bare `javac` launcher deliberately neither trains nor maps AOT (measured: no
win — see `PluginAot` javadoc). The warm/dirty improvement comes from the engine-side work
in the same batch (engine AOT cache on start, idle-boundary GC discipline, journal/metrics
paths), not from worker AOT on this benchmark's hot path.

**Remaining gap vs Mill on dirty (~80 ms):** Mill's resident Zinc keeps a warm compiler JVM;
jk re-forks bare `javac` per dirty build. **Decision (JK-1416, 2026-08-03): do not close
this with a warm javac worker pool** — the latency is not worth the extra RSS. See
[warm-javac-pool-decision.md](warm-javac-pool-decision.md).

## Earlier / other arms

| Date | Tool | Notes |
|------|------|-------|
| (echo sample) | `scripts/netty-echo-bench.sh` | published Netty jars only — not monorepo sources |
