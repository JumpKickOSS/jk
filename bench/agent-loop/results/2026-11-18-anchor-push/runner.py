#!/usr/bin/env python3
"""Run one tier's plan sequentially: one agent run per command, gate-aware, fail-fast per config.

    runner.py PLAN.json LOG

PLAN.json: {"tier": "t0", "rows_globs": [...], "runs": [{"cfg": "...", "env": {...}, "cmd": [...], "cap_s": N}, ...]}
Each run's new rows (lines appended to any file matching rows_globs) are logged. A config whose first two
rows in this tier are both `error` is skipped for the rest of the tier. While GATE exists, wait (polls of
60 s, 5 min per logged pause, up to 2 h; then stop). Ends with a `DONE` line.

Each END line carries the run's wall-clock and monotonic seconds and the least MemAvailable seen while it ran;
a run whose wall clock moved more than 5 s beyond the monotonic clock (the host slept) is marked SUSPEND.
Runs matching $BENCH_CUTS (default /var/tmp/jk-bench/run/CUTS; see cuts()) are skipped and logged CUT.
Keeping the host awake is the caller's job (on daybreak: awake-start.ps1, a Windows process started through WMI).
$BENCH_GATE and $BENCH_AGENT_LOOP default to the first host's (bocabox) paths.
"""
import glob
import json
import os
import signal
import subprocess
import sys
import time
from pathlib import Path

GATE = Path(os.environ.get("BENCH_GATE", "/var/tmp/jk-review2/GATE_RUNNING"))
AGENT_LOOP = os.environ.get("BENCH_AGENT_LOOP", "/var/tmp/jk-review2/wt/bench-multi-agent/bench/agent-loop")
CUTS = Path(os.environ.get("BENCH_CUTS", "/var/tmp/jk-bench/run/CUTS"))


def cuts():
    """Runs cut by the budget, read before every run so a cut applies without stopping the batch: lines of
    `<cfg> <arm>`, `<cfg> *` or `repeat <k>`; # starts a comment."""
    try:
        lines = CUTS.read_text().splitlines()
    except OSError:
        return set()
    return {tuple(x.split()[:2]) for x in (l.split("#")[0].strip() for l in lines) if len(x.split()) >= 2}


def mem_available_mb():
    with open("/proc/meminfo") as fh:
        for line in fh:
            if line.startswith("MemAvailable:"):
                return int(line.split()[1]) // 1024
    return -1


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
    log(logf, f"START tier={plan['tier']} runs={len(plan['runs'])} pid={os.getpid()} mem_avail_mb={mem_available_mb()}")
    return run_all(plan, logf)


def run_all(plan, logf):
    first = {}   # cfg -> outcomes of its first rows this tier
    skipped = set()
    for i, run in enumerate(plan["runs"], 1):
        cfg = run["cfg"]
        if cfg in skipped:
            log(logf, f"SKIP {i} {cfg}: config stopped (fail fast)")
            continue
        cut = cuts()
        if (cfg, "*") in cut or (cfg, run.get("arm")) in cut or ("repeat", str(run.get("repeat"))) in cut:
            log(logf, f"CUT {i} {cfg} arm={run.get('arm')} repeat={run.get('repeat')}: in {CUTS}")
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
        with open("/proc/loadavg") as fh:
            load = fh.read().split()[0]
        log(logf, f"RUN {i}/{len(plan['runs'])} {cfg} load={load} :: {' '.join(run['cmd'][1:])}")
        t0 = time.time()
        m0 = time.monotonic()
        mem_min = mem_available_mb()
        with open(sys.argv[2] + ".out", "a") as out:
            p = subprocess.Popen(run["cmd"], cwd=AGENT_LOOP, env=env, stdout=out, stderr=subprocess.STDOUT,
                                 start_new_session=True)
            try:
                rc = None
                while rc is None:
                    try:
                        rc = p.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        mem_min = min(mem_min, mem_available_mb())
                        if time.monotonic() - m0 > run["cap_s"]:
                            raise
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
        # A row whose agent never answered (no tokens, an error event: a lost login, a refused model) is a
        # driver failure even when the verify grades it red, not error.
        outcomes = [("error" if r.get("tokens") is None and r.get("agent_finding") else r.get("outcome"))
                    for r in rows] or [f"norow rc={rc}"]
        wall, mono = time.time() - t0, time.monotonic() - m0
        mark = " SUSPEND" if wall - mono > 5 else ""
        log(logf, f"END {i} rc={rc} {wall:.0f}s wall={wall:.1f} mono={mono:.1f} mem_min_avail_mb={mem_min}{mark}")
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
