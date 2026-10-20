"""Work counts of the inner loop: what jk does on a no-op and on a one-file edit, read from counters
that load does not move.

The project is the wall harness's spring-petclinic (bench/wall: same commit, same jk.toml and
jk-lock.toml, same edited file). It runs on a private engine (its own JK_STATE_DIR under the
scoreboard home) sharing the user's store and caches, so another session's jobs on the shared engine
never land in these counters and the caches stay warm.

Subjects, per scenario (`inner.<scenario>.<counter>`):

  tasks-run              steps that finished SUCCESS (`--output json` task-finish events)
  tasks-skipped          steps that finished SKIPPED
  disk-write-bytes       bytes the engine process tree dirtied in files (/proc/<pid>/io write_bytes):
                         every file write, native copies by workers included; no socket or wakeup
  target-files-touched   files under target/ created, deleted or rewritten (inode, size or mtime)
  files-written          distinct files the engine JVM wrote (JFR jdk.FileWrite; temp names folded)
  file-write-bytes       bytes the engine JVM wrote to files (JFR)
  file-read-bytes        bytes the engine JVM read from files, /proc and /sys left out (JFR)
  output-bytes           what `jk build --no-ansi` printed, stdout and stderr

Counters the engine has to report itself (files stat'd, files hashed, files compiled, actions
restored) are not here yet; /proc and JFR cannot see them.

Each row takes `runs` measured runs after one warm-up. The value is the median; a row with more
than a quarter of its runs outside the band around that median is `unstable`. The JFR counters come from a second set of runs with a
recording attached, since a recording writes files of its own.
"""

from __future__ import annotations

import json
import os
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

TIERS = ("fast", "night", "release")

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _petclinic as pc  # noqa: E402
from _petclinic import PROJECT, edit, settle  # noqa: E402

SCENARIOS = ("noop", "edit")

# (counter, unit, band, slack, target) — slack absorbs absolute drift a ratio cannot (a few log lines).
COUNTERS = {
    "tasks-run": ("tasks", 0.0, 0, None),
    "tasks-skipped": ("tasks", 0.0, 0, None),
    "disk-write-bytes": ("bytes", 0.02, 8192, None),
    "target-files-touched": ("files", 0.0, 0, None),
    "files-written": ("files", 0.0, 1, None),
    "file-write-bytes": ("bytes", 0.02, 4096, None),
    "file-read-bytes": ("bytes", 0.05, 16384, None),
    "output-bytes": ("bytes", 0.0, 8, None),
}
# Where the loop should be: a no-op that writes nothing it did not have to and touches no output.
TARGETS = {
    ("noop", "target-files-touched"): 0,
    ("noop", "files-written"): 2,
    ("noop", "file-write-bytes"): 16 << 10,
    ("noop", "disk-write-bytes"): 64 << 10,
    ("noop", "tasks-run"): 2,
    ("edit", "target-files-touched"): 4,
    ("edit", "file-write-bytes"): 256 << 10,
}

JFC = """<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0">
  <event name="jdk.FileWrite"><setting name="enabled">true</setting><setting name="threshold">0 ms</setting><setting name="stackTrace">false</setting></event>
  <event name="jdk.FileRead"><setting name="enabled">true</setting><setting name="threshold">0 ms</setting><setting name="stackTrace">false</setting></event>
</configuration>
"""
_TMP = re.compile(r"(/\.?[^/]*?)-\d{6,}(\.tmp)$")
_RUN = re.compile(r"/runs/\.?\d+(\.complete)?(\.tmp)?/")


# --------------------------------------------------------------------------- counters

def io_delta(before: dict, after: dict) -> int:
    return sum(b - before.get(pid, 0) for pid, b in after.items())


def target_census(work: Path) -> dict[str, tuple[int, int, int]]:
    census = {}
    root = work / "target"
    if not root.is_dir():
        return census
    for dirpath, _, files in os.walk(root):
        for name in files:
            p = os.path.join(dirpath, name)
            try:
                st = os.stat(p, follow_symlinks=False)
            except OSError:
                continue
            census[p] = (st.st_ino, st.st_size, st.st_mtime_ns)
    return census


def touched(before: dict, after: dict) -> list[str]:
    return sorted(p for p in before.keys() | after.keys() if before.get(p) != after.get(p))


def task_census(jsonl: str) -> tuple[int, int]:
    run = skipped = 0
    for line in jsonl.splitlines():
        try:
            e = json.loads(line)
        except ValueError:
            continue
        if e.get("type") == "task-finish":
            run += e.get("status") == "SUCCESS"
            skipped += e.get("status") == "SKIPPED"
    return run, skipped


# --------------------------------------------------------------------------- scenarios

def one_run(scenario: str, work: Path, jk: str, env: dict, state: Path) -> dict:
    """Plain pass: /proc counters, the target census, the task census and the output size."""
    if scenario == "edit":
        edit(work)
    io0 = pc.quiesce(state)
    t0 = target_census(work)
    plain = subprocess.run([jk, "build", "--skip-tests", "--no-ansi"], cwd=work, env=env, capture_output=True)
    io1 = pc.quiesce(state)
    t1 = target_census(work)
    sample = {"disk-write-bytes": io_delta(io0, io1), "target-files-touched": len(touched(t0, t1)),
              "_touched": touched(t0, t1),
              "output-bytes": len(plain.stdout) + len(plain.stderr), "rc": plain.returncode}
    # The task census needs the JSON stream; it is a separate build of the same scenario state.
    settle(work, jk, env)
    if scenario == "edit":
        edit(work)
    js = subprocess.run([jk, "build", "--skip-tests", "--output", "json"], cwd=work, env=env, capture_output=True, text=True)
    sample["tasks-run"], sample["tasks-skipped"] = task_census(js.stdout)
    settle(work, jk, env)
    return sample


