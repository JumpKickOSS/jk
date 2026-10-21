# Performance

jk, Gradle and Maven measured on the same public Spring Boot project, on the same machine, doing
the same work. This is the table behind the speed and memory sentences in the
[README](../../README.md) and in [Why JumpKick](why.md); the harness that produces it is
[`bench/wall/measure`](../../bench/wall/README.md), and its rows are banked in
[`scoreboard.toml`](../../scoreboard.toml) by the scoreboard's night tier.

## The table

<!-- wall-table:begin -->
| Scenario | jk median / p90 | Gradle median / p90 | Maven median / p90 |
|---|---:|---:|---:|
| Clean build | 0.39 s / 4.06 s | 2.81 s / 10.83 s | 3.36 s / 3.44 s |
| Warm rebuild | 0.10 s / 0.10 s | 0.59 s / 0.61 s | 3.31 s / 3.38 s |
| No-op | 0.05 s / 0.33 s | 0.55 s / 0.57 s | 2.51 s / 2.72 s |
| One-file edit | 0.14 s / 0.29 s | 0.65 s / 0.67 s | 3.32 s / 3.39 s |
| Test run | 28.33 s / 28.79 s | 36.38 s / 45.76 s | 30.39 s / 31.03 s |

| Scenario | jk peak RSS | Gradle peak RSS | Maven peak RSS |
|---|---:|---:|---:|
| Clean build | 1,050 MiB | 1,652 MiB | 569 MiB |
| Warm rebuild | 536 MiB | 1,411 MiB | 564 MiB |
| No-op | 549 MiB | 1,414 MiB | 451 MiB |
| One-file edit | 630 MiB | 1,525 MiB | 574 MiB |
| Test run | 1,898 MiB | 1,767 MiB | 1,257 MiB |

Measured on 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL (host `1a8c211a203d`); project spring-projects/spring-petclinic; Gradle 9.8.2; Maven 3.10.0; jk 0.14.0; jk 0.15.3; 5 timed runs per cell. Banked 2026-09-28 at jk tree 21a8fc0ac (jk); 2026-10-20 at jk tree 0f4bf5ea2 (Maven); 2026-10-20 at jk tree 4fd583742 (jk); 2026-10-20 at jk tree 92335d1c6 (Gradle); 2026-10-20 at jk tree fc56a8bc4 (jk). Conditions: jk loadavg 1.7 at start, engine live jobs 0; Gradle loadavg 1.7 at start, engine live jobs ?; Maven loadavg 1.0 at start, engine live jobs ?.
<!-- wall-table:end -->

Walls are wall-clock seconds, median and p90 over the timed runs. Peak RSS is the highest sum of
resident memory across the tool's whole process tree during the run, sampled every 200 ms: for jk
the client, the resident engine and every compiler and test worker; for Gradle the wrapper client,
the daemon and its workers; for Maven the launcher JVM and its forks.

## What each row is

| Row | jk | Gradle | Maven |
|---|---|---|---|
| Clean build | `jk clean`, then `jk build -r -S`: outputs gone, action cache bypassed | `build/`, configuration cache and build cache removed, then `classes jar` | `target/` removed, then `package` |
| Warm rebuild | `jk clean`, then `jk build -S`: the action cache restores | `build/` removed, then `classes jar`: the build cache restores | `target/` removed, then `package`: Maven has no cache and recompiles |
| No-op | `jk build -S` | `classes jar` | `package` |
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
