# The wall harness

jk, Gradle and Maven on one pinned public Spring Boot project: wall clock and peak memory for a
clean build, a warm rebuild, a no-op, a one-file edit and a test run. The numbers it banks are the
table in [docs/user/performance.md](../../docs/user/performance.md) and the footnote in
[docs/user/why.md](../../docs/user/why.md); the banked entries live in the `[[wall.*]]` section of
[`wall-baseline.toml`](../../wall-baseline.toml).

```bash
bench/wall/measure                          # every tool, every scenario, five timed runs each
bench/wall/measure --tool gradle --scenario edit --runs 3
bench/wall/measure --bank                   # measure, bank into wall-baseline.toml, render the docs tables
bench/wall/measure --render                 # docs tables from the banked entries; nothing measured
```

Clones, working copies, Gradle's private user home and build cache, and the per-run logs live under
`$WALL_HOME` (default `~/src/scratch/wall`), never in this tree. The first run provisions; every
later run measures.

## The project

[spring-projects/spring-petclinic](https://github.com/spring-projects/spring-petclinic) at
`818c4136ea971c21674525f9053de0d9c7ad8cfe`: one module, 25 main classes and 24 test classes, a
`pom.xml` and a `build.gradle` maintained by the same authors, Spring Boot 4.1 on Java 17. It is
the one public Spring Boot project that ships both build files and is green under jk after
`jk import pom.xml` with two additions, which is why it is the subject rather than a larger
multi-module repository that only one of the three tools builds. Its jk manifest and lock are
committed in [`spring-petclinic/`](spring-petclinic/):

- `[javac] args = ["-parameters"]` — Boot's parent POM sets it for `maven-compiler-plugin` and the
  Gradle plugin sets it on `compileJava`; `jk import` does not carry it and half the MVC tests
  need it.
- `[application] main = …` — the POM says so through `spring-boot-maven-plugin`; without the table
  jk treats the module as a library and writes sources and javadoc jars on every build.

One test class leaves every tool's copy: `PostgresIntegrationTests` starts Postgres through
`docker compose config --format=json`, which podman-compose cannot answer, so on a host without
Docker proper it is red under jk, Gradle and Maven alike. Everything else runs.

## Same work, three tools

Each wall row compiles the main sources, copies resources and writes the plain jar. The
repository's own Gradle and Maven builds also run checkstyle, Spring Javaformat, an SBOM, Boot AOT
processing and Boot repackaging; those are switched off so the row measures the build tool and not
plugins jk has no counterpart for.

| Tool | Build command | Test command |
|---|---|---|
| jk 0.13.7 | `jk build --skip-tests` (`-r` in the clean row) | `jk test -r` |
| Gradle 9.5.1, the repository's wrapper | `./gradlew classes jar --configuration-cache --build-cache -I bench/wall/wall.init.gradle` | `./gradlew test --rerun` with the same flags |
| Maven 3.9.16 through `jk mvn` | `jk mvn -q -o -Dmaven.test.skip -Dcheckstyle.skip -Dspring-javaformat.skip -Denforcer.skip -Dmaven.gitcommitid.skip -Dcyclonedx.skip -Dspring-boot.repackage.skip -Djacoco.skip package` | the same properties minus `maven.test.skip`, goal `test` |

Gradle runs with the configuration cache and the build cache on, in a user home of the harness's
own (`$WALL_HOME/gradle-home`) so its daemon is the only daemon on the memory bill; the init script
points the local build cache at `$WALL_HOME/gradle-build-cache` so the clean row can empty it.
Maven has no daemon here (mvnd is not used) and no output cache. jk's engine is the developer's
resident engine, shared with everything else the machine builds with jk.

## Scenarios

Tests are skipped in every wall row; the suite is its own row. The wipe before a run is never
timed.

| Row | Before the timed command | What the row measures |
|---|---|---|
| clean | outputs deleted and the tool's output cache emptied for this project: `jk clean` then `jk build -r`; Gradle's `build/`, configuration cache and build cache removed; Maven's `target/` removed | the first build on a warm machine: dependencies fetched, engine or daemon up |
| rebuild | outputs deleted, the tool's cache kept: `jk clean`; Gradle's `build/` removed; Maven's `target/` removed | jk restoring from its action cache, Gradle from its build cache, Maven recompiling |
| noop | nothing changed | the repeated cycle |
| edit | one line appended to `system/WelcomeController.java`, a class nothing else imports; restored after each run | the inner loop. jk keys its cache on content, so a `touch` would measure a no-op |
| test | nothing changed; the run is forced (`jk test -r`, `gradle test --rerun`, Maven always runs) | the whole suite: 72 tests, Testcontainers MySQL included when Docker or podman answers |

Each cell is `--runs` timed runs (default five) after one untimed priming run where the row needs
a built tree; the table shows the median and the p90 (nearest rank).

## Memory

Every timed run samples `/proc` every 200 ms and sums the RSS of the tool's process tree: the
command's descendants plus, for jk, the resident engine and its workers, and for Gradle the
harness's daemon and its workers. The row's number is the peak of that sum. RSS counts pages shared
between JVMs once per JVM, so the column is an upper bound, the same upper bound for every tool.
Each row also records the resident RSS at the start (`resident_rss_mb` in `rows.jsonl`): for jk
that is the engine before the command, which on a developer machine carries whatever it built
earlier.

## Reading the numbers honestly

- The engine is shared. `measure` waits (`--wait-idle`, default 900 s) until `jk engine status`
  reports no live job before it starts, and records the load average at start and end in the
  banked `load` field. A number banked under load says so.
- Warm means different things per tool and the rows say which: the rebuild row is jk's action
  cache against Gradle's build cache against Maven's recompile.
- One module. A multi-module project would move every ratio; this harness answers the question
  for the project it names and no other.
- Every banked entry names the date, the host, the tool versions, the jk tree's commit and the
  exact command, so a reader can rerun it with one command and compare.