def jfr_run(scenario: str, work: Path, jk: str, env: dict, state: Path, jfc: Path, out: Path) -> dict:
    """Recording pass: files the engine JVM wrote and read, attributed by path."""
    pid = pc.engine_pid(state)
    if pid is None:
        return {}
    java_bin = Path(os.readlink(f"/proc/{pid}/exe")).parent
    jcmd, jfr = str(java_bin / "jcmd"), str(java_bin / "jfr")
    if scenario == "edit":
        edit(work)
    rec = out / f"{scenario}-{time.time_ns()}.jfr"
    pc.quiesce(state)
    subprocess.run([jcmd, str(pid), "JFR.start", "name=scoreboard", f"settings={jfc}"], check=True, capture_output=True)
    try:
        subprocess.run([jk, "build", "--skip-tests", "--no-ansi"], cwd=work, env=env, capture_output=True)
        pc.quiesce(state)
        subprocess.run([jcmd, str(pid), "JFR.dump", "name=scoreboard", f"filename={rec}"], check=True, capture_output=True)
    finally:
        subprocess.run([jcmd, str(pid), "JFR.stop", "name=scoreboard"], capture_output=True)
    printed = subprocess.run([jfr, "print", "--json", "--events", "jdk.FileWrite,jdk.FileRead", str(rec)],
                             capture_output=True, text=True, check=True)
    rec.unlink(missing_ok=True)
    written: dict[str, list[int]] = {}
    wbytes, rbytes = 0, 0
    for e in json.loads(printed.stdout)["recording"]["events"]:
        v = e["values"]
        path = v.get("path") or ""
        if e["type"] == "jdk.FileWrite":
            n = max(0, v.get("bytesWritten", 0))
            if path and str(out.parent) not in path:
                calls_bytes = written.setdefault(_RUN.sub("/runs/N/", _TMP.sub(r"\1\2", path)), [0, 0])
                calls_bytes[0] += 1
                calls_bytes[1] += n
            wbytes += n
        elif not path.startswith(("/proc/", "/sys/")):
            rbytes += max(0, v.get("bytesRead", 0))
    settle(work, jk, env)
    return {"files-written": len(written), "file-write-bytes": wbytes, "file-read-bytes": rbytes,
            "_written": written}


# --------------------------------------------------------------------------- the dimension

def measure(ctx) -> list[dict]:
    state = ctx.home / "state"
    env = pc.engine_env(state)
    # A fresh engine every time: one left running carries memos and pending chores of its own.
    pc.stop(ctx.jk, state)
    work = pc.working_copy(ctx.home, ctx.jk, state)
    jfc = ctx.out / "files.jfc"
    jfc.write_text(JFC, encoding="utf-8")
    rows = []
    for scenario in SCENARIOS:
        if not any(ctx.wants(f"inner.{scenario}.{c}") for c in COUNTERS):
            continue
        settle(work, ctx.jk, env)
        one_run(scenario, work, ctx.jk, env, state)  # warm-up
        plain = [one_run(scenario, work, ctx.jk, env, state) for _ in range(ctx.runs)]
        jfr_run(scenario, work, ctx.jk, env, state, jfc, ctx.out)  # warm-up
        rec = [jfr_run(scenario, work, ctx.jk, env, state, jfc, ctx.out) for _ in range(ctx.runs)]
        if any(s["rc"] for s in plain):
            raise SystemExit(f"counts: jk build failed in the {scenario} scenario; see {work}")
        (ctx.out / f"{scenario}-target-touched.txt").write_text("\n".join(plain[-1]["_touched"]) + "\n")
        if rec and rec[-1]:
            # Where the bytes went, biggest first: the first thing to read when a write row moves.
            lines = [f"{b}\t{c}\t{p}" for p, (c, b) in sorted(rec[-1]["_written"].items(), key=lambda x: -x[1][1])]
            (ctx.out / f"{scenario}-file-writes.tsv").write_text("bytes\tcalls\tpath\n" + "\n".join(lines) + "\n")
        for counter, (unit, band, slack, _) in COUNTERS.items():
            subject = f"inner.{scenario}.{counter}"
            if not ctx.wants(subject):
                continue
            samples = [s[counter] for s in (plain if counter in plain[0] else rec) if counter in s]
            if not samples:
                continue
            value = statistics.median(samples)
            value = int(value) if float(value).is_integer() else value
            tolerance = value * band + slack
            outliers = sum(1 for x in samples if abs(x - value) > tolerance)
            rows.append({
                "dim": "inner", "subject": subject, "project": PROJECT, "tool": "jk", "kind": "count",
                "value": value, "unit": unit, "band": band, "slack": slack, "runs": len(samples),
                "target": TARGETS.get((scenario, counter)), "samples": samples,
                # One stray run in five is a background chore landing in the window, not drift.
                "unstable": outliers > len(samples) // 4,
            })
    pc.stop(ctx.jk, state)
    return rows
