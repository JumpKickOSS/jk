"""The build ETA and progress bar against the walls they predicted: bench/eta's corpus, scored.

The corpus (bench/eta/corpus.sh) runs a fixed list of builds of JumpKick's own tree, pinned at
WORKLOAD, in a private home with the engine under test; bench/eta/score.py reads each run's
journal and judges the countdown and the bar at 10, 25, 50, 75 and 90% of the run's working
time. Iteration 1 of a scenario is cold (an empty ledger for that shape), the rest warm. Runs
shorter than MIN_WALL_S are left out: their checkpoints are mostly empty.

  eta.warm.eta-error       median over warm runs of each run's median |ETA error|, percent
  eta.warm.bar-error       the same for the bar, points of the bar against the true share done
  eta.warm.eta-miss        warm runs whose median |ETA error| is over ETA_BAND, percent of runs
  eta.warm.bar-miss        warm runs whose median |bar error| is over BAR_BAND, percent of runs
  eta.warm.range-miss      warm checkpoints whose truth fell outside the likely range, percent
  eta.cold.eta-error       median |ETA error| over cold runs, percent
  eta.cold.seed-error      median |seed error| (R0 against the run's working time) over cold runs

Night and release tiers only: the corpus is about 80 minutes of every core. The engine under test is
$JK_DIST, else the dist beside $JK_BIN, else this checkout's target/dist.
"""

from __future__ import annotations

import json
import os
import statistics
import subprocess
from pathlib import Path

TIERS = ("night", "release")
JK_TREE = Path(__file__).resolve().parent.parent.parent
CORPUS = JK_TREE / "bench" / "eta" / "corpus.sh"
SCORE = JK_TREE / "bench" / "eta" / "score.py"
# The tree the corpus builds, fixed so nights compare: a change to the workload is a new baseline.
WORKLOAD = "21cb3b713"
MIN_WALL_S = 5.0
ETA_BAND = 0.20
BAR_BAND = 10.0
# (subject, unit, band, slack, target): lower is better; band is the regression tolerance.
ROWS = {
    "eta.warm.eta-error": ("%", 0.15, 3.0, 20.0),
    "eta.warm.bar-error": ("pts", 0.15, 1.0, 10.0),
    "eta.warm.eta-miss": ("%", 0.15, 10.0, 20.0),
    "eta.warm.bar-miss": ("%", 0.15, 10.0, 20.0),
    "eta.warm.range-miss": ("%", 0.15, 10.0, 30.0),
    "eta.cold.eta-error": ("%", 0.20, 10.0, 40.0),
    "eta.cold.seed-error": ("%", 0.20, 10.0, 40.0),
}


def dist(ctx) -> Path | None:
    for d in (os.environ.get("JK_DIST"), str(Path(ctx.jk).resolve().parent), str(JK_TREE / "target" / "dist")):
        if d and (Path(d) / "jk").is_file() and any((Path(d) / "lib").glob("jk-engine-*.jar")):
            return Path(d)
    return None


def workload(ctx) -> Path:
    """A detached worktree of this checkout at WORKLOAD, made once and reset before each corpus."""
    wt = ctx.home / "eta-workload"
    if not (wt / "jk.toml").is_file():
        subprocess.run(["git", "worktree", "add", "--detach", str(wt), WORKLOAD], cwd=JK_TREE, check=True,
                       capture_output=True)
    subprocess.run(["git", "checkout", "--detach", "--force", WORKLOAD], cwd=wt, check=True, capture_output=True)
    subprocess.run(["git", "clean", "-fdq"], cwd=wt, check=True, capture_output=True)
    return wt


def median_abs(points: list[dict], key: str) -> float | None:
    vals = [abs(p[key]) for p in points if p.get(key) is not None]
    return statistics.median(vals) if vals else None


def summarize(runs: list[dict]) -> dict[str, float]:
    ok = [r for r in runs if r.get("success") and r.get("wall_ms", 0) >= MIN_WALL_S * 1000]
    warm = [r for r in ok if r.get("warm")]
    cold = [r for r in ok if not r.get("warm")]
    out: dict[str, float] = {}
    w_eta = [m for r in warm if (m := median_abs(r["points"], "eta_err")) is not None]
    w_bar = [m for r in warm if (m := median_abs(r["points"], "bar_err")) is not None]
    if w_eta:
        out["eta.warm.eta-error"] = round(100 * statistics.median(w_eta), 1)
        out["eta.warm.eta-miss"] = round(100 * sum(m > ETA_BAND for m in w_eta) / len(w_eta), 1)
    if w_bar:
        out["eta.warm.bar-error"] = round(statistics.median(w_bar), 1)
        out["eta.warm.bar-miss"] = round(100 * sum(m > BAR_BAND for m in w_bar) / len(w_bar), 1)
    ranged = [p["in_range"] for r in warm for p in r["points"] if p.get("in_range") is not None]
    if ranged:
        out["eta.warm.range-miss"] = round(100 * sum(not x for x in ranged) / len(ranged), 1)
    c_eta = [m for r in cold if (m := median_abs(r["points"], "eta_err")) is not None]
    if c_eta:
        out["eta.cold.eta-error"] = round(100 * statistics.median(c_eta), 1)
    c_seed = [abs(r["r0_err"]) for r in cold if r.get("r0_err") is not None]
    if c_seed:
        out["eta.cold.seed-error"] = round(100 * statistics.median(c_seed), 1)
    return out


def measure(ctx) -> list[dict]:
    if not any(ctx.wants(s) for s in ROWS):
        return []
    d = dist(ctx)
    if d is None:
        ctx.skipped.append("eta: no dist with an engine jar ($JK_DIST, beside $JK_BIN, or target/dist)")
        return []
    out = ctx.out / "eta"
    out.mkdir(parents=True, exist_ok=True)
    home = ctx.home / "eta-home"
    label = "scoreboard"
    res = subprocess.run(
        ["bash", str(CORPUS), "--dist", str(d), "--worktree", str(workload(ctx)), "--home", str(home),
         "--label", label, "--logs", str(out)],
        cwd=JK_TREE, stdout=open(out / "corpus.log", "w"), stderr=subprocess.STDOUT)
    manifest = out / "manifest.tsv"
    if res.returncode or not manifest.is_file():
        ctx.skipped.append(f"eta: the corpus failed (exit {res.returncode}); see {out / 'corpus.log'}")
        return []
    scored = out / "score.jsonl"
    scored.unlink(missing_ok=True)
    res = subprocess.run(
        ["python3", "-I", str(SCORE), "--home", str(home), "--manifest", str(manifest), "--min-wall", "0",
         "--json", str(scored), "--quiet"],
        cwd=JK_TREE, capture_output=True, text=True)
    if not scored.is_file():
        ctx.skipped.append(f"eta: score.py wrote nothing (exit {res.returncode}): {res.stderr[-300:]}")
        return []
    runs = [json.loads(line) for line in scored.read_text().splitlines() if line.strip()]
    values = summarize(runs)
    rows = []
    for subject, (unit, band, slack, target) in ROWS.items():
        if subject not in values or not ctx.wants(subject):
            continue
        rows.append({"dim": "eta", "subject": subject, "project": "jk", "tool": "jk", "kind": "count",
                     "value": values[subject], "unit": unit, "band": band, "slack": slack, "runs": 1,
                     "target": target, "tool-version": d.name})
    return rows
