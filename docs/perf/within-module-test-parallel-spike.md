# Within-module JVM test parallelism spike (JK-1087)

**Date:** 2026-07-20 · **Updated 2026-07-22: default auto `-w0` (B3)**

Companion: [test-parallelization.md](test-parallelization.md) (JK-1086 Phase A).

## Mill vs jk (single module)

| Mill stage | Mill behavior | jk today |
|------------|---------------|----------|
| Serial | One process, classes sequential | **`-w1`** — `JUnitLauncher.runSingle` |
| Module sharding | Parallel across modules | `-j` + optional `--parallel-tests` |
| Static class groups | Fixed groups → N processes | Not productized (user would hand-split) |
| Dynamic sharding | Pool of `NUM` processes pull classes | **`-w0` auto** or **`-w N`** — discover + pull-queue |
| Auto process count | `min(jobs, #classes)` | **`TestWorkers.auto`** + `HeapPlan` clamp |
| Biased dynamic | Prefer first process per module | Cross-module scheduler concern → **not** 1087 |

**Conclusion:** Within-module path matches Mill dynamic sharding; default is now **auto** (B3), not forced serial.

## Spike method

Synthetic traditional project, **24** JUnit Jupiter classes each sleeping **200 ms** (+ scaffold test → 25 tests).

```bash
jk test -w N --rebuild --no-progress
```

Host: macOS (agent laptop), local `jk` 0.10.1.

## Wall time

| `-w` | Wall (jk report) | Speedup vs `-w1` |
|------|------------------|------------------|
| 1 | **5.8 s** | 1.0× |
| 2 | **3.6 s** | 1.6× |
| 4 | **2.4 s** | 2.4× |
| 8 | **2.1 s** | 2.8× |

Ideal wall for 24 × 200 ms pure sleep ≈ 4.8 s serial; with overhead (discover fork, JVM start, scaffold) ~5.8 s. Parallel scales until JVM spawn / discovery dominate (~8 workers little better than 4).

## RSS (rough peak of test-runner-like processes)

| `-w` | Peak RSS (sample) | Notes |
|------|-------------------|--------|
| 1 | ~**142 MiB** | One forked runner |
| 4 | ~**564 MiB** | ~4× workers — HeapPlan still clamps multi-module peaks |

## Decisions

### GO (shipped)

1. **`-w N`** — Mill-class within-module parallel (pull-queue).
2. **Default `-w0` (auto)** — `min(jobs, classCount)` + heap clamp (B3); use **`-w1`** for serial.
3. **Per-worker temp** when W>1 (B1).
4. **Guide recipes** (B2).

### NO-GO (for now)

1. ~~**Do not default-on `--parallel-tests`**~~ — **C2 shipped** (default on; `--serial-tests` opt-out).
2. **Do not implement Mill biased multi-module scheduling here** — workspace scheduler owns that.
3. **No second flag** for class parallel — `-w` is enough.

### Follow-up (implementation, not blocking 1087)

When someone wants production polish after opt-in usage:

- Per-worker `java.io.tmpdir` (and optional port ranges) when `workers > 1`
- Failure lines always include class FQCN + worker id (partially present)
- Optional microbench scenario: `scripts/microbench.sh` with multi-class sleep fixture

No separate “implement pull-queue” ticket — **already there** (`JUnitLauncher.runParallel`).

## Refs

- `server/engine/.../test/JUnitLauncher.java` — `runSingle` / `runParallel`
- Mill: https://mill-build.org/blog/11-jvm-test-parallelism.html
- JK-1086: [test-parallelization.md](test-parallelization.md)
