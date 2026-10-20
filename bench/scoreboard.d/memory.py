"""Idle memory: what the jk process tree holds while the developer is not building, beside the
Gradle daemon after the same work.

Each tool builds and tests the scoreboard's petclinic once (jk on a private engine; Gradle in the
wall harness's working copy with its private user home), then sits idle. At each mark the whole
tree is sampled:

  memory.idle-<mark>.pss               PSS summed over the tree (/proc/<pid>/smaps_rollup), so pages
                                       shared between JVMs (CDS and AOT archives, libraries) count once
  memory.idle-<mark>.processes         processes in the tree (engine, warm compiler, test JVMs, daemon)
  memory.idle-<mark>.heap-committed    the engine's committed Java heap (jstat; jk only)
  memory.idle-<mark>.metaspace         the engine's committed metaspace (jstat; jk only)

Marks are 1 and 5 minutes; the night tier adds 30. Maven has no daemon and holds nothing. A sample
is valid only while the host has no memory pressure: reclaim under pressure shrinks a tree for reasons that
have nothing to do with it, so a mark taken under pressure is `noisy`. "No pressure" is memory PSI
`some` avg60 at most PSI_LIMIT: a WSL host is never at exactly 0.
"""

from __future__ import annotations

import os
import subprocess
import sys
import time
from importlib.machinery import SourceFileLoader
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import _petclinic as pc  # noqa: E402
import benchtools  # noqa: E402

TIERS = ("fast", "night", "release")
MEASURE = Path(__file__).resolve().parent.parent / "wall" / "measure"
MARKS = {"fast": (1, 5), "night": (1, 5, 30), "release": (1, 5, 30)}
MiB = 1 << 20
# (counter, unit, band, slack, tools)
COUNTERS = {
    "pss": ("bytes", 0.05, 4 * MiB, ("jk", "gradle")),
    "processes": ("processes", 0.0, 0, ("jk", "gradle")),
    "heap-committed": ("bytes", 0.05, 2 * MiB, ("jk",)),
    "metaspace": ("bytes", 0.05, 2 * MiB, ("jk",)),
}
TARGETS = {(5, "pss"): 120 * MiB, (30, "pss"): 60 * MiB}
# Memory PSI `some` avg60 (%) above which a mark is noisy. A WSL host idles at a few hundredths.
PSI_LIMIT = 0.5


def children() -> dict[int, list[int]]:
    kids: dict[int, list[int]] = {}
    for entry in os.listdir("/proc"):
        if not entry.isdigit():
            continue
        try:
            ppid = int(Path(f"/proc/{entry}/stat").read_text().rsplit(")", 1)[1].split()[1])
        except (OSError, IndexError, ValueError):
            continue
        kids.setdefault(ppid, []).append(int(entry))
    return kids


def tree(roots: list[int]) -> list[int]:
    kids, seen, todo = children(), set(), list(roots)
    while todo:
        pid = todo.pop()
        if pid not in seen:
            seen.add(pid)
            todo += kids.get(pid, [])
    return sorted(seen)


def pss(pid: int) -> int:
    try:
        for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines():
            if line.startswith("Pss:"):
                return int(line.split()[1]) * 1024
    except OSError:
        pass
    return 0


def jstat(pid: int) -> dict[str, int]:
    """Committed heap and metaspace in bytes, from the JVM's perf data (no attach)."""
    java = Path(os.readlink(f"/proc/{pid}/exe")).parent
    res = subprocess.run([str(java / "jstat"), "-gc", str(pid)], capture_output=True, text=True)
    lines = res.stdout.split("\n")
    if res.returncode or len(lines) < 2:
        return {}
    cols = dict(zip(lines[0].split(), lines[1].split()))
    kib = lambda *names: int(sum(float(cols[n]) for n in names) * 1024)
    return {"heap-committed": kib("S0C", "S1C", "EC", "OC"), "metaspace": kib("MC")}


def sample(roots: list[int], engine: int | None) -> dict:
    pids = tree(roots)
    out = {"pss": sum(pss(p) for p in pids), "processes": len(pids), "memory-psi-avg60": benchtools.conditions().get("memory-psi-avg60", 0.0)}
    if engine:
        out.update(jstat(engine))
    return out


