# Performance

jk, Gradle and Maven measured on the same public Spring Boot project, on the same machine, doing
the same work. This is the table behind the speed and memory sentences in the
[README](../../README.md) and in [Why JumpKick](why.md); the harness that produces it is
[`bench/wall/measure`](../../bench/wall/README.md), and its rows are banked in
[`scoreboard.toml`](../../scoreboard.toml) by the scoreboard's night tier.

<!-- wall-claims:begin -->
On [spring-petclinic](#the-table), jk is faster than Gradle on every row: clean build (0.37 s against 2.69 s), warm rebuild (0.09 s against 0.57 s), no-op (0.05 s against 0.53 s), one-file edit (0.13 s against 0.61 s) and test run (8.54 s against 9.97 s). It is faster than Maven on every row: clean build (0.37 s against 3.16 s), warm rebuild (0.09 s against 3.16 s), no-op (0.05 s against 2.37 s), one-file edit (0.13 s against 3.14 s) and test run (8.54 s against 10.10 s).

Between builds jk keeps 863 MiB resident: 171 MiB for the engine, whose heap is capped at 256 MiB and trimmed back after every job, and the rest for warm compilers that exit after 30 idle minutes or as soon as the host runs short of memory. One engine serves every project on the machine. Gradle keeps 1,734 MiB, 2.0× as much: its daemon, which idles for three hours by default, and its workers. Maven keeps nothing resident and starts a fresh JVM for every build instead (2.37 s for a no-op). Peak whole-tree RSS during a run is lower than Gradle's on clean build, warm rebuild, no-op and one-file edit and higher on test run; it is higher than Maven's on every row.
<!-- wall-claims:end -->

## The table

<!-- wall-table:begin -->
| Scenario | jk median / p90 | Gradle median / p90 | Maven median / p90 |
|---|---:|---:|---:|
| Clean build | 0.37 s / 3.37 s | 2.69 s / 10.02 s | 3.16 s / 3.23 s |
| Warm rebuild | 0.09 s / 0.12 s | 0.57 s / 0.60 s | 3.16 s / 3.20 s |
| No-op | 0.05 s / 0.05 s | 0.53 s / 0.56 s | 2.37 s / 2.42 s |
| One-file edit | 0.13 s / 0.21 s | 0.61 s / 0.65 s | 3.14 s / 3.23 s |
| Test run | 8.54 s / 8.84 s | 9.97 s / 18.27 s | 10.10 s / 11.42 s |

| Scenario | jk peak RSS | Gradle peak RSS | Maven peak RSS |
|---|---:|---:|---:|
| Clean build | 593 MiB | 1,464 MiB | 546 MiB |
| Warm rebuild | 544 MiB | 1,521 MiB | 540 MiB |
| No-op | 573 MiB | 1,526 MiB | 452 MiB |
| One-file edit | 620 MiB | 1,611 MiB | 535 MiB |
| Test run | 1,993 MiB | 1,847 MiB | 1,189 MiB |
| Idle, a minute after the test run | 863 MiB | 1,734 MiB | 0 MiB |

Measured on 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 12 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL (host `c9ab742f7b18`); project spring-projects/spring-petclinic; Gradle 9.8.2; Maven 3.10.0; jk 0.41.1; 5 timed runs per cell. Banked 2026-10-26 at jk tree 1880fb554. Conditions: jk loadavg 1.4 at start, engine live jobs ?; Gradle loadavg 1.4 at start, engine live jobs ?; Maven loadavg 1.4 at start, engine live jobs ?.
<!-- wall-table:end -->

Walls are wall-clock seconds, median and p90 over the timed runs. Peak RSS is the highest sum of
resident memory across the tool's whole process tree during the run, sampled every 200 ms: for jk
the client, the resident engine and every compiler and test worker; for Gradle the wrapper client,
the daemon and its workers; for Maven the launcher JVM and its forks. The idle row runs no
command: a minute after the test run it sums the same residents' RSS once a second and keeps the
median, so it is what each tool leaves on the machine between builds.

## What each row is

| Row | jk | Gradle | Maven |
|---|---|---|---|
| Clean build | `jk clean`, then `jk build -r -S`: outputs gone, action cache bypassed | `build/`, configuration cache and build cache removed, then `classes jar` | `target/` removed, then `package` |
| Warm rebuild | `jk clean`, then `jk build -S`: the action cache restores | `build/` removed, then `classes jar`: the build cache restores | `target/` removed, then `package`: Maven has no cache and recompiles |
| No-op | `jk build -S` | `classes jar` | `package` |
| One-file edit | one line appended to a leaf controller | the same edit | the same edit |
| Test run | `jk test -r` | `test --rerun` | `test` |
| Idle | the engine and its warm workers, a minute after the test run | the daemon and its workers, likewise | nothing: Maven keeps no process between builds |

The wipe before a run is never timed. Every Gradle command carries `--configuration-cache
--build-cache`; every Maven command carries the properties that switch off checkstyle, Spring
Javaformat, the enforcer, the SBOM, Boot repackaging and JaCoCo, and Gradle's task selection leaves
out the same plugins plus Boot AOT — so all three tools compile the main sources, copy resources
and write the plain jar, and no more. The exact commands are in the
[harness README](../../bench/wall/README.md#same-work-three-tools).

## Reading it honestly

- **Warm is not one thing.** jk's warm rebuild restores outputs from its content-keyed action
  cache; Gradle's restores from its build cache; Maven recompiles. The rows say which.
- **The host is quiet.** The scoreboard waits for a quiet host and calibrates it before and after;
  the banked `load` field records the load average at the start and the end of the run, and a row
  taken on a busy or drifting host is never banked.
- **Peak RSS is a ceiling.** Summing RSS over several JVMs counts shared pages once per JVM; the
  column is an upper bound, the same upper bound for every tool. jk's includes the whole resident
  engine and its warm workers. The engine
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
- **Every cell is the latest run.** jk's rows ratchet in `scoreboard.toml` — a regression past the
  band fails the night run — but the table publishes each tool's latest measurement, never jk's
  best-ever against the others' today. The engine is a private one started fresh at the default
  heap, whatever the measuring host's `~/.jk/config.toml` says.
- **The sentences are generated.** The paragraph above the table is written by
  `bench/scoreboard --render` from the same rows, so it cannot say faster where the table says
  slower; no other page may compare speed or memory by hand.
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
