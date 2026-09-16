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
| Clean build | 0.90 s / 1.26 s | 2.04 s / 4.14 s | 2.26 s / 2.60 s |
| Warm rebuild | 0.10 s / 0.10 s | 0.43 s / 0.48 s | 2.40 s / 3.66 s |
| No-op | 0.09 s / 0.36 s | 0.44 s / 1.05 s | 1.84 s / 1.92 s |
| One-file edit | 0.64 s / 1.05 s | 0.52 s / 0.59 s | 2.23 s / 2.30 s |
| Test run | 26.15 s / 35.66 s | 24.45 s / 38.74 s | 23.66 s / 27.31 s |

| Scenario | jk peak RSS | Gradle peak RSS | Maven peak RSS |
|---|---:|---:|---:|
| Clean build | 2,715 MiB | 1,687 MiB | 435 MiB |
| Warm rebuild | 2,426 MiB | 1,700 MiB | 442 MiB |
| No-op | 2,551 MiB | 1,698 MiB | 399 MiB |
| One-file edit | 2,716 MiB | 1,742 MiB | 429 MiB |
| Test run | 7,479 MiB | 2,938 MiB | 1,081 MiB |

Measured 2026-09-16 on AMD Ryzen 9 7900X 12-Core Processor, 24 threads, 30 GB, Linux 7.1.12-200.fc44.x86_64; project spring-projects/spring-petclinic@818c4136e; Gradle 9.5.1 (the repository's wrapper); Maven 3.9.16 via jk mvn; jk 0.13.7; jk tree at e86db43a4; 5 timed runs per cell; loadavg 2.5 at start, engine live jobs 0 (run 2026-09-16T04:32, other gates idle).
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
  engine, including memory it holds from earlier builds of other projects: in the banked run the
  engine alone was 2.4 GiB before the first jk command, after a day of building jk's own tree, so
  the wall rows' jk peaks are that engine plus about 300 MiB of build. Gradle's daemon was fresh
  (1.5 GiB before its first row) because the harness gives it a user home of its own.
- **The test row is where the tools differ most in shape.** jk shards the suite across forked
  test JVMs sized from the host's cores and RAM — on this 24-thread host that is the 7.5 GiB peak —
  where Gradle runs one test JVM and Maven one surefire fork; the walls end up within a few seconds
  of each other because the suite is dominated by Spring context start-up. `jk test -r` also
  redoes the compile it depends on, which Gradle's `test --rerun` and Maven's `test` do not.
- **The engine had been idle.** The banked run started with no live engine job and a load average
  of 2.5; Maven's test row was measured in a second run after a stray 600-byte `jackson-bom`
  POM stub in `~/.m2` (which made Maven drop Thymeleaf's core jar and fail four view tests) was
  quarantined — the other fourteen cells are from the first run.
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
