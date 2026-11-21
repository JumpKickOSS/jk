"""The Android lock: how long `jk lock` takes on Now in Android's 27-module workspace, the resolve an
Android adopter waits for most.

Night and release tiers only (behind the quiet gate). The project is the jk-examples overlay
(`04-clients/nowinandroid/overlay`, or $JK_NIA_OVERLAY), copied under the scoreboard home so the
committed lock is never rewritten; each run starts from that lock. It runs on a private engine that
shares the user's store, so one untimed lock first fills the store and every timed lock reads a warm
disk. Subjects (seconds, median of the runs):

  lock.nia.cold   the first lock an engine serves: warm store, cold process caches
  lock.nia.hot    the same lock again on that engine

NiaWarmLockTimingTest (server/resolver) prints the phase split of the same two locks; this row is
the gate on their cost.
"""

from __future__ import annotations

import os
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _petclinic as pc  # noqa: E402

TIERS = ("night", "release")
KIND = "absolute-wall"
PROJECT = "nowinandroid"
JK_TREE = Path(__file__).resolve().parent.parent.parent
DEFAULT_OVERLAY = JK_TREE.parent / "jk-examples" / "04-clients" / "nowinandroid" / "overlay"
BAND = 0.15
TARGETS = {"cold": 8.0, "hot": None}


def overlay() -> Path:
    env = os.environ.get("JK_NIA_OVERLAY")
    return Path(env) if env else DEFAULT_OVERLAY


def lock(jk: str, work: Path, env: dict) -> float:
    """One `jk lock` from the overlay's committed lock, in seconds."""
    shutil.copy(overlay() / "jk-lock.toml", work / "jk-lock.toml")
    t0 = time.perf_counter()
    res = subprocess.run([jk, "lock", "--no-ansi"], cwd=work, env=env, capture_output=True, text=True)
    wall = time.perf_counter() - t0
    if res.returncode:
        raise RuntimeError(f"jk lock exited {res.returncode}: {(res.stdout + res.stderr).strip()[-400:]}")
    return wall


def measure(ctx) -> list[dict]:
    if not any(ctx.wants(f"lock.nia.{s}") for s in TARGETS):
        return []
    source = overlay()
    if not (source / "jk.toml").is_file() or not (source / "jk-lock.toml").is_file():
        ctx.skipped.append(f"lock: no nowinandroid overlay with a jk.toml and jk-lock.toml at {source} (set JK_NIA_OVERLAY)")
        return []
    work = ctx.home / "work" / f"{PROJECT}-lock"
    shutil.rmtree(work, ignore_errors=True)
    shutil.copytree(source, work)
    state = ctx.home / "lock-state"
    env = pc.engine_env(state, jk=ctx.jk)
    walls: dict[str, list[float]] = {"cold": [], "hot": []}
    try:
        pc.stop(ctx.jk, state)
        lock(ctx.jk, work, env)
        for _ in range(ctx.runs):
            pc.stop(ctx.jk, state)
            walls["cold"].append(lock(ctx.jk, work, env))
            walls["hot"].append(lock(ctx.jk, work, env))
    except RuntimeError as e:
        ctx.skipped.append(f"lock.nia: {e}")
        return []
    finally:
        pc.stop(ctx.jk, state)
    rows = []
    for name, samples in walls.items():
        subject = f"lock.nia.{name}"
        if ctx.wants(subject):
            rows.append({"dim": "lock", "subject": subject, "project": PROJECT, "tool": "jk", "kind": KIND,
                         "value": round(statistics.median(samples), 2), "unit": "s", "band": BAND,
                         "runs": len(samples), "target": TARGETS[name],
                         "samples": [round(s, 2) for s in samples]})
    return rows
