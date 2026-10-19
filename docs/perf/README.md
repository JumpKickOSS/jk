# Performance records and the wall ratchet

Decision records and benches live in KanArtist (`projects/jk/docs/perf/`). This directory keeps the
two records that describe jk's own behaviour ([engine-heap-monorepo.md](engine-heap-monorepo.md),
[junit-parallel-vs-jk-workers.md](junit-parallel-vs-jk-workers.md)) and the contract the progress bar
and ETA follow ([../contributors/progress-contract.md](../contributors/progress-contract.md)).

## The scheduled wall measurement, and its ratchet

`.github/workflows/wall-measure.yml` runs `scripts/dogfood-wall-measure.sh` on a schedule (Mondays,
07:40 UTC; also on demand). It times this tree with jk, three rows by two sides:

| row | what is timed |
|---|---|
| `rebuild` | everything from scratch: `jk build -r` |
| `noop` | a warm build with nothing changed |
| `touched` | a warm build after one production file is edited |

| side | meaning |
|---|---|
| `jk` | `jk build` raw — `[guards] on-build = false`, the published number |
| `jk-guards` | `jk build` as contributors run it, house-rule guards on (the opt-in number, its own series) |

The rows land in `target/dogfood-wall/row.jsonl` (an `env` object, then one `measurement` per side and
row with its timed walls). `scripts/wall-band.py` compares each row's median with
[`wall-baseline.toml`](../../wall-baseline.toml):

- a row more than **15 %** above its banked median fails the run and prints the commit range
  since the line was banked (`commit` in the table), so the regression is bisectable;
- any row more than **5 %** below its median has its line rewritten in the same run — an improvement is
  banked, never a failure; the job cannot commit, so the diff is in the step summary and the
  `dogfood-wall` artifact for a contributor to commit;
- a row the file has never seen is added at its measured median: the first scheduled run seeds the
  file, and the band is tuned from the noise the following runs record.

The guards-off invariant this holds: a project without `jk-guards.toml` pays nothing for jk's own
guards. The `jk` and `jk-guards` rows are separate series so the guards' cost never reads as drift in
the product number.

### Re-baselining after an intentional change

Edit the row's `median-s` (and `date`, `commit`) in `wall-baseline.toml` in the same commit as the
change, and say why in the commit message. The ratchet never raises a jk line by itself. A run that
is red because the runner was slow is re-run from the Actions tab; three consecutive green scheduled
runs are the bar for tightening the band.

### Microbenchmarks

The nightly bench profile (`jk test --profile bench`, `@Tag("bench")`) reports each bench's median through
`cc.jumpkick.testing.BenchBand`. A bench with a `[bench.<name>]` table in `wall-baseline.toml` fails
when its median exceeds the banked `median-ms` by the same 15 % band; one without prints its number
as `unbaselined` so it can be banked from the job log. `./scripts/wall-band.py --selftest` and the
`BenchBand` unit test cover the arithmetic without a runner.

### Fat-jar size

`JarSizeBenchTest` (`server/engine`, tier `bench`) packages the four fixtures in
[`bench/jar-size/`](../../bench/jar-size/README.md) with the installed `jk`, with Gradle Shadow (the
fixtures' own wrappers) and with Maven Shade over the same pinned dependencies, prints the per-tool
table and attributes every
byte of the jk-minus-tool delta to a named cause. Unlike the microbenches it asserts: a jk jar more
than 0.5 % above its line in [`jar-size-baseline.toml`](../../jar-size-baseline.toml), or more than
1 % above Shadow's, fails. Sizes are a pure function of the pinned inputs, so the band is for a jk
version string changing length inside the SBOM, not for noise. The current table and the deflate
decision are in [docs/user/packaging.md](../user/packaging.md#fat-jar-size-against-shadow-and-shade).

```bash
jk test --profile bench -m server/engine --class cc.jumpkick.compile.JarSizeBenchTest
```

## The scoreboard: work counts that load does not move

`bench/scoreboard` measures the loop in counters, not seconds, so it can judge a change on a busy
machine. Every row lives in [`scoreboard.toml`](../../scoreboard.toml): one `[[row]]` per dimension,
subject, project, tool and kind, with its banked `value`, `band`, `target` and where it came from.

```bash
bench/scoreboard --tier fast             # measure, judge, bank (exit 1 on a regression)
bench/scoreboard --no-bank               # judge only
bench/scoreboard --subject 'inner.noop.*'
bench/scoreboard --gaps                  # banked rows over their target, worst first
bench/scoreboard --selftest
```

The first dimension, `counts` (`bench/scoreboard.d/counts.py`), runs the wall harness's
spring-petclinic on a **private engine** (its own `JK_STATE_DIR`, the user's store and caches), so
another session's jobs never land in its counters. For a no-op and a one-file edit it records:

| counter | source |
|---|---|
| `tasks-run`, `tasks-skipped` | `task-finish` statuses from `--output json` |
| `disk-write-bytes` | `/proc/<pid>/io` `write_bytes` of every process carrying the private state dir: file bytes only, native copies included |
| `target-files-touched` | files under `target/` created, deleted or rewritten (inode, size, mtime) |
| `files-written`, `file-write-bytes`, `file-read-bytes` | a JFR recording on the engine (`jdk.FileWrite`, `jdk.FileRead`; `/proc` and `/sys` reads left out) |
| `output-bytes` | what `jk build --no-ansi` printed |

Counting stops at a quiet point: the engine finishes part of a job after its reply (the idle
boundary flushes the hash memo; the journal completes the run record), so each window waits until
the engine's processes have written no file bytes for 1 s. Each row takes five runs after a warm-up,
and its value is the median. A row with more than one run in four outside its band is `unstable`:
printed, never banked or judged. `<scenario>-file-writes.tsv` in the run's results directory lists
every file the engine wrote, biggest first. `<scenario>-target-touched.txt` lists the files under `target/` the
build created, deleted or rewritten.

A jk row above its banked value by more than its band (exact for task and file counts, 2–5 % plus a
small absolute slack for bytes) fails the run and prints the commit range since it was banked; a
row that improves is rewritten. Commit the rewritten file. Files stat'd, files hashed and files
compiled need the engine to count them, and are not rows yet.
