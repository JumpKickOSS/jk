# Performance

jk, Gradle and Maven measured on the same public Spring Boot project, on the same machine, doing
the same work. This is the table behind the speed and memory sentences in the
[README](../../README.md) and in [Why JumpKick](why.md); the harness that produces it is
[`bench/wall/measure`](../../bench/wall/README.md), and the entries it banks are the `[[wall.*]]`
section of [`wall-baseline.toml`](../../wall-baseline.toml).

## The table

<!-- wall-table:begin -->
| Scenario | jk median / p90 | Gradle median / p90 | Maven median / p90 |
|---|---:|---:|---:|
| Clean build | 1.29 s / 4.30 s | 2.75 s / 10.09 s | 3.21 s / 4.50 s |
| Warm rebuild | 0.21 s / 0.26 s | 0.60 s / 0.61 s | 3.18 s / 3.24 s |
| No-op | 0.19 s / 0.20 s | 0.52 s / 0.54 s | 2.42 s / 2.45 s |
| One-file edit | 0.93 s / 1.01 s | 0.62 s / 0.67 s | 3.15 s / 3.20 s |
| Test run | 28.33 s / 28.79 s | 30.64 s / 38.06 s | 29.60 s / 31.64 s |

| Scenario | jk peak RSS | Gradle peak RSS | Maven peak RSS |
|---|---:|---:|---:|
| Clean build | 502 MiB | 1,307 MiB | 439 MiB |
| Warm rebuild | 406 MiB | 1,364 MiB | 446 MiB |
| No-op | 313 MiB | 1,363 MiB | 374 MiB |
| One-file edit | 594 MiB | 1,497 MiB | 423 MiB |
| Test run | 1,487 MiB | 1,703 MiB | 1,201 MiB |

Measured 2026-09-28 on 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL (host `1a8c211a203d`); project spring-projects/spring-petclinic@818c4136e; Gradle 9.8.0; Maven 3.9.16; jk 0.14.0; jk tree at c3cd4139a; 5 timed runs per cell; loadavg 1.1 at start, engine live jobs ?.
<!-- wall-table:end -->

Walls are wall-clock seconds, median and p90 over the timed runs. Peak RSS is the highest sum of
resident memory across the tool's whole process tree during the run, sampled every 200 ms: for jk
the client, the resident engine and every compiler and test worker; for Gradle the wrapper client,
the daemon and its workers; for Maven the launcher JVM and its forks.

## What each row is

| Row | jk | Gradle | Maven |
|---|---|---|---|
| Clean build | `jk clean`, then `jk build -r --skip-tests`: outputs gone, action cache bypassed | `build/`, configuration cache and build cache removed, then `classes jar` | `target/` removed, then `package` |
| Warm rebuild | `jk clean`, then `jk build --skip-tests`: the action cache restores | `build/` removed, then `classes jar`: the build cache restores | `target/` removed, then `package`: Maven has no cache and recompiles |
| No-op | `jk build --skip-tests` | `classes jar` | `package` |
| One-file edit | one line appended to a leaf controller | the same edit | the same edit |
| Test run | `jk test -r` | `test --rerun` | `test` |

The wipe before a run is never timed. Every Gradle command carries `--configuration-cache
--build-cache`; every Maven command carries the properties that switch off checkstyle, Spring
Javaformat, the enforcer, the SBOM, Boot repackaging and JaCoCo, and Gradle's task selection leaves
out the same plugins plus Boot AOT — so all three tools compile the main sources, copy resources
and write the plain jar, and no more. The exact commands are in the
[harness README](../../bench/wall/README.md#same-work-three-tools).

## Reading it honestly

- **Warm is not one thing.** jk's warm rebuild restores outputs from its content-keyed action
  cache; Gradle's restores from its build cache; Maven recompiles. The rows say which.
- **The engine is shared.** jk's engine is the developer's resident engine. The harness waits for
  it to have no live job before it starts, and the banked `load` field records the load average
  at the start and the end of the run; a number measured under load says so.
- **Peak RSS is a ceiling.** Summing RSS over several JVMs counts shared pages once per JVM; the
  column is an upper bound, the same upper bound for every tool. jk's includes the whole resident
  engine, including whatever it still holds from earlier builds of other projects. The engine
  returns to a floor after a job — heap uncommitted, native heap trimmed, again after thirty idle
  seconds ([engine memory](engine.md#memory-after-a-build)) — so the resident RSS the harness
  records at the start of a row (`resident_rss_mb`) is that floor plus what the last job left in
  flight, not a day's accumulation. Gradle's daemon is the harness's own (a private user home),
  counted from its working directory, so another checkout's daemon is not on the bill.
- **The test row is where the tools differ most in shape.** jk shards the suite across forked
  test JVMs only as wide as the measured class times make useful (two here, where one Spring
  context class dominates), where Gradle runs one test JVM and Maven one surefire fork; the walls end up within a few seconds
  of each other because the suite is dominated by Spring context start-up. `jk test -r` also
  redoes the compile it depends on, which Gradle's `test --rerun` and Maven's `test` do not.
- **The engine had been idle.** The banked run started with the engine stopped and a load
  average of 1.1. Gradle's cells were re-measured in a second run, banked with the first run's jk
  and Maven cells.
- **One project, one module.** spring-petclinic is 25 main classes. A multi-module project moves
  every ratio, and this page claims nothing about one.
- **Dependencies are warm for everyone.** The jk store, Gradle's dependency cache and `~/.m2` all
  hold the project's dependencies before the timed runs; no row measures a download.

## Rerun it

```bash
bench/wall/measure --bank      # every tool and scenario, five runs each; rewrites the table above
bench/wall/measure --tool jk --scenario edit --runs 3
```

The first run clones the project and provisions a working copy per tool under `~/src/scratch/wall`
(`$WALL_HOME`); later runs only measure.
