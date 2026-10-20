"""Paired walls of the inner loop: the candidate jk against a baseline jk on the same host, interleaved.

The candidate is the jk under test ($JK_BIN, else the installed client); the baseline is
`--baseline` ($SCOREBOARD_BASELINE, else the installed client, so an unpaired run is an A/A check).
Each side gets its own private engine and its own working copy of the scoreboard's petclinic, and
every pair runs the two sides back to back in a random order. Load slows both sides of a pair
alike, so a pair's ratio holds where an absolute wall would not; the row's value is the median of
the pair ratios, which also cancels load that rises or falls across the batch.

Subjects (`inner.<scenario>.wall`, unit `ratio`, candidate over baseline):

  noop   a build with nothing changed
  edit   a build after one source file is edited

Each row carries both medians and minimums in milliseconds, the ratio of the medians, and a
bootstrap 95% CI of the median pair ratio. A
row fails when the CI's lower bound is above 1 + band: the candidate is slower beyond doubt.
"""

from __future__ import annotations

import random
import statistics
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _petclinic as pc  # noqa: E402
import benchtools  # noqa: E402

TIERS = ("fast", "night", "release")
SCENARIOS = ("noop", "edit")
BAND = 0.10
RESAMPLES = 2000
WARMUPS = 3


def timed(scenario: str, work: Path, jk: str, env: dict, state: Path) -> float:
    """Seconds of one build of the scenario, started from a built tree and a quiet engine."""
    pc.settle(work, jk, env)
    pc.quiesce(state, quiet_s=0.5, cap_s=5.0)
    if scenario == "edit":
        pc.edit(work)
    t0 = time.perf_counter()
    res = pc.build(work, jk, env, "--no-ansi")
    wall = time.perf_counter() - t0
    if res.returncode:
        raise SystemExit(f"paired: {jk} build failed in the {scenario} scenario:\n{res.stdout.decode()[-2000:]}")
    return wall


def pair_ratios(cand: list[float], base: list[float]) -> list[float]:
    return [c / b for c, b in zip(cand, base)]


def ratio_ci(ratios: list[float], rng: random.Random) -> tuple[float, float]:
    """Bootstrap 95% CI of the median pair ratio."""
    n = len(ratios)
    boot = sorted(statistics.median(ratios[rng.randrange(n)] for _ in range(n)) for _ in range(RESAMPLES))
    return boot[int(RESAMPLES * 0.025)], boot[int(RESAMPLES * 0.975) - 1]


def measure(ctx) -> list[dict]:
    sides = {"candidate": ctx.jk, "baseline": ctx.baseline}
    states = {name: ctx.home / "paired" / name for name in sides}
    envs = {name: pc.engine_env(states[name]) for name in sides}
    works = {}
    for name, jk in sides.items():
        pc.stop(jk, states[name])
        works[name] = pc.working_copy(ctx.home, jk, states[name], f"{pc.PROJECT}-{name}")
        pc.settle(works[name], jk, envs[name])
    versions = {name: pc.version(jk) for name, jk in sides.items()}
    rng = random.Random(4083)
    rows = []
    for scenario in SCENARIOS:
        subject = f"inner.{scenario}.wall"
        if not ctx.wants(subject):
            continue
        before = benchtools.conditions()
        walls: dict[str, list[float]] = {name: [] for name in sides}
        for _ in range(WARMUPS):  # the compiler worker's JIT needs a few edits before it is warm
            for name in sides:
                timed(scenario, works[name], sides[name], envs[name], states[name])
        for _ in range(ctx.pairs):
            order = list(sides)
            rng.shuffle(order)
            for name in order:
                walls[name].append(timed(scenario, works[name], sides[name], envs[name], states[name]))
        after = benchtools.conditions()
        cand, base = walls["candidate"], walls["baseline"]
        ratios = pair_ratios(cand, base)
        low, high = ratio_ci(ratios, rng)
        row = {
            "dim": "inner", "subject": subject, "project": pc.PROJECT, "tool": "jk", "kind": "paired-wall",
            "value": round(statistics.median(ratios), 3), "unit": "ratio",
            "band": BAND, "runs": ctx.pairs, "ci-low": round(low, 3), "ci-high": round(high, 3),
            "ratio-of-medians": round(statistics.median(cand) / statistics.median(base), 3),
            "candidate-ms": round(statistics.median(cand) * 1000), "baseline-ms": round(statistics.median(base) * 1000),
            "candidate-min-ms": round(min(cand) * 1000), "baseline-min-ms": round(min(base) * 1000),
            "candidate": versions["candidate"], "baseline": versions["baseline"],
            "samples": [round(x * 1000) for x in cand], "baseline-samples": [round(x * 1000) for x in base],
        }
        row.update({f"{k}-start": v for k, v in before.items()})
        row.update({f"{k}-end": v for k, v in after.items()})
        rows.append(row)
        for name in sides:
            pc.unedit(works[name])
    for name, jk in sides.items():
        pc.stop(jk, states[name])
    return rows
