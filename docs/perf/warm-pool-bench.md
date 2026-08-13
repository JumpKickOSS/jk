# Warm compiler pool vs AOT forks — measure & decision

**Date:** 2026-07-21 (Temurin 25 re-run)  
**Status:** **DEFER** warm pool (no implementation on `main`)  
**Baseline:** JumpKick short-lived **fork** + JEP 514 `.aot` (`PluginAot`) on **HotSpot / Temurin 25**  
**Harness:** `scripts/aot-vs-fork-bench.sh`, forced `jk build --skip-tests --redo --jdk temurin-25`

## Important: GraalVM does not participate in worker AOT

`PluginAot.eligible` **rejects** Oracle GraalVM and GraalVM CE:

```text
// HotSpot 25+ records mappable caches; Graal hosts and older JDKs never train.
```

On a Graal **project/toolchain** JDK:

- no javac/kotlinc `.aot` train  
- no `-J-XX:AOTCache=` on the worker  
- `JK_WORKER_AOT=on` vs `off` are **the same cold fork**  

So any “AOT-on vs AOT-off” numbers collected while the **compiler JDK** was Graal (common when Graal is `active` for native-image / dogfood) are **not** measuring AOT. They only measure plan noise.

**Default clean-host story:** `project.jdk = 25` resolves to **Eclipse Temurin** (or similar HotSpot) via `jk jdk` — that is the right arm for AOT and for warm-pool go/no-go.

## What we are comparing

| Arm | Meaning | On `main`? |
|-----|---------|------------|
| **A — Cold `java` worker + AOT map** | PluginMain forks map `kotlinc-*.aot` / `java-compiler-*.aot` when trained | yes (production) |
| **B — Cold `java` worker, AOT off** | Same forks; `JK_WORKER_AOT=off` | yes (control) |
| **Bare `javac`** | No AOT (by design) | yes |
| **C — Warm / resident pool** | Mill-style long-lived compiler JVM(s) already JIT-warm | **no** — not prototyped |

**Warm pool (C) must beat A (Temurin plugin workers + AOT), not only B.**

## Process shapes (what AOT can attach to)

| Path | Process | AOT today | Typical when |
|------|---------|-----------|----------------|
| Bare **`javac`** | `javac …` (no AOT flags) | **none** — not trained or mapped | Default Java compile (no source-gen AP) |
| **`java … PluginMain`** java-compiler | ToolProvider/javac *inside* worker | yes (`javaCompilerFlags`) | After project proves source-generating APs |
| **`java … PluginMain`** kotlin-compiler | Kotlin Build Tools API | yes (`kotlincFlags`) | All Kotlin compiles |
| Engine itself | `java -cp jk-engine.jar` | separate engine `.aot` | Always |

**Policy:** AOT only for **`java … PluginMain`** workers (kotlin-compiler + java-compiler +
formatter). Bare `javac` never trains or maps `.aot` (measured as noise on Temurin).

## 2026-07-21 — Temurin 25.0.3 results

### A. Bare `javac` (historical — AOT removed)

Temurin spring-boot-hello `--redo` had AOT-on ~496 vs AOT-off ~485 ms (**noise**). Bare-`javac`
AOT training/mapping was **removed**; default Java compiles no longer touch `javac-*.aot`.

### B. `java … jk-java-compiler` PluginMain (microbench)

`ForkedJavacAotBenchTest` on **Temurin** host (Gradle `-Dorg.gradle.java.home=…/25.0.3-tem`), n=7 after train:

| Arm | Median worker wall |
|-----|-------------------:|
| AOT-on | **172 ms** |
| AOT-off | **299 ms** |

**~1.7× faster with AOT** (~42% less wall). Samples: on 130–243; off 247–418.  
stderr: `AOT cache ready for java-compiler worker (TEMURIN 25.0.3)`.

Same microbench on **Graal** host: ~252 vs ~249 ms (void — ineligible).

### C. `java … jk-kotlin-compiler` PluginMain (hello-kotlin)

Chrome `compile-kotlin` duration, 3× `--redo --jdk temurin-25` after train:

| Arm | compile-kotlin (approx) |
|-----|------------------------:|
| AOT-on | **486–497 ms** |
| AOT-off | **495–509 ms** |

**Modest / noise** on this tiny project (full plan ~500–600 ms). Larger Kotlin modules may show more; re-run with a fatter corpus if needed.

### Earlier invalid Graal bare-javac runs

~612 vs ~621 ms — both pure cold forks; **void**.

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

# Train then measure (after caches exist under ~/.local/state/jk/aot/javac-*)
RUNS=7 ./scripts/aot-vs-fork-bench.sh /path/to/project
# or forced:
for i in 1..7; do jk build --skip-tests --redo --jdk temurin-25; done  # AOT on
JK_WORKER_AOT=off  # same with AOT off
```

Confirm mapping with a mid-build `ps` line containing both `…/temurin…/bin/javac` and `-J-XX:AOTCache=`.

## Related

- `PluginAot.eligible` — Graal excluded by design  
- [mill-comparison.md](../mill-comparison.md) §5b  
- JK-1049 (warm pool implementation, gated), ticket-1030  
