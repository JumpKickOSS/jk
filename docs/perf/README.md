# Performance records and the scoreboard

Decision records and benches live in KanArtist (`projects/jk/docs/perf/`). This directory keeps the
two records that describe jk's own behaviour ([engine-heap-monorepo.md](engine-heap-monorepo.md),
[junit-parallel-vs-jk-workers.md](junit-parallel-vs-jk-workers.md)) and the contract the progress bar
and ETA follow ([../contributors/progress-contract.md](../contributors/progress-contract.md)).

## The scoreboard

`bench/scoreboard` is the one ratchet over jk's loop. Every row lives in
[`scoreboard.toml`](../../scoreboard.toml): one `[[row]]` per dimension, subject, project, tool and
kind, with its banked `value`, `band`, `target`, where it came from (`runs`, `date`, `commit`,
`host-id`) and the conditions it was taken under. Only rows of the measuring host are compared.

```bash
bench/scoreboard --tier fast             # count and paired-wall rows: measure, judge, bank (exit 1 on a regression)
bench/scoreboard --tier night            # the fast tier, then every absolute wall behind the quiet gate
bench/scoreboard --no-bank               # judge only
bench/scoreboard --dim paired --baseline ~/jk-0.15.2/bin/jk
bench/scoreboard --subject 'inner.noop.*'
bench/scoreboard --gaps                  # banked rows over their target, worst first
bench/scoreboard --render                # docs tables from the banked rows
bench/scoreboard --selftest
```

Three kinds of row:

| kind | what it holds | when it runs |
|---|---|---|
| `count` | work counters load does not move: tasks run, bytes and files written, files under `target/` touched, output size | any time |
| `paired-wall` | the candidate jk against a baseline jk, interleaved A B A B; the median of the pair ratios with a bootstrap 95 % CI | any time |
| `absolute-wall` | published seconds and peak RSS: jk, Gradle and Maven on petclinic (`wall`), jk on its own tree (`dogfood`) | night, quiet host only |

A jk `count` or `absolute-wall` row above its banked value by more than its band (exact for task
and file counts, 2–5 % plus a small absolute slack for bytes, 15 % for seconds) fails the run and
prints the commit range since it was banked; a row that improves is rewritten, and a row the file
has never seen is seeded. A `paired-wall` row fails when its CI's lower bound is above 1 + band
(10 %): the candidate is slower beyond doubt. Its value is today's ratio, rewritten every run.
Gradle and Maven rows record what those tools do today and never fail. Commit the rewritten file;
the ratchet never raises a jk row by itself, so raise one by hand after an intentional change and
say why in the commit.

### Counts

