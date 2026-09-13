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

The rows land in `build/dogfood-wall/row.jsonl` (an `env` object, then one `measurement` per side and
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
