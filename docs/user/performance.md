# Performance

jk, Gradle and Maven measured on the same public Spring Boot project, on the same machine, doing
the same work. This is the table behind the speed and memory sentences in the
[README](../../README.md) and in [Why JumpKick](why.md); the harness that produces it is
[`bench/wall/measure`](../../bench/wall/README.md), and the entries it banks are the `[[wall.*]]`
section of [`wall-baseline.toml`](../../wall-baseline.toml).

## The table

<!-- wall-table:begin -->
_No banked wall yet: run `bench/wall/measure --bank`._
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
  engine, including memory it holds from earlier builds of other projects.
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
