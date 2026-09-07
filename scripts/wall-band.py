#!/usr/bin/env python3
"""The wall-clock ratchet behind the scheduled dogfood measurement.

Reads the measurement's row.jsonl (one `measurement` object per side and row, `walls_s` a list of
timed runs) and wall-baseline.toml (one `[<row>.<side>]` table per subject with `median-s`, `date`,
`runs`, `commit`), then:

  * a jk subject whose median is more than BAND above its recorded median FAILS (exit 1) and prints
    the commit range since the line was banked, so the regression is bisectable;
  * a subject more than IMPROVE below its line has the line rewritten in this run (an improvement
    is banked, never a failure);
  * a subject the file has never seen is added at its measured median — the first scheduled run
    seeds the file;
  * Gradle subjects are recorded and rewritten the same way but never fail: they are the comparison,
    not the product.

Usage: wall-band.py ROW_JSONL BASELINE_TOML [--band 0.15] [--improve 0.05] [--commit SHA] [--selftest]
"""
import json
import re
import statistics
import subprocess
import sys
from datetime import date
from pathlib import Path

BAND = 0.15
IMPROVE = 0.05
HEADER = """# wall-baseline.toml — the ratchet behind the scheduled dogfood wall measurement
# (.github/workflows/wall-measure.yml, scripts/wall-band.py).
#
# One table per subject, `[<row>.<side>]`: rows are rebuild / noop / touched, sides are gradle /
# jk / jk-guards (jk with its house-rule guards on, the opt-in number). Each holds the banked
# `median-s`, the `date` it was banked, the `runs` the median came from and the `commit` measured.
#
# A jk subject more than 15 % ABOVE its median fails the scheduled run and prints the commit range
# since the line was banked. Any subject more than 5 % BELOW its median has the line rewritten in
# the same run — an improvement is banked, never a failure; commit the rewritten file. A subject
# this file has never seen is added at its measured median, so the first run seeds it. Gradle rows
# are the comparison and never fail. Re-baseline after an intentional change by editing the line
# and saying why in the commit; the ratchet never raises a jk line by itself.
"""


def parse_baseline(text):
    tables = {}
    current = None
    for raw in text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        m = re.fullmatch(r"\[([a-z-]+)\.([a-z-]+)]", line)
        if m:
            current = (m.group(1), m.group(2))
            tables[current] = {}
            continue
        m = re.fullmatch(r"([a-z-]+)\s*=\s*(.+)", line)
        if not m or current is None:
            raise SystemExit(f"wall-baseline.toml: cannot read `{raw}`")
        key, value = m.group(1), m.group(2).strip()
        tables[current][key] = value.strip('"') if value.startswith('"') else float(value) if "." in value else int(value)
    return tables


def render(tables):
    out = [HEADER.rstrip("\n"), ""]
    for (row, side) in sorted(tables):
        t = tables[(row, side)]
        out.append(f"[{row}.{side}]")
        out.append(f"median-s = {float(t['median-s']):.2f}")
        out.append(f"date = \"{t['date']}\"")
        out.append(f"runs = {int(t['runs'])}")
        out.append(f"commit = \"{t.get('commit', '')}\"")
        out.append("")
    return "\n".join(out)


def measurements(jsonl_text):
    out = {}
    for line in jsonl_text.splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        if row.get("type") != "measurement":
            continue
        walls = [w for w in row.get("walls_s", []) if w is not None]
        if not walls:
            continue
        # The first timed run warms the daemon / engine when there is more than one.
        timed = walls[1:] if len(walls) > 1 else walls
        out[(row["row"], row["side"])] = (statistics.median(timed), len(timed))
    return out


