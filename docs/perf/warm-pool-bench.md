# Warm compiler pool vs AOT forks — measure & decision

**Date:** 2026-07-21 (Temurin 25 re-run)  
**Status:** **DEFER** warm pool (no implementation on `main`)  
**Baseline:** JumpKick short-lived **fork** + JEP 514 `.aot` (`PluginAot`) on **HotSpot / Temurin 25**  
**Harness:** `scripts/aot-vs-fork-bench.sh`, forced `jk build --skip-tests --rebuild --jdk temurin-25`

## Important: GraalVM does not participate in worker AOT

`PluginAot.eligible` **rejects** Oracle GraalVM and GraalVM CE:

```text
// HotSpot 25+ records mappable caches; Graal hosts and older JDKs never train.
```

On a Graal **project/toolchain** JDK:

- no javac/kotlinc `.aot` train  
- no `-J-XX:AOTCache=` on the worker  
- `JK_WORKER_AOT=on` vs `off` are **the same cold fork**  

So any “AOT-on vs AOT-off” numbers collected while the **compiler JDK** was Graal (common when Graal is `active` for native-image / dogfood) are **not** measuring AOT. They only measure pipeline noise.

**Default clean-host story:** `project.jdk = 25` resolves to **Eclipse Temurin** (or similar HotSpot) via `jk jdk` — that is the right arm for AOT and for warm-pool go/no-go.

## What we are comparing

| Arm | Meaning | On `main`? |
|-----|---------|------------|
| **A — Cold fork + AOT map** | Each compile starts a new `javac` JVM; maps `~/.jk/state/aot/javac-*.aot` when trained | yes (production) |
| **B — Cold fork, AOT off** | Same fork model; `JK_WORKER_AOT=off` — pure cold start every time | yes (control) |
| **C — Warm / resident pool** | Mill-style long-lived compiler JVM(s) already JIT-warm | **no** — not prototyped |

**Warm pool (C) must beat A (Temurin + AOT), not only B.**

There is no “warm process” arm without a prototype. **A** is “cold process start, AOT-accelerated”; **B** is “cold process start, no AOT.”

## 2026-07-21 — Temurin 25.0.3 (`25.0.3-tem`)

**Fixture:** `jk-examples/examples/spring-boot-hello`  
**Command:** `jk build --skip-tests --rebuild --jdk temurin-25` (n=7, median of CLI “took”)  
**Compiler process (ps):**  
`/…/25.0.3-tem/bin/javac … -J-XX:AOTCache=…/javac-9ae6aacc8f2c4999.aot` when AOT on.

| Arm | Median wall | Raw (ms) |
|-----|-------------:|----------|
| **A — Temurin + AOT map** | **496 ms** | 473–528 |
| **B — Temurin, AOT off** | **485 ms** | 478–500 |

**Delta: AOT ≈ flat / slightly slower (~2%) on this fixture — modest at best; no clear win.**

Training: first Temurin compile after deleting `javac-*.aot` produced a new cache within ~2s (background trainer).

### Earlier invalid Graal runs (for the record)

When the toolchain was Graal, AOT-on vs AOT-off medians were ~612 vs ~621 ms — both pure cold forks; treat as **void**.

## Decision: **DEFER warm pool** (reaffirmed)

| Finding | Implication |
|---------|-------------|
| AOT only applies on HotSpot 25+ (Temurin, Corretto, …) | Always measure with `--jdk temurin-25` (or host default HotSpot), never Graal-as-compiler |
| On Temurin spring-boot-hello rebuild, AOT-on ≉ faster than AOT-off | Room for a **warm pool** might still exist (live JIT + no process spawn), but AOT is not buying much here |
| Arm C unprototyped | No GO without wall **and** peak RSS vs A |

**Do not implement a warm pool on `main` yet.**

### When to reopen

Worktree prototype of arm **C** on **Temurin 25**, measure against **A**:

1. Forced rebuild / multi-module dirty wall  
2. Peak RSS (engine + forks vs engine + pool)  
3. C beats **A**, not only **B**

## How to re-run

```bash
# Prefer Temurin as project/default JDK
jk jdk default temurin-25
jk jdk pin temurin-25 -C /path/to/project

# Train then measure (after caches exist under ~/.jk/state/aot/javac-*)
RUNS=7 ./scripts/aot-vs-fork-bench.sh /path/to/project
# or forced:
for i in 1..7; do jk build --skip-tests --rebuild --jdk temurin-25; done  # AOT on
JK_WORKER_AOT=off  # same with AOT off
```

Confirm mapping with a mid-build `ps` line containing both `…/temurin…/bin/javac` and `-J-XX:AOTCache=`.

## Related

- `PluginAot.eligible` — Graal excluded by design  
- [mill-comparison.md](../mill-comparison.md) §5b  
- JK-1049 (warm pool implementation, gated), ticket-1030  