def gradle_side(home: Path, log: Path):
    """The wall harness's Gradle working copy built and tested; (daemon pids, version) or None."""
    w = SourceFileLoader("wall_measure", str(MEASURE)).load_module()
    try:
        w.PINNED = benchtools.resolve(allow_stale=True, need=("gradle",))
    except SystemExit as e:
        return None, f"gradle unavailable: {e}", w
    w.GRADLE = str(w.PINNED.gradle_bin)
    repo = w.clone()
    java_home, _ = benchtools.java_for_project(repo, "gradle")
    w.JAVA_FOR["gradle"] = {"JAVA_HOME": str(java_home), "PATH": f"{java_home / 'bin'}{os.pathsep}{os.environ.get('PATH', '')}"}
    work = w.working_copy("gradle", repo, log)
    subprocess.run([w.GRADLE, "--stop"], env=w.tool_env([w.GRADLE]), capture_output=True)
    for cmd in (w.build_cmd("gradle"), w.test_cmd("gradle")):
        res = subprocess.run(cmd, cwd=work, env=w.tool_env(cmd), capture_output=True, text=True)
        if res.returncode:
            return None, f"gradle failed: {(res.stdout + res.stderr)[-300:]}", w
    procs = w.scan_procs()
    return sorted(w.resident_roots("gradle", procs)), f"Gradle {w.PINNED.gradle_version}", w


def measure(ctx) -> list[dict]:
    marks = MARKS.get(ctx.tier, MARKS["fast"])
    if not any(ctx.wants(f"memory.idle-{m}m.{c}") for m in marks for c in COUNTERS):
        return []
    state = ctx.home / "memory-state"
    env = pc.engine_env(state, jk=ctx.jk)
    pc.stop(ctx.jk, state)
    work = pc.working_copy(ctx.home, ctx.jk, state, f"{pc.PROJECT}-memory")
    pc.settle(work, ctx.jk, env)
    res = subprocess.run([ctx.jk, "test", "--no-ansi"], cwd=work, env=env, capture_output=True)
    if res.returncode:
        ctx.skipped.append(f"memory [jk]: jk test failed (exit {res.returncode})")
        return []
    jk_t0 = time.monotonic()
    engine = pc.engine_pid(state)
    sides = {"jk": (lambda: pc.engine_tree(state), engine, jk_t0)}
    roots, gradle_version, w = gradle_side(ctx.home, ctx.out / "memory-gradle.log")
    if roots is None:
        ctx.skipped.append(f"memory [gradle]: {gradle_version}")
    else:
        sides["gradle"] = (lambda: sorted(w.resident_roots("gradle", w.scan_procs())), None, time.monotonic())
    versions = {"jk": pc.version(ctx.jk), "gradle": gradle_version}
    samples: dict[tuple[str, int], dict] = {}
    for mark in marks:
        for tool, (roots_of, engine_pid, t0) in sides.items():
            wait = t0 + mark * 60 - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            samples[(tool, mark)] = sample(roots_of(), engine_pid)
    pc.stop(ctx.jk, state)
    if "gradle" in sides:
        subprocess.run([w.GRADLE, "--stop"], env=w.tool_env([w.GRADLE]), capture_output=True)
    rows = []
    for (tool, mark), s in samples.items():
        for counter, (unit, band, slack, tools) in COUNTERS.items():
            subject = f"memory.idle-{mark}m.{counter}"
            if tool not in tools or counter not in s or not ctx.wants(subject):
                continue
            row = {"dim": "memory", "subject": subject, "project": pc.PROJECT, "tool": tool, "kind": "count",
                   "value": s[counter], "unit": unit, "band": band, "slack": slack, "runs": 1,
                   "target": TARGETS.get((mark, counter)) if tool == "jk" else None, "tool-version": versions[tool]}
            if s["memory-psi-avg60"] > PSI_LIMIT:
                row["noisy"] = f"memory PSI avg60 {s['memory-psi-avg60']:g} at the mark"
            rows.append(row)
    return rows
