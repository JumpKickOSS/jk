"""CLI latency: how long a jk command takes end to end, p50 and p99 over 30 runs, on the
scoreboard's petclinic and a private engine.

Night and release tiers only (behind the quiet gate): a 4 ms command is all noise on a busy host.
Subjects (`cli.latency.<command>.<p50|p99>`, milliseconds):

  version, help           client only: the native client answers without the engine
  status, deps, explain   a round trip to a warm engine
  noop-build              `jk build` with nothing changed, warm engine
  version-cold            `jk --version` with no engine running: the native client's own start
  status-cold             the first `jk status` an engine serves after a no-op build
  noop-build-cold         a no-op build that starts the engine
"""

from __future__ import annotations

import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _petclinic as pc  # noqa: E402

TIERS = ("night", "release")
KIND = "absolute-wall"
RUNS = 30
COLD_RUNS = 5
BAND = 0.15
WARM = {
    "version": ["--version"],
    "help": ["--help"],
    "status": ["status", "--no-ansi"],
    "deps": ["deps", "--no-ansi"],
    "explain": ["explain", "--no-ansi"],
    "noop-build": ["build", "--skip-tests", "--no-ansi"],
}
TARGETS = {"version": 15, "help": 15, "status": 60, "deps": 60, "explain": 60, "noop-build": 60}


def once(jk: str, args: list[str], work: Path, env: dict) -> float:
    t0 = time.perf_counter()
    res = subprocess.run([jk, *args], cwd=work, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    wall = (time.perf_counter() - t0) * 1000
    if res.returncode:
        raise SystemExit(f"latency: jk {' '.join(args)} exited {res.returncode}")
    return wall


def p99(walls: list[float]) -> float:
    ordered = sorted(walls)
    return ordered[min(len(ordered) - 1, round(0.99 * (len(ordered) - 1)))]


def rows_for(name: str, walls: list[float], ctx) -> list[dict]:
    out = []
    for stat, value in (("p50", statistics.median(walls)), ("p99", p99(walls))):
        subject = f"cli.latency.{name}.{stat}"
        if ctx.wants(subject):
            out.append({"dim": "cli", "subject": subject, "project": pc.PROJECT, "tool": "jk", "kind": KIND,
                        "value": round(value, 1), "unit": "ms", "band": BAND, "runs": len(walls),
                        "target": TARGETS.get(name) if stat == "p50" else None,
                        "samples": [round(w, 1) for w in walls]})
    return out


def measure(ctx) -> list[dict]:
    state = ctx.home / "latency-state"
    env = pc.engine_env(state, jk=ctx.jk)
    pc.stop(ctx.jk, state)
    work = pc.working_copy(ctx.home, ctx.jk, state, f"{pc.PROJECT}-latency")
    pc.settle(work, ctx.jk, env)
    rows = []
    cold: dict[str, list[float]] = {"version-cold": [], "noop-build-cold": [], "status-cold": []}
    for _ in range(COLD_RUNS):
        pc.stop(ctx.jk, state)
        cold["version-cold"].append(once(ctx.jk, ["--version"], work, env))
        cold["noop-build-cold"].append(once(ctx.jk, WARM["noop-build"], work, env))
        pc.quiesce(state, quiet_s=1.0, cap_s=30.0)
        cold["status-cold"].append(once(ctx.jk, WARM["status"], work, env))
    for name, walls in cold.items():
        rows += rows_for(name, walls, ctx)
    for name, args in WARM.items():
        if not any(ctx.wants(f"cli.latency.{name}.{s}") for s in ("p50", "p99")):
            continue
        for _ in range(3):
            once(ctx.jk, args, work, env)
        rows += rows_for(name, [once(ctx.jk, args, work, env) for _ in range(RUNS)], ctx)
    pc.stop(ctx.jk, state)
    return rows