The `counts` dimension (`bench/scoreboard.d/counts.py`) runs the wall harness's spring-petclinic on a
**private engine** (its own `JK_STATE_DIR`, the user's store and caches), so another session's jobs
never land in its counters. For a no-op and a one-file edit it records:

| counter | source |
|---|---|
| `tasks-run`, `tasks-skipped` | `task-finish` statuses from `-O jsonl` |
| `disk-write-bytes` | `/proc/<pid>/io` `write_bytes` of every process carrying the private state dir: file bytes only, native copies included |
| `target-files-touched` | files under `target/` created, deleted or rewritten (inode, size, mtime) |
| `files-written`, `file-write-bytes`, `file-read-bytes` | a JFR recording on the engine (`jdk.FileWrite`, `jdk.FileRead`; `/proc` and `/sys` reads left out) |
| `output-bytes` | what `jk build -u` printed |

Counting stops at a quiet point: the engine finishes part of a job after its reply (the idle
boundary flushes the hash memo; the journal completes the run record), so each window waits until
the engine's processes have written no file bytes for 1 s. Each row takes five runs after a warm-up,
and its value is the median. A row with more than one run in four outside its band is `unstable`:
printed, never banked or judged. `<scenario>-file-writes.tsv` in the run's results directory lists
every file the engine wrote, biggest first. `<scenario>-target-touched.txt` lists the files under
`target/` the build created, deleted or rewritten. Files stat'd, files hashed and files compiled
need the engine to count them, and are not rows yet.

### Paired walls

The `paired` dimension times a no-op and a one-file edit with the candidate (`$JK_BIN`, else the
installed jk) and a baseline (`--baseline`, `$SCOREBOARD_BASELINE`, else the installed jk, which
makes an unpaired run an A/A check). Each side has its own private engine and working copy; each
of the 20 pairs runs the two sides back to back in a random order, after three warm-up rounds.
Load slows both sides of a pair alike, so the median pair ratio holds on a busy host where an
absolute wall would not. Each row also carries both medians and minimums in milliseconds and the
ratio of the medians.

To judge a branch, build it and point `JK_BIN` at its client against the installed jk; to judge
the installed jk against a release, install that release into a home of its own and pass its
client as the baseline. A side installed in its own home runs from that home, sharing the user's
store and cache:

```bash
jk build -S && JK_BIN=target/dist/jk bench/scoreboard --dim paired
JK_HOME="$JK_BENCH_HOME/release-0.15.2" JK_VERSION=0.15.2 bash install.sh
bench/scoreboard --dim paired --baseline "$JK_BENCH_HOME/release-0.15.2/bin/jk"
```

### Absolute walls and the night

`--tier night` takes `$JK_BENCH_HOME/heavy.lock` (`flock`) for its whole run, so another heavy job
that takes the same lock waits instead of landing in the numbers, and runs the load-proof kinds
first. Before each absolute-wall dimension it waits for a **quiet host**: 1-minute load below 1.0,
CPU PSI `some` avg60 below 0.5 %, and no live job on the user's engine. It looks every 5 minutes
until the deadline (`--deadline`, default 06:30); a host still busy then records `skipped: busy`
and banks nothing for that dimension. Each batch is **calibrated**: a fixed javac batch of
generated sources, timed before and after. Drift above 10 %, or a host slower than its banked
calibration, marks every row `noisy`: recorded, never banked or judged. The load at the end of a
batch is the batch's own builds, so interference during it shows as calibration drift instead. A clean batch banks the calibration as its own row.

After the measurements, the commands in [`bench/night-queue`](../../bench/night-queue) run one after
another (an integration tier, a corpus re-run), never beside the measurements. The run then banks,
renders the `wall` rows into three kinds of generated block — the tables (`wall-table`, in
`docs/user/performance.md` and `why.md`), the comparison sentences (`wall-claims`, in the README,
`why.md`, `comparison.md` and `performance.md`) and the website's proof strip (`wall-strip`, in
`hosting/public/index.html`) — each from every tool's latest measurement, never jk's ratcheted best;
every number names its commit and conditions. Speed and memory comparisons appear nowhere else
(guard `speed-claims-are-generated`). It then writes the morning report
to `$JK_BENCH_HOME/scoreboard/night/<date>.md`: regressions with commit ranges, banked and recorded
rows, noisy and skipped rows, queue results and the top five gaps.

On a Linux host, a systemd user timer fires it:

```ini
# ~/.config/systemd/user/jk-scoreboard.service
[Service]
Type=oneshot
WorkingDirectory=%h/src/oss/jk
ExecStart=%h/src/oss/jk/bench/scoreboard --tier night

# ~/.config/systemd/user/jk-scoreboard.timer
[Timer]
OnCalendar=*-*-* 01:30
Persistent=false
[Install]
WantedBy=timers.target
```

Enable it with `systemctl --user enable --now jk-scoreboard.timer` (and `loginctl enable-linger`
so it fires with no session open). Under WSL, a Windows Task Scheduler entry starts it without a
WSL shell:

```powershell
schtasks /Create /TN "jk scoreboard night" /SC DAILY /ST 01:30 /TR ^
  "wsl.exe -d Ubuntu-26.04 -e bash -lc 'cd ~/src/oss/jk && bench/scoreboard --tier night'"
```

## Microbenchmarks

The nightly bench profile (`jk test -p bench`, `@Tag("bench")`) reports each bench's median through
`cc.jumpkick.testing.BenchBand`. A bench with a `dim = "bench"` row in `scoreboard.toml` (subject the
bench's name, value in `ms`) fails when its median exceeds the banked value by 15 %; one without
prints its number as `unbaselined` so it can be banked from the job log. The `BenchBand` unit test
covers the arithmetic.

## Fat-jar size

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
jk test -p bench -m server/engine -S cc.jumpkick.compile.JarSizeBenchTest
```
