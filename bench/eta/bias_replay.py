#!/usr/bin/env python3
"""Replay a corpus's priced steps through the per-rung bias (RungBias) and score it offline.

Walks the runs in the order they ran. Before each run, a step a prior rung (PROJECT, HOST,
CALIBRATION) priced is corrected by the bias learned from the runs before it; after the run, the
bias learns from that run the way RungBias.learn does. Prints, per rung and kind, the median signed
error of the raw prices and of the corrected ones, so a change to the bias is judged on recorded runs
in seconds. The runs must have been priced without a bias (the bias they carry is not undone).

    bias_replay.py --home ~/src/scratch/eta-home --manifest <logs>/manifest.tsv
"""

from __future__ import annotations

import argparse
import math
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import score  # noqa: E402

ALPHA = 0.4  # RungBias.ALPHA
MIN_WALL_MS = 1000  # RungBias.MIN_WALL_MS
MAX_LOG = math.log(4.0)  # RungBias.MAX_FACTOR
PRIORS = ("PROJECT", "HOST", "CALIBRATION")


def clamp(v: float) -> float:
    return max(-MAX_LOG, min(MAX_LOG, v))


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--home", type=Path, required=True)
    ap.add_argument("--manifest", type=Path)
    ap.add_argument("--alpha", type=float, default=ALPHA)
    a = ap.parse_args(argv)

    dirs = score.runs_of(a.home.expanduser())
    manifest = score.read_manifest(a.manifest.expanduser()) if a.manifest else None
    if manifest is not None:
        dirs = [d for d in dirs if str(d.resolve()) in manifest]

    bias: dict[tuple[str, str], float] = {}
    raw: dict[tuple[str, str], list[float]] = {}
    fixed: dict[tuple[str, str], list[float]] = {}
    for d in dirs:
        s = score.score_run(d)
        if s is None or not s.success:
            continue
        residuals: dict[tuple[str, str], list[float]] = {}
        for _, step, priced, ran, *rest in s.steps:
            rung = rest[0] if rest else ""
            if rung not in PRIORS or priced <= 0 or ran < MIN_WALL_MS:
                continue
            key = (rung, score.step_kind(step))
            f = math.exp(clamp(bias.get(key, 0.0)))
            raw.setdefault(key, []).append((priced - ran) / ran)
            fixed.setdefault(key, []).append((priced * f - ran) / ran)
            residuals.setdefault(key, []).append(clamp(math.log(ran / (priced * f))))
        for key, v in residuals.items():
            r = statistics.median(v)
            bias[key] = clamp(r if key not in bias else bias[key] + a.alpha * r)

    if not raw:
        print("no prior-priced steps carry a rung")
        return 2
    print("rung         kind         n   raw median  |raw|   biased median  |biased|")
    for key in sorted(raw):
        r, b = raw[key], fixed[key]
        print(
            f"{key[0]:12s} {key[1]:10s} {len(r):4d}  {statistics.median(r):+10.0%} {statistics.median(map(abs, r)):6.0%}"
            f"   {statistics.median(b):+12.0%} {statistics.median(map(abs, b)):8.0%}"
        )
    print("final bias: " + ", ".join(f"{k[0]}/{k[1]}={math.exp(v):.2f}x" for k, v in sorted(bias.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
