#!/usr/bin/env python3
"""Score jk's ETA and progress bar against what each run really took.

Reads run records (record.json + details.jsonl) from a project home and measures, per run, what
the user was shown against the truth:

- R0: the seed estimate against the wall that remained when it was shown
- ETA: the remaining time shown at 10/25/50/75/90% of the wall against the true remaining
- bar: the percentage shown at those points minus the elapsed share
- plateau: the longest stretch the bar moved less than one point, as a share of the wall
- tail: the time spent at 99% or more before the run finished, as a share of the wall

The state at a checkpoint is the last event at or before it: what the screen showed then.
Runs are grouped by scenario (a corpus manifest) or by argv, and each group is held to the
targets in TARGETS. Exit status is 1 when any group that a target applies to misses it.

    score.py --home ~/src/scratch/eta-home                # every run in the home's projects
    score.py --home ~/.jk --last 50                       # the latest 50 runs
    score.py --home H --manifest corpus/manifest.tsv      # group by scenario, warm/cold
    score.py --home H --json out.jsonl                    # one line per run, appended
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
from dataclasses import asdict, dataclass, field
from pathlib import Path

CHECKPOINTS = (0.10, 0.25, 0.50, 0.75, 0.90)
SCORED = (0.25, 0.50, 0.75)

# (warm, cold) thresholds; "share" is the share of runs that must land inside the band.
TARGETS = {
    "r0": {"band": (0.15, 0.40), "share": 0.80},
    "eta": {"band": (0.20, 0.40), "share": 0.80},
    "bar": {"band": (10.0, 20.0), "share": 0.80},
    "plateau": {"band": (0.10, 0.15), "share": 0.80},
    "tail": {"band": (0.03, 0.03), "share": 0.80, "or_seconds": 2.0},
}


@dataclass
class Point:
    at: float
    bar: float | None
    bar_err: float | None
    eta_ms: int | None
    true_ms: int
    eta_err: float | None
    # Whether the truth fell inside the likely range shown (JK-4TEZ7); None when none was shown.
    in_range: bool | None = None


@dataclass
class RunScore:
    run: str
    argv: str
    scenario: str
    warm: bool
    wall_ms: int
    success: bool
    r0_ms: int | None
    r0_true_ms: int | None
    r0_err: float | None
    points: list[Point] = field(default_factory=list)
    plateau: float = 0.0
    tail: float = 0.0
    tail_ms: int = 0


def load_events(path: Path) -> list[dict]:
    out = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return out


def hold_intervals(ev: list[dict]) -> list[tuple[int, int]]:
    """The run's holds for memory, (start, end) in ms: hold-start to hold-end, or to the last event."""
    out, since = [], None
    for e in ev:
        if e.get("type") == "hold-start" and since is None:
            since = e.get("ts")
        elif e.get("type") == "hold-end" and since is not None:
            out.append((since, e.get("ts", since)))
            since = None
    if since is not None:
        out.append((since, max(e.get("ts", since) for e in ev)))
    return out


def work_time(ts: int, holds: list[tuple[int, int]]) -> int:
    """`ts` less the time held before it: a hold is a pause, not work, and is not scored."""
    held = 0
    for start, end in holds:
        if ts <= start:
            break
        held += min(ts, end) - start
    return ts - held


