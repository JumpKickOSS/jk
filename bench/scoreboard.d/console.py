"""The dashboard console on a warm engine, in headless Chrome: its first meaningful paint, how fast
an engine event and a decision reach it, and how fast the supervisor checklist's answers are on
screen. PRD agent-console.md § Measures.

Night and release tiers only (behind the quiet gate): every subject is a wall. Subjects:

  console.paint.p50      median first paint of the timed loads, ms, after one warm-up load
                         (bench/console/paint.mjs: the page's `data-console-painted-ms`). Target 500.
  console.event.p95      95th percentile of an MCP `find` call's `action` frame, engine publish (the
                         call's end) → painted on the selected session's timeline, ms
                         (bench/console/live.mjs: the page's `jk-console-event` marks). Target 250.
  console.decision.p50   median of a decision's opening → its Needs-you row painted, ms; the
                         decision is a confirmed `disk clean` from a client that declared form
                         elicitation, declined once timed. Target 2000.
  console.checklist.max  the slowest of the supervisor checklist's five answers (goal, progress,
                         changes, needs you, last action), each the median over fresh loads of when
                         it is on screen, ms since navigation start. The PRD's checklist is timed
                         with a stranger; this is the floor under that, the time the screen takes
                         to hold every answer. Target 5000.

The engine is a private one on its own state dir, store and cache (nothing shared with the user's
home), serving this tree's dashboard (`JK_HTTP_WEB_ROOT`) on a free port, so the rows measure the
console the tree ships; `JK_HOME` passes through, so a scratch home's jk serves it, and jk's own
jars are copied from that home's store so the runs build. A small generated project gives it a
shell session with a few runs to paint, and live.mjs an MCP session.
Needs Google Chrome or Chromium and the Node.js `clients/web` pins (run through `jk node exec`).
"""

from __future__ import annotations

import json
import math
import os
import shutil
import statistics
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import benchtools  # noqa: E402

TIERS = ("night", "release")
KIND = "absolute-wall"
JK_TREE = Path(__file__).resolve().parent.parent.parent
WEB = JK_TREE / "clients" / "web" / "src" / "main" / "resources" / "web"
PAINT = JK_TREE / "bench" / "console" / "paint.mjs"
LIVE = JK_TREE / "bench" / "console" / "live.mjs"
RUNS = 15
EVENTS = 20
DECISIONS = 5
PASSES = 5
BAND = 0.15
SLACK_MS = 10  # tens of ms move by a few between loads; the band alone would fail on a frame of jitter
TARGETS = {"console.paint.p50": 500, "console.event.p95": 250, "console.decision.p50": 2000,
           "console.checklist.max": 5000}
CHROMES = ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser")
# What the private engine inherits: a scratch home's jk and its self-update policy.
PASS_THROUGH = ("JK_HOME", "JK_SELF_UPDATE")


def project(root: Path) -> Path:
    """A one-module project with no dependencies: nothing to download, a few seconds to build."""
    src = root / "src" / "main" / "java" / "demo"
    src.mkdir(parents=True, exist_ok=True)
    (root / "jk.toml").write_text('group = "dev.scoreboard"\nname = "console"\nversion = "0.1.0"\njava = 25\n')
    for i in range(40):
        (src / f"C{i}.java").write_text(f"package demo;\npublic class C{i} {{ static int f(int x) {{ return x * {i}; }} }}\n")
    return root


def seed_store(store: Path) -> None:
    """jk's own jars (the compiler, the test runner) from the installing home's `jk-local` repository,
    copied, so the private store builds without the user's store and leaves it untouched."""
    src = benchtools.jk_home() / "store" / "repos" / "jk-local"
    if src.is_dir():
        shutil.copytree(src, store / "repos" / "jk-local")


def p95(samples: list[float]) -> float:
    ordered = sorted(samples)
    return ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)]


def row(subject: str, value: float, samples: list, **extra) -> dict:
    return {"dim": "console", "subject": subject, "project": "generated", "tool": "jk", "kind": KIND,
            "value": round(value, 1), "unit": "ms", "band": BAND, "slack": SLACK_MS, "runs": len(samples),
            "target": TARGETS[subject], "samples": samples, **extra}


def node(ctx, script: Path, *args: str) -> dict | None:
    """Run a harness script under the pinned Node.js; its last stdout line as JSON, None when it failed."""
    res = subprocess.run([ctx.jk, "node", "exec", "--", "node", str(script), *args],
                         cwd=JK_TREE / "clients" / "web", capture_output=True, text=True, timeout=900)
    if res.returncode:
        ctx.skipped.append(f"console: {script.name} exited {res.returncode}: {res.stderr.strip()[-400:]}")
        return None
    return json.loads(res.stdout.strip().splitlines()[-1])


def engine_url(state: Path) -> tuple[str, str]:
    """The private engine's dashboard URL and bearer token, from its state dir."""
    engine = state / "engine"
    for _ in range(100):
        urls, tokens = list(engine.glob("*.http")), list(engine.glob("*.http-token"))
        if urls and tokens:
            return urls[0].read_text().strip().rstrip("/"), tokens[0].read_text().strip()
        time.sleep(0.1)
    raise RuntimeError(f"no dashboard URL under {engine}")


def measure(ctx) -> list[dict]:
    paint = ctx.wants("console.paint.p50")
    live = any(ctx.wants(s) for s in TARGETS if s != "console.paint.p50")
    if not paint and not live:
        return []
    chrome = next((shutil.which(c) for c in CHROMES if shutil.which(c)), None)
    if chrome is None:
        ctx.skipped.append("console: no Chrome or Chromium on PATH")
        return []
    home = ctx.home / "console"
    shutil.rmtree(home, ignore_errors=True)
    work = project(home / "work")
    seed_store(home / "store")
    env = {k: v for k, v in os.environ.items() if not k.startswith("JK_") or k in PASS_THROUGH}
    env.setdefault("JK_SELF_UPDATE", "never")
    env.update({
        "JK_STATE_DIR": str(home / "state"),
        "JK_STORE_DIR": str(home / "store"),
        "JK_CACHE_DIR": str(home / "cache"),
        "JK_HTTP_ENABLED": "1",
        "JK_HTTP_PORT": "0",
        "JK_HTTP_WEB_ROOT": str(WEB),
        "JK_UI_ASK": "quiet",  # a decision the harness leaves open notifies nobody
    })
    rows = []
    try:
        for _ in range(3):  # a shell session with runs to paint, and a warm engine
            subprocess.run([ctx.jk, "build", "--no-ansi"], cwd=work, env=env, capture_output=True)
        url, token = engine_url(home / "state")
        dashboard = f"{url}/#t={token}"
        if paint:
            out = node(ctx, PAINT, chrome, dashboard, str(RUNS))
            if out:
                rows.append(row("console.paint.p50", statistics.median(out["samples"]), out["samples"]))
        if live:
            out = node(ctx, LIVE, chrome, dashboard, str(work), str(EVENTS), str(DECISIONS), str(PASSES))
            if out:
                rows.append(row("console.event.p95", p95(out["event"]), out["event"]))
                rows.append(row("console.decision.p50", statistics.median(out["decision"]), out["decision"]))
                medians = {q: statistics.median(p[q] for p in out["checklist"]) for q in out["checklist"][0]}
                slowest = max(medians, key=medians.get)
                rows.append(row("console.checklist.max", medians[slowest],
                                [max(p.values()) for p in out["checklist"]], slowest=slowest))
    finally:
        subprocess.run([ctx.jk, "engine", "stop"], env=env, capture_output=True)
    return [r for r in rows if ctx.wants(r["subject"])]
