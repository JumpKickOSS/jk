# Memory

Per-class resident memory, and the two acceptance runs from the worker-budget work: petclinic
alone, then four projects at once.

```bash
bench/memory/sample <pid> <out.json> [interval_seconds]
bench/memory/run
bench/memory/run --out "$JK_BENCH_HOME/memory/TABLE.md"
```

`sample` is stdlib Python 3.11. It reads `/proc` until `pid` exits (default every 3s) and prints
peak RSS by class: engine, nested engine (an `EngineMain` whose command contains `/tmp`),
compiler worker, test JVM, other jk worker, Gradle, Maven, other Java, other. The JSON file keeps
every sample.

`run` is one heavy job. Leave the host otherwise idle. It runs `jk test --redo` in
spring-petclinic, then the same command in four projects at once, and writes a markdown table:
wall, exit, the `jk engine status` Workers line at the busiest sample, the workers cgroup
`memory.events` delta (`oom_kill`, `oom`, `high`, `max`), and per-class peak RSS.

The trees are the other harnesses' scratch checkouts, under `$JK_BENCH_HOME` (set it for the
scratch root — the default is `bench_home()` in [`../benchtools.py`](../benchtools.py)):

| project | path |
|---|---|
| spring-petclinic | `$WALL_HOME/work/jk` (`$JK_BENCH_HOME/wall`) |
| TheAlgorithms-Java | `$CORPUS_SCRATCH/TheAlgorithms-Java` (`$JK_BENCH_HOME/maven-corpus`) |
| gs-rest-service | `$AGENT_LOOP_HOME/baselines/jk/gs-rest-service` |
| gs-accessing-data-jpa | `$AGENT_LOOP_HOME/baselines/jk/gs-accessing-data-jpa` |

A missing `jk.toml` refuses the run. Logs and the table land under `$MEMORY_HOME` (default
`$JK_BENCH_HOME/memory`).
