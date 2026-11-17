#!/usr/bin/env python3
"""Run one tier's plan sequentially: one agent run per command, gate-aware, fail-fast per config.

    runner.py PLAN.json LOG

PLAN.json: {"tier": "t0", "rows_globs": [...], "runs": [{"cfg": "...", "env": {...}, "cmd": [...], "cap_s": N}, ...]}
Each run's new rows (lines appended to any file matching rows_globs) are logged. A config whose first two
rows in this tier are both `error` is skipped for the rest of the tier. While GATE exists, wait (polls of
60 s, 5 min per logged pause, up to 2 h; then stop). Ends with a `DONE` line.
"""
import glob
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

GATE = Path("/var/tmp/jk-review2/GATE_RUNNING")
AGENT_LOOP = "/var/tmp/jk-review2/wt/bench-multi-agent/bench/agent-loop"


def log(f, msg):
    f.write(time.strftime("%Y-%m-%dT%H:%M:%S ") + msg + "\n")
    f.flush()


def counts(globs):
    out = {}
    for g in globs:
        for p in glob.glob(g):
            with open(p) as fh:
                out[p] = sum(1 for _ in fh)
    return out


def new_rows(globs, before):
    rows = []
    for g in globs:
        for p in glob.glob(g):
            with open(p) as fh:
                lines = fh.read().splitlines()
            rows += [json.loads(x) for x in lines[before.get(p, 0):] if x.strip()]
    return rows


def main():
    plan = json.loads(Path(sys.argv[1]).read_text())
    logf = open(sys.argv[2], "a")
    log(logf, f"START tier={plan['tier']} runs={len(plan['runs'])} pid={os.getpid()}")
    first = {}   # cfg -> outcomes of its first rows this tier
    skipped = set()
    for i, run in enumerate(plan["runs"], 1):
        cfg = run["cfg"]
        if cfg in skipped:
            log(logf, f"SKIP {i} {cfg}: config stopped (fail fast)")
            continue
        waited = 0
        while GATE.exists():
            if waited % 300 == 0:
                log(logf, f"PAUSE gate running (waited {waited}s) before run {i}")
            if waited >= 7200:
                log(logf, "STOP gate wait cap (2 h) hit")
                log(logf, "DONE (stopped at gate)")
                return 3
            time.sleep(60)
            waited += 60
        if waited:
            log(logf, f"RESUME after gate pause of {waited}s")
        before = counts(plan["rows_globs"])
        env = {**os.environ, **run.get("env", {})}
        log(logf, f"RUN {i}/{len(plan['runs'])} {cfg} :: {' '.join(run['cmd'][1:])}")
        t0 = time.time()
        with open(sys.argv[2] + ".out", "a") as out:
            p = subprocess.Popen(run["cmd"], cwd=AGENT_LOOP, env=env, stdout=out, stderr=subprocess.STDOUT,
                                 start_new_session=True)
            try:
                rc = p.wait(timeout=run["cap_s"])
            except subprocess.TimeoutExpired:
                os.killpg(p.pid, signal.SIGTERM)
                time.sleep(10)
                try:
                    os.killpg(p.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                rc = "hard-timeout"
        rows = new_rows(plan["rows_globs"], before)
        for r in rows:
            log(logf, f"  ROW {r.get('driver')}/{r.get('model')} {r.get('task')} {r.get('arm')} #{r.get('run')}: "
                      f"{r.get('outcome')} tokens={r.get('tokens')} turns={r.get('turns')} wall={r.get('wall_s')} "
                      f"cost={r.get('cost_est_usd')} {str(r.get('finding') or '')[:160]}")
        outcomes = [r.get("outcome") for r in rows] or [f"norow rc={rc}"]
        log(logf, f"END {i} rc={rc} {time.time() - t0:.0f}s")
        seen = first.setdefault(cfg, [])
        if len(seen) < 2:
            seen += outcomes
            if len(seen) >= 2 and all(o == "error" or str(o).startswith("norow") for o in seen[:2]):
                skipped.add(cfg)
                log(logf, f"FAILFAST {cfg}: first two runs {seen[:2]}")
    log(logf, "DONE")
    return 0


if __name__ == "__main__":
    sys.exit(main())
