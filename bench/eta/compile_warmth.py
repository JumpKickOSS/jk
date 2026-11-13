#!/usr/bin/env python3
"""What a compile costs on a cold compiler worker against a warm one (JK-D3785).

Reads compile-warmth.sh's manifest: per project, a forced build on a fresh engine (cold worker) and
the same build again while the worker lingers (warm). For each compile step (compile-java,
compile-test, ...) it pairs the two runs' walls, each less its queue wait, and reports:

- per project: the cold and warm totals and the median per-compile difference
- by order: the cold/warm ratio of the 1st, 2nd, ... compile to start, as the worker warms up
- the warm compiles' fit: floor + rate * sources, the standalone price of a compile on a warm worker

    compile_warmth.py --manifest ~/src/scratch/eta-corpus/<label>/manifest.tsv
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import sys
from pathlib import Path

SOURCES = re.compile(r"compiled (\d+)(?: of \d+)? source")


def compiles(run_dir: Path) -> list[dict]:
    """The run's compile steps in the order they started: dir, step, work ms, wait ms, sources."""
    started: dict[tuple[str, str], int] = {}
    dir_of: dict[str, str] = {}
    out = []
    for raw in (run_dir / "details.jsonl").read_text(encoding="utf-8").splitlines():
        try:
            e = json.loads(raw)
        except ValueError:
            continue
        t = e.get("type")
        if t == "module-start":
            dir_of[e.get("coord")] = e.get("dir")
        task = e.get("task") or ""
        if not task.startswith("compile-") or task.startswith("compile-guard"):
            continue
        d = e.get("dir") or dir_of.get(e.get("module"), "")
        if t == "task-start":
            started[(d, task)] = e.get("ts", 0)
        elif t == "task-finish" and e.get("status") == "SUCCESS":
            m = SOURCES.search(e.get("label") or "")
            if not m:
                continue  # an up-to-date or sourceless compile ran no javac
            wait = int(e.get("waitMillis") or 0)
            out.append({"dir": d, "step": task, "work": int(e.get("millis") or 0) - wait, "wait": wait,
                        "sources": int(m.group(1)), "start": started.get((d, task), e.get("ts", 0))})
    out.sort(key=lambda c: c["start"])
    return out


def fit(points: list[tuple[float, float]]) -> tuple[float, float]:
    xs, ys = [p[0] for p in points], [p[1] for p in points]
    mx, my = statistics.fmean(xs), statistics.fmean(ys)
    var = sum((x - mx) ** 2 for x in xs)
    if var == 0:
        return my, 0.0
    rate = max(0.0, sum((x - mx) * (y - my) for x, y in zip(xs, ys)) / var)
    return max(0.0, my - rate * mx), rate


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", type=Path, required=True)
    a = ap.parse_args(argv)
    runs: dict[str, dict[str, Path]] = {}
    for line in a.manifest.expanduser().read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.startswith("#"):
            continue
        run, project, phase, rc = (line.split("\t") + [""] * 4)[:4]
        if rc == "0" and (Path(run) / "details.jsonl").is_file():
            runs.setdefault(project, {})[phase] = Path(run)

    by_order: dict[int, list[float]] = {}
    warm_points: list[tuple[float, float]] = []
    print(f"{'project':34s} {'n':>3s} {'cold s':>7s} {'warm s':>7s} {'median +ms':>10s} {'1st cold/warm':>14s}")
    for project, phases in runs.items():
        if "cold" not in phases or "warm" not in phases:
            continue
        cold = compiles(phases["cold"])
        warm = {(c["dir"], c["step"]): c for c in compiles(phases["warm"])}
        pairs = [(c, warm[(c["dir"], c["step"])]) for c in cold if (c["dir"], c["step"]) in warm]
        if not pairs:
            continue
        for i, (c, w) in enumerate(pairs):
            if w["work"] > 0:
                by_order.setdefault(min(i, 9), []).append(c["work"] / w["work"])
        for _, w in pairs:
            warm_points.append((w["sources"], w["work"]))
        first = pairs[0]
        print(f"{project[:34]:34s} {len(pairs):3d} {sum(c['work'] for c, _ in pairs) / 1000:7.1f} {sum(w['work'] for _, w in pairs) / 1000:7.1f}"
              f" {statistics.median(c['work'] - w['work'] for c, w in pairs):10.0f}"
              f" {first[0]['work']:6d}/{first[1]['work']:<6d}")
    print("\ncold/warm by start order (all projects):")
    for i in sorted(by_order):
        v = by_order[i]
        print(f"   {'%d' % (i + 1) if i < 9 else '10+':>3s}  n={len(v):3d}  median {statistics.median(v):5.2f}x")
    if warm_points:
        floor, rate = fit(warm_points)
        print(f"\nwarm compile: {floor:.0f} ms + {rate:.1f} ms/source over {len(warm_points)} compiles")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