def judge(baseline, measured, band=BAND, improve=IMPROVE, today=None, commit=""):
    today = today or date.today().isoformat()
    next_tables = {k: dict(v) for k, v in baseline.items()}
    failures, banked = [], []
    for key in sorted(measured):
        median, runs = measured[key]
        row, side = key
        recorded = baseline.get(key)
        if recorded is None:
            next_tables[key] = {"median-s": median, "date": today, "runs": runs, "commit": commit}
            banked.append(f"{row}.{side}: new, {median:.2f} s")
            continue
        base = float(recorded["median-s"])
        if side != "gradle" and median > base * (1 + band):
            since = recorded.get("commit", "")
            failures.append(f"{row}.{side}: {median:.2f} s, banked {base:.2f} s on {recorded.get('date', '?')} "
                            f"(band {band:.0%}); commits since: {since or '?'}..HEAD")
        elif median < base * (1 - improve):
            next_tables[key] = {"median-s": median, "date": today, "runs": runs, "commit": commit}
            banked.append(f"{row}.{side}: {base:.2f} -> {median:.2f} s")
    return next_tables, failures, banked


def selftest():
    base = parse_baseline(render({("noop", "jk"): {"median-s": 2.00, "date": "2026-09-07", "runs": 1, "commit": "abc"},
                                  ("noop", "gradle"): {"median-s": 9.0, "date": "2026-09-07", "runs": 1, "commit": "abc"}}))
    rows = "\n".join(json.dumps(r) for r in [
        {"type": "env", "os": "test"},
        {"type": "measurement", "row": "noop", "side": "jk", "walls_s": [3.1, 2.50]},        # +0.5 s on 2.0 s: red
        {"type": "measurement", "row": "noop", "side": "gradle", "walls_s": [20.0, 20.0]},   # gradle never fails
        {"type": "measurement", "row": "touched", "side": "jk", "walls_s": [5.0, 4.0]},      # unseen: seeded
    ])
    nxt, fails, banked = judge(base, measurements(rows), today="2026-09-08", commit="def")
    assert len(fails) == 1 and fails[0].startswith("noop.jk: 2.50 s, banked 2.00 s"), fails
    assert "commits since: abc..HEAD" in fails[0], fails
    assert nxt[("noop", "gradle")]["median-s"] == 9.0, "a slower gradle row is neither failed nor banked upward"
    assert nxt[("touched", "jk")]["median-s"] == 4.0 and nxt[("touched", "jk")]["commit"] == "def"
    assert nxt[("noop", "jk")]["median-s"] == 2.00, "a regression does not move the line"
    # an improvement past 5 % is banked; within 5 % holds
    nxt2, fails2, banked2 = judge(base, {("noop", "jk"): (1.80, 1), ("noop", "gradle"): (8.8, 1)}, today="x", commit="g")
    assert not fails2 and nxt2[("noop", "jk")]["median-s"] == 1.80 and nxt2[("noop", "gradle")]["median-s"] == 9.0, (banked2, nxt2)
    # the rendered file reads back identically
    assert parse_baseline(render(nxt2)) == parse_baseline(render(parse_baseline(render(nxt2))))
    print("wall-band selftest ok")


def main(argv):
    if "--selftest" in argv:
        selftest()
        return 0
    opts, args, i = {}, [], 0
    while i < len(argv):
        if argv[i].startswith("--") and i + 1 < len(argv):
            opts[argv[i]] = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    if len(args) != 2:
        print(__doc__)
        return 2
    rows_path, baseline_path = Path(args[0]), Path(args[1])
    band = float(opts.get("--band", BAND))
    improve = float(opts.get("--improve", IMPROVE))
    commit = opts.get("--commit") or subprocess.run(
        ["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    if not rows_path.is_file():
        print(f"wall-band: no measurement at {rows_path} — nothing to judge")
        return 1
    baseline = parse_baseline(baseline_path.read_text()) if baseline_path.is_file() else {}
    measured = measurements(rows_path.read_text())
    if not measured:
        print("wall-band: the measurement has no timed rows — nothing to judge")
        return 1
    nxt, failures, banked = judge(baseline, measured, band, improve, commit=commit)
    if banked or not baseline_path.is_file():
        baseline_path.write_text(render(nxt))
        print("wall-baseline.toml rewritten: " + ("; ".join(banked) or "seeded") + " — commit it")
    for m in sorted(measured):
        print(f"  {m[0]}.{m[1]}: {measured[m][0]:.2f} s (n={measured[m][1]})")
    if failures:
        print("wall-band: a jk subject regressed past its band —")
        for f in failures:
            print("  " + f)
        return 1
    print("wall-band: every jk subject within its band")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
