"""jk building its own tree (scripts/dogfood-wall-measure.sh): a full rebuild, a no-op and one
touched production file, each raw and, where the tree carries jk-guards.toml, with its house-rule
guards on.

Night and release tiers only: a rebuild of this tree is minutes of every core. Subjects:

  dogfood.<row>          `jk build` raw (`[guards] on-build = false`), the published number
  dogfood.<row>.guards   the same build as contributors run it, guards on (its own series)
  dogfood.fresh          the second `jk build` of a fresh clone of the tree (one timed run)
  dogfood.fresh.steps    the productive steps that second build ran: 0, or a key depends on
                         what its own build writes (JK-875KR)

The first run of each row warms the engine and is left out of the median.
"""

from __future__ import annotations

import json
import os
import statistics
import subprocess
from pathlib import Path

TIERS = ("night", "release")
KIND = "absolute-wall"
JK_TREE = Path(__file__).resolve().parent.parent.parent
SCRIPT = JK_TREE / "scripts" / "dogfood-wall-measure.sh"
BAND = 0.15
RUNS = 3


def measure(ctx) -> list[dict]:
    out = ctx.out / "dogfood"
    env = dict(os.environ, JK_BIN=ctx.jk, OUT_DIR=str(out), RUNS=str(min(ctx.runs, RUNS)))
    rows_wanted = [
        r
        for r in ("rebuild", "noop", "touched", "fresh")
        if ctx.wants(f"dogfood.{r}") or ctx.wants(f"dogfood.{r}.guards") or ctx.wants(f"dogfood.{r}.steps")
    ]
    if not rows_wanted:
        return []
    res = subprocess.run(["bash", str(SCRIPT), *rows_wanted], cwd=JK_TREE, env=env)
    jsonl = out / "row.jsonl"
    if res.returncode or not jsonl.is_file():
        ctx.skipped.append(f"dogfood: the measurement failed (exit {res.returncode}); see {out}")
        return []
    rows = []
    for line in jsonl.read_text().splitlines():
        m = json.loads(line)
        walls = [w for w in m.get("walls_s", []) if w is not None]
        if m.get("type") != "measurement" or not walls:
            continue
        timed = walls[1:] if len(walls) > 1 else walls
        subject = f"dogfood.{m['row']}" + (".guards" if m["side"] == "jk-guards" else "")
        rows.append({"dim": "dogfood", "subject": subject, "project": "jk", "tool": "jk", "kind": KIND,
                     "value": round(statistics.median(timed), 2), "unit": "s", "band": BAND, "runs": len(timed),
                     "command": m.get("command", ""), "samples": timed})
        if m["row"] == "fresh" and str(m.get("steps", "")).isdigit():
            rows.append({"dim": "dogfood", "subject": "dogfood.fresh.steps", "project": "jk", "tool": "jk",
                         "kind": "count", "value": int(m["steps"]), "unit": "steps", "band": 0, "runs": 1,
                         "command": m.get("command", ""), "key_moved": m.get("key_moved", "")})
    return rows