def score_run(run_dir: Path, scenario: str = "", warm: bool = True) -> RunScore | None:
    details = run_dir / "details.jsonl"
    if not details.is_file():
        return None
    ev = load_events(details)
    if not ev or ev[0].get("type") != "session-start":
        return None
    # Time on hold for memory comes off the run's clock: the countdown stands still through a
    # hold, and the run's wall the estimate is judged against is its working time (JK-VYJ4X).
    holds = hold_intervals(ev)
    if holds:
        for e in ev:
            if isinstance(e.get("ts"), (int, float)):
                e["ts"] = work_time(e["ts"], holds)
    argv = " ".join(ev[0].get("argv") or [])
    t0 = ev[0]["ts"]
    finish = [e for e in ev if e.get("type") == "workspace-finish"]
    end = finish[-1]["ts"] if finish else max(e.get("ts", t0) for e in ev)
    wall = end - t0
    success = bool(finish and finish[-1].get("success"))
    wp = [e for e in ev if e.get("type") == "workspace-progress"]

    r0 = r0_true = r0_err = None
    first_eta = next((e for e in ev if e.get("type") == "eta" and (e.get("remainingMs") or 0) > 0), None)
    if first_eta is not None:
        r0 = int(first_eta["remainingMs"])
        r0_true = end - first_eta["ts"]
        r0_err = (r0 - r0_true) / r0_true if r0_true > 0 else None

    s = RunScore(run_dir.name, argv, scenario or argv, warm, wall, success, r0, r0_true, r0_err)

    for f in CHECKPOINTS:
        at = t0 + f * wall
        shown = None
        for e in wp:
            if e["ts"] > at:
                break
            shown = e
        true = int(end - at)
        if shown is None:
            s.points.append(Point(f, None, None, None, true, None))
            continue
        bar = shown.get("progress")
        bar = float(bar) if isinstance(bar, (int, float)) else None
        rem = shown.get("remainingMs")
        eta = None
        in_range = None
        if isinstance(rem, (int, float)) and rem >= 0 and shown.get("phase") != "preflight":
            # What the countdown showed: the last re-anchor, decayed by the wall since it.
            eta = max(0, int(rem - (at - shown["ts"])))
            low, high = shown.get("etaLowMs"), shown.get("etaHighMs")
            if isinstance(low, (int, float)) and isinstance(high, (int, float)) and low >= 0 and high >= 0:
                # The range rides the residual: its gaps below and above stay as the countdown decays.
                in_range = (eta - (rem - low)) <= true <= (eta + (high - rem))
        s.points.append(
            Point(
                f,
                bar,
                None if bar is None else bar - f * 100.0,
                eta,
                true,
                None if eta is None or true <= 0 else (eta - true) / true,
                in_range,
            )
        )

    # Plateau: the longest stretch over which the bar rose by less than one point.
    if wall > 0 and wp:
        series = [(t0, 0.0)] + [(e["ts"], float(e.get("progress") or 0.0)) for e in wp] + [(end, 100.0)]
        longest = 0
        start_t, start_v = series[0]
        for t, v in series[1:]:
            if v - start_v >= 1.0:
                start_t, start_v = t, v
            else:
                longest = max(longest, t - start_t)
        s.plateau = longest / wall
        at99 = next((e["ts"] for e in wp if float(e.get("progress") or 0) >= 99.0), None)
        if at99 is not None:
            s.tail_ms = int(end - at99)
            s.tail = s.tail_ms / wall
    return s


def runs_of(home: Path) -> list[Path]:
    root = home / "state" / "builds" / "projects"
    if not root.is_dir():
        root = home  # a project home or a runs dir handed in directly
    found = []
    for runs in [root] if root.name == "runs" else root.glob("*/runs"):
        for d in runs.iterdir():
            if d.is_dir() and d.name.isdigit():
                found.append(d)
    found.sort(key=lambda p: (p.stat().st_mtime, int(p.name)))
    return found


def read_manifest(path: Path) -> dict[str, tuple[str, bool]]:
    """run dir (absolute) -> (scenario, warm). TSV: run_dir, scenario, iteration (1 = cold)."""
    out = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        out[str(Path(parts[0]).resolve())] = (parts[1], parts[2].strip() != "1")
    return out


def pct(v: float | None) -> str:
    return "    -" if v is None else f"{v * 100:+5.0f}%"


def pts(v: float | None) -> str:
    return "   -" if v is None else f"{v:+4.0f}"


def within(v: float | None, limit: float) -> bool:
    return v is not None and abs(v) <= limit


