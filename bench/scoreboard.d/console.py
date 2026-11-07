"""The dashboard console's first meaningful paint on a warm engine: headless Chrome loads the
landing page and reads the mark the page sets once the sessions rail and the selected episode are
on screen (bench/console/paint.mjs). PRD target: 500 ms.

Night and release tiers only (behind the quiet gate): a paint is a wall. Subject:

  console.paint.p50     median of the timed loads, milliseconds, after one warm-up load

The engine is a private one on its own state dir, store and cache (nothing shared with the user's
home), serving this tree's dashboard (`JK_HTTP_WEB_ROOT`), so the row measures the console the tree
ships. A small generated project gives it a shell session with a few runs to paint. Needs Google
Chrome or Chromium and the Node.js `clients/web` pins (run through `jk node exec`).
"""

from __future__ import annotations

import json
import os
import shutil
import socket
import statistics
import subprocess
from pathlib import Path

TIERS = ("night", "release")
KIND = "absolute-wall"
JK_TREE = Path(__file__).resolve().parent.parent.parent
WEB = JK_TREE / "clients" / "web" / "src" / "main" / "resources" / "web"
PAINT = JK_TREE / "bench" / "console" / "paint.mjs"
RUNS = 15
BAND = 0.15
TARGET = 500
CHROMES = ("google-chrome", "google-chrome-stable", "chromium", "chromium-browser")


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def project(root: Path) -> Path:
    """A one-module project with no dependencies: nothing to download, a few seconds to build."""
    src = root / "src" / "main" / "java" / "demo"
    src.mkdir(parents=True, exist_ok=True)
    (root / "jk.toml").write_text('group = "dev.scoreboard"\nname = "console"\nversion = "0.1.0"\njava = 25\n')
    for i in range(40):
        (src / f"C{i}.java").write_text(f"package demo;\npublic class C{i} {{ static int f(int x) {{ return x * {i}; }} }}\n")
    return root


def measure(ctx) -> list[dict]:
    if not ctx.wants("console.paint.p50"):
        return []
    chrome = next((shutil.which(c) for c in CHROMES if shutil.which(c)), None)
    if chrome is None:
        ctx.skipped.append("console: no Chrome or Chromium on PATH")
        return []
    home = ctx.home / "console"
    shutil.rmtree(home, ignore_errors=True)
    work = project(home / "work")
    port = free_port()
    env = {k: v for k, v in os.environ.items() if not k.startswith("JK_")}
    env.update({
        "JK_STATE_DIR": str(home / "state"),
        "JK_STORE_DIR": str(home / "store"),
        "JK_CACHE_DIR": str(home / "cache"),
        "JK_HTTP_ENABLED": "1",
        "JK_HTTP_PORT": str(port),
        "JK_HTTP_WEB_ROOT": str(WEB),
    })
    try:
        for _ in range(3):  # a shell session with runs to paint, and a warm engine
            subprocess.run([ctx.jk, "build", "--no-ansi"], cwd=work, env=env, capture_output=True)
        token = next((home / "state" / "engine").glob("*.http-token")).read_text().strip()
        res = subprocess.run(
            [ctx.jk, "node", "exec", "--", "node", str(PAINT), chrome, f"http://127.0.0.1:{port}/#t={token}", str(RUNS)],
            cwd=JK_TREE / "clients" / "web", capture_output=True, text=True, timeout=900)
        if res.returncode:
            ctx.skipped.append(f"console: paint.mjs exited {res.returncode}: {res.stderr.strip()[-400:]}")
            return []
        samples = json.loads(res.stdout.strip().splitlines()[-1])["samples"]
    finally:
        subprocess.run([ctx.jk, "engine", "stop"], env=env, capture_output=True)
    return [{"dim": "console", "subject": "console.paint.p50", "project": "generated", "tool": "jk", "kind": KIND,
             "value": round(statistics.median(samples), 1), "unit": "ms", "band": BAND, "runs": len(samples),
             "target": TARGET, "samples": samples}]
