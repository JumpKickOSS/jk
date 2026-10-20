"""Published seconds: jk, Gradle and Maven on the pinned spring-petclinic (bench/wall/measure).

Night and release tiers only. The scoreboard runs this dimension behind its quiet gate and
calibrates the host before and after, so a row taken while the host was busy is `noisy` and never
banked. Subjects, per scenario of the harness (clean, rebuild, noop, edit, test):

  wall.<scenario>            median seconds of the timed runs (p90 beside it)
  wall.<scenario>.peak-rss   peak summed RSS of the tool's process tree

jk rows ratchet (15% above fails, 5% below is banked); Gradle and Maven rows record what those
tools do today. `render` writes the tables in docs/user/performance.md and why.md from the banked
rows of the most recently measured host.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import benchtools  # noqa: E402

TIERS = ("night", "release")
KIND = "absolute-wall"
JK_TREE = Path(__file__).resolve().parent.parent.parent
MEASURE = JK_TREE / "bench" / "wall" / "measure"
DOCS = [JK_TREE / "docs" / "user" / "performance.md", JK_TREE / "docs" / "user" / "why.md"]
PROJECT = "spring-petclinic"
TOOLS = ("jk", "gradle", "maven")
SCENARIOS = ("clean", "rebuild", "noop", "edit", "test")
BAND = 0.15
TARGETS = {("jk", "edit"): 0.30, ("jk", "noop"): 0.10}


def measure(ctx) -> list[dict]:
    out = ctx.out / "wall"
    # A private engine started fresh: the user's engine carries whatever it built before, and its
    # heap is the peak-RSS column's biggest term.
    state = ctx.home / "wall-state"
    env = dict(os.environ, JK_BIN=ctx.jk, JK_STATE_DIR=str(state))
    subprocess.run([ctx.jk, "engine", "stop"], env=env, capture_output=True)
    scenarios = [s for s in SCENARIOS if ctx.wants(f"wall.{s}") or ctx.wants(f"wall.{s}.peak-rss")]
    if not scenarios:
        return []
    cmd = [sys.executable, str(MEASURE), "--out", str(out), "--runs", str(ctx.runs)]
    for tool in TOOLS:
        if ctx.tools is None or tool in ctx.tools:
            cmd += ["--tool", tool]
    for s in scenarios:
        cmd += ["--scenario", s]
    subprocess.run(cmd, env=env, check=False)
    subprocess.run([ctx.jk, "engine", "stop"], env=env, capture_output=True)
    rows = []
    rows_file = out / "rows.jsonl"
    if not rows_file.is_file():
        raise SystemExit(f"wall: the harness wrote no rows; see {out}")
    for line in rows_file.read_text().splitlines():
        r = json.loads(line)
        if "median_s" not in r:
            ctx.skipped.append(f"wall.{r['scenario']} [{r['tool']}]: {r.get('status') or 'every run failed'}")
            continue
        common = {"dim": "wall", "project": PROJECT, "tool": r["tool"], "kind": KIND, "runs": r["runs"],
                  "band": BAND, "tool-version": r["tool_version"], "host": benchtools.host_summary(),
                  "conditions": r.get("load", ""), "command": r.get("command", "")}
        rows.append({**common, "subject": f"wall.{r['scenario']}", "value": round(r["median_s"], 2), "unit": "s",
                     "p90-s": round(r["p90_s"], 2), "target": TARGETS.get((r["tool"], r["scenario"]))})
        rows.append({**common, "subject": f"wall.{r['scenario']}.peak-rss", "value": r["peak_rss_mb"] << 20,
                     "unit": "bytes"})
    return rows


# --------------------------------------------------------------------------- the docs tables

LABELS = {"clean": "Clean build", "rebuild": "Warm rebuild", "noop": "No-op", "edit": "One-file edit", "test": "Test run"}
NAMES = {"jk": "jk", "gradle": "Gradle", "maven": "Maven"}


def table(rows: list[dict]) -> str:
    cell = {(r["tool"], r["subject"]): r for r in rows}
    lines = ["| Scenario | " + " | ".join(f"{NAMES[t]} median / p90" for t in TOOLS) + " |", "|---|" + "---:|" * len(TOOLS)]
    for s in SCENARIOS:
        cells = [f"{c['value']:.2f} s / {c['p90-s']:.2f} s" if (c := cell.get((t, f"wall.{s}"))) else "—" for t in TOOLS]
        lines.append(f"| {LABELS[s]} | " + " | ".join(cells) + " |")
    lines += ["", "| Scenario | " + " | ".join(f"{NAMES[t]} peak RSS" for t in TOOLS) + " |", "|---|" + "---:|" * len(TOOLS)]
    for s in SCENARIOS:
        cells = [f"{c['value'] >> 20:,} MiB" if (c := cell.get((t, f"wall.{s}.peak-rss"))) else "—" for t in TOOLS]
        lines.append(f"| {LABELS[s]} | " + " | ".join(cells) + " |")
    lines.append("")
    # Every number names its commit and conditions: the bankings, then each tool's conditions.
    walls = [r for r in rows if not r["subject"].endswith("peak-rss")]
    bankings: dict[tuple[str, str], set[str]] = {}
    for r in walls:
        bankings.setdefault((r["date"], r["commit"]), set()).add(NAMES[r["tool"]])
    versions = "; ".join(sorted({r["tool-version"] for r in rows}))
    lines.append(f"Measured on {rows[0].get('host', '')} (host `{rows[0]['host-id']}`); project "
                 f"spring-projects/spring-petclinic; {versions}; {rows[0]['runs']} timed runs per cell. Banked "
                 + "; ".join(f"{date} at jk tree {commit}" + ("" if len(bankings) == 1 else f" ({', '.join(sorted(t))})")
                             for (date, commit), t in sorted(bankings.items()))
                 + ". Conditions: "
                 + "; ".join(f"{NAMES[t]} {c}" for t in TOOLS
                             if (c := next((r.get("conditions") for r in walls if r["tool"] == t), None)))
                 + ".")
    return "\n".join(lines)


def render(board: list[dict], host: str) -> None:
    rows = [r for r in board if r.get("dim") == "wall"]
    hosts = {r["host-id"] for r in rows}
    if not rows:
        text = "_No banked wall yet: run `bench/scoreboard --tier night`._"
    else:
        latest = host if host in hosts else max(hosts, key=lambda h: max(r["date"] for r in rows if r["host-id"] == h))
        text = table([r for r in rows if r["host-id"] == latest])
    begin, end = "<!-- wall-table:begin -->", "<!-- wall-table:end -->"
    for doc in DOCS:
        body = doc.read_text()
        if begin not in body or end not in body:
            print(f"{doc}: no wall-table markers, left alone", file=sys.stderr)
            continue
        pre, rest = body.split(begin, 1)
        _, post = rest.split(end, 1)
        doc.write_text(f"{pre}{begin}\n{text}\n{end}{post}")
        print(f"rendered the wall table into {doc}")
