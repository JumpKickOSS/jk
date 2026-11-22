"""Big suites: JumpKick against a large repository's own Maven or Gradle build (bench/suite/measure).

Night and release tiers, behind the quiet gate. The night measures the inner loop; the release
tier adds the CI replay and the morning build, which take hours. Subjects, per fixture:

  suite.edit             seconds from a one-file edit to a test verdict (median; p90 beside it)
  suite.ci.pipeline      minutes per replayed commit (median; p90, failed commits, runner minutes
                         per 100 commits, steps skipped by kind and the cache hit rate beside it)
  suite.morning          seconds for a fresh checkout of a commit CI built to reach its verdict

Tools: `jk` and the fixture's build tool for the edit; `jk-cold` (an empty action cache, as a fresh
runner has), `jk-shared` (the shared cache in $SUITE_CACHE_REMOTE) and the build tool for the
replay and the morning build. $SUITE_COMMITS sets the replay's length (default 200);
$SUITE_USD_PER_RUNNER_MINUTE, when set, prices the runner minutes. $SUITE_OUT names
the harness's results directory: a replay cut short there resumes, its finished commits kept, and
its rows are recorded `noisy` because those commits ran before this batch's quiet gate. jk rows ratchet; the build
tool's rows record what it does today.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import benchtools  # noqa: E402

TIERS = ("night", "release")
KIND = "absolute-wall"
MEASURE = Path(__file__).resolve().parent.parent / "suite" / "measure"
BAND = 0.15
NAMES = {"maven": "Maven", "gradle": "Gradle"}
SUBJECTS = {"edit": "suite.edit", "replay": "suite.ci.pipeline", "morning": "suite.morning"}


def arms(ctx) -> list[str]:
    wanted = ["edit"] + (["replay", "morning"] if ctx.tier == "release" else [])
    return [a for a in wanted if ctx.wants(SUBJECTS[a])]


def as_row(r: dict, started: float) -> dict | None:
    """One harness row as a scoreboard row; None when the arm produced no number.

    A replay resumed from $SUITE_OUT holds commits run before this batch's quiet gate and
    calibration: it is recorded `noisy`, never banked.
    """
    version = r["jk_version"] if r["tool"].startswith("jk") else f"{NAMES[r['build']]} {r[r['build'] + '_version']}"
    common = {"dim": "suite", "subject": SUBJECTS[r["arm"]], "project": r["fixture"], "tool": r["tool"], "kind": KIND,
              "band": BAND, "tool-version": version, "host": benchtools.host_summary(),
              "fixture-commit": r["commit"], "conditions": f"load {r['load_start']} at start, {r['load_end']} at end"}
    if r["arm"] == "replay":
        if "median_min" not in r:
            return None
        common["conditions"] = f"load at most {r['load_max']:.2f} as each commit started"
        row = {**common, "value": r["median_min"], "unit": "min", "runs": r["commits"], "p90-min": r["p90_min"],
               "commits": r["commits"], "failed": r["failed"], "runner-min-per-100": r["runner_min_per_100"]}
        if "usd_per_100" in r:
            row["usd-per-100"] = r["usd_per_100"]
        if r.get("first_at", 0) < started:
            row["noisy"] = "commits replayed before this batch's quiet gate (resumed from $SUITE_OUT)"
        if "hit_rate" in r:
            row["hit-rate"] = r["hit_rate"]
            row["skipped-by-kind"] = ", ".join(f"{k} {n}" for k, n in r["skipped_by_kind"].items())
        return row
    if "median_s" not in r:
        return None
    row = {**common, "value": r["median_s"], "unit": "s", "runs": r["runs"], "p90-s": r["p90_s"],
           "failed": sum(1 for v in r["verdicts"] if v)}
    if r.get("tests") is not None:
        row["tests"] = r["tests"]
    if r.get("hit_rate") is not None:
        row["hit-rate"] = r["hit_rate"]
    return row


def measure(ctx) -> list[dict]:
    chosen = arms(ctx)
    if not chosen:
        return []
    started = time.time()
    out = Path(os.environ["SUITE_OUT"]) if os.environ.get("SUITE_OUT") else ctx.out / "suite"
    cmd = [sys.executable, str(MEASURE), "--out", str(out), "--runs", str(ctx.runs),
           "--commits", os.environ.get("SUITE_COMMITS", "200")]
    for arm in chosen:
        cmd += ["--arm", arm]
    if os.environ.get("SUITE_CACHE_REMOTE"):
        cmd += ["--cache-remote", os.environ["SUITE_CACHE_REMOTE"]]
    if os.environ.get("SUITE_USD_PER_RUNNER_MINUTE"):
        cmd += ["--usd-per-runner-minute", os.environ["SUITE_USD_PER_RUNNER_MINUTE"]]
    for tool in ctx.tools or []:
        cmd += ["--tool", tool]
    subprocess.run(cmd, env=dict(os.environ, JK_BIN=ctx.jk), check=False)
    rows_file = out / "rows.jsonl"
    if not rows_file.is_file():
        raise SystemExit(f"suite: the harness wrote no rows; see {out}")
    rows = []
    for line in rows_file.read_text(encoding="utf-8").splitlines():
        r = json.loads(line)
        row = as_row(r, started)
        if row is None:
            ctx.skipped.append(f"{SUBJECTS[r['arm']]} [{r['tool']}] {r['fixture']}: {r.get('status') or 'no run finished'}")
        else:
            rows.append(row)
    return rows