def judge(group: list[RunScore], warm: bool) -> dict[str, tuple[float, bool]]:
    """Share of runs inside each target's band, and whether the group passes it."""
    k = 0 if warm else 1
    res = {}
    n = len(group)
    if n == 0:
        return res

    def share(ok) -> float:
        return sum(1 for s in group if ok(s)) / n

    r0 = share(lambda s: within(s.r0_err, TARGETS["r0"]["band"][k]))
    eta = share(
        lambda s: all(within(p.eta_err, TARGETS["eta"]["band"][k]) for p in s.points if p.at in SCORED)
    )
    bar = share(lambda s: all(within(p.bar_err, TARGETS["bar"]["band"][k]) for p in s.points if p.at in SCORED))
    plateau = share(lambda s: s.plateau <= TARGETS["plateau"]["band"][k])
    tail = share(
        lambda s: s.tail <= TARGETS["tail"]["band"][k] or s.tail_ms <= TARGETS["tail"]["or_seconds"] * 1000
    )
    for name, v in (("r0", r0), ("eta", eta), ("bar", bar), ("plateau", plateau), ("tail", tail)):
        res[name] = (v, v >= TARGETS[name]["share"])
    return res


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--home", type=Path, required=True, help="JK_HOME, a project home, or a runs dir")
    ap.add_argument("--manifest", type=Path, help="corpus manifest (run dir, scenario, iteration)")
    ap.add_argument("--last", type=int, default=0, help="only the latest N runs")
    ap.add_argument("--min-wall", type=float, default=20.0, help="skip runs shorter than this (seconds)")
    ap.add_argument("--match", default="", help="only runs whose argv contains this")
    ap.add_argument("--all", action="store_true", help="include failed runs")
    ap.add_argument("--json", type=Path, help="append one JSON line per run")
    ap.add_argument("--quiet", action="store_true", help="summary only")
    a = ap.parse_args(argv)

    home = a.home.expanduser()
    dirs = runs_of(home)
    manifest = read_manifest(a.manifest.expanduser()) if a.manifest else None
    if manifest is not None:
        dirs = [d for d in dirs if str(d.resolve()) in manifest]
    if a.last:
        dirs = dirs[-a.last :]

    scores: list[RunScore] = []
    for d in dirs:
        scenario, warm = manifest.get(str(d.resolve()), ("", True)) if manifest else ("", True)
        s = score_run(d, scenario, warm)
        if s is None or s.wall_ms < a.min_wall * 1000:
            continue
        if not a.all and not s.success:
            continue
        if a.match and a.match not in s.argv:
            continue
        scores.append(s)

    if a.json:
        with a.json.expanduser().open("a", encoding="utf-8") as f:
            for s in scores:
                f.write(json.dumps(asdict(s)) + "\n")

    if not scores:
        print("no runs matched", file=sys.stderr)
        return 2

    if not a.quiet:
        hdr = "run    wall   R0err  ETA@10  @25    @50    @75    @90   bar@25 @50  @75 plat  tail  scenario"
        print(hdr)
        for s in scores:
            p = {pt.at: pt for pt in s.points}
            print(
                f"{s.run:>5} {s.wall_ms / 1000:6.0f}s {pct(s.r0_err)} "
                + " ".join(pct(p[f].eta_err) for f in CHECKPOINTS)
                + "  "
                + " ".join(pts(p[f].bar_err) for f in SCORED)
                + f" {s.plateau * 100:4.0f}% {s.tail * 100:4.0f}%  {s.scenario[:48]}{'' if s.warm else ' (cold)'}"
            )
        print()

    failed = False
    groups: dict[tuple[str, bool], list[RunScore]] = {}
    for s in scores:
        groups.setdefault((s.scenario, s.warm), []).append(s)
    print("group                                              n  median|ETA|  median|R0|   r0   eta   bar  plat  tail")
    for (name, warm), g in sorted(groups.items()):
        etas = [abs(p.eta_err) for s in g for p in s.points if p.at in SCORED and p.eta_err is not None]
        r0s = [abs(s.r0_err) for s in g if s.r0_err is not None]
        verdict = judge(g, warm)
        cells = []
        for k in ("r0", "eta", "bar", "plateau", "tail"):
            v, ok = verdict[k]
            failed |= not ok
            cells.append(f"{v * 100:3.0f}%{'' if ok else '!'}")
        label = f"{name[:44]}{'' if warm else ' (cold)'}"
        print(
            f"{label:48s} {len(g):3d}  {100 * statistics.median(etas) if etas else 0:9.0f}%"
            f"  {100 * statistics.median(r0s) if r0s else 0:9.0f}%  " + " ".join(f"{c:>5}" for c in cells)
        )
    print("\n(cells: share of runs inside the target band; ! = below the required share)")
    # Calibration of the likely range (JK-4TEZ7): the share of scored checkpoints whose truth fell
    # inside the range shown, meant to be about 80%.
    ranged = [p.in_range for s in scores for p in s.points if p.at in SCORED and p.in_range is not None]
    if ranged:
        cov = sum(1 for r in ranged if r) / len(ranged)
        ok = 0.70 <= cov <= 0.90
        print(f"range coverage: {100 * cov:.0f}% of {len(ranged)} checkpoints (target 70-90%){'' if ok else ' !'}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
