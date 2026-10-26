"""Published seconds and memory: jk, Gradle and Maven on the pinned spring-petclinic (bench/wall/measure).

Night and release tiers only. The scoreboard runs this dimension behind its quiet gate and
calibrates the host before and after, so a row taken while the host was busy is `noisy` and never
banked. Subjects, per scenario of the harness (clean, rebuild, noop, edit, test, idle):

  wall.<scenario>            median seconds of the timed runs (p90 beside it)
  wall.<scenario>.peak-rss   peak summed RSS of the tool's process tree
  wall.idle-rss              the tool's residents a minute after a test run, nothing running

jk rows ratchet (15% above fails, 5% below is banked); Gradle and Maven rows record what those
tools do today. `render` publishes each tool's latest measurement, never jk's banked best, into
three kinds of generated block: the tables (`wall-table`), the comparison sentences
(`wall-claims`) and the website's proof strip (`wall-strip`). No other text may compare speed or
memory against another tool; the `speed-claims-are-generated` guard holds the docs to that.
"""

from __future__ import annotations

import html
import json
import os
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import benchtools  # noqa: E402

TIERS = ("night", "release")
KIND = "absolute-wall"
JK_TREE = Path(__file__).resolve().parent.parent.parent
MEASURE = JK_TREE / "bench" / "wall" / "measure"
ENGINE_CONFIG = JK_TREE / "shared" / "core" / "src" / "main" / "java" / "cc" / "jumpkick" / "config" / "JkEngineConfig.java"
TABLE_DOCS = [JK_TREE / "docs" / "user" / "performance.md", JK_TREE / "docs" / "user" / "why.md"]
# Each doc that carries the comparison sentences, and its link to the table page.
CLAIM_DOCS = {
    JK_TREE / "README.md": "docs/user/performance.md",
    JK_TREE / "docs" / "user" / "why.md": "performance.md",
    JK_TREE / "docs" / "user" / "comparison.md": "performance.md",
    JK_TREE / "docs" / "user" / "performance.md": "#the-table",
}
SITE = JK_TREE / "hosting" / "public" / "index.html"
SITE_LINK = "https://github.com/JumpKickOSS/jk/blob/main/docs/user/performance.md"
PROJECT = "spring-petclinic"
TOOLS = ("jk", "gradle", "maven")
SCENARIOS = ("clean", "rebuild", "noop", "edit", "test")
BAND = 0.15
TARGETS = {("jk", "edit"): 0.30, ("jk", "noop"): 0.10}


def default_heap_mb() -> int:
    """The engine's default heap cap, read from the tree so the harness never restates it."""
    m = re.search(r"DEFAULT_MAX_HEAP_MB\s*=\s*(\d+)", ENGINE_CONFIG.read_text(encoding="utf-8"))
    if not m:
        raise SystemExit(f"wall: no DEFAULT_MAX_HEAP_MB in {ENGINE_CONFIG}")
    return int(m.group(1))


def measure(ctx) -> list[dict]:
    out = ctx.out / "wall"
    # A private engine started fresh at the default heap: the user's engine carries whatever it built
    # before, and a host's config.toml may raise a cap that a user who never touched it does not get.
    state = ctx.home / "wall-state"
    env = dict(os.environ, JK_BIN=ctx.jk, JK_STATE_DIR=str(state), JK_ENGINE_MAX_HEAP_MB=str(default_heap_mb()))
    env.pop("CI", None)
    subprocess.run([ctx.jk, "engine", "stop"], env=env, capture_output=True)
    scenarios = [s for s in SCENARIOS if ctx.wants(f"wall.{s}") or ctx.wants(f"wall.{s}.peak-rss")]
    if ctx.wants("wall.idle-rss"):
        scenarios.append("idle")
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
        common = {"dim": "wall", "project": PROJECT, "tool": r["tool"], "kind": KIND, "runs": r["runs"],
                  "band": BAND, "tool-version": r["tool_version"], "host": benchtools.host_summary(),
                  "conditions": r.get("load", ""), "command": r.get("command", "")}
        if r["scenario"] == "idle":
            if "idle_rss_mb" not in r:
                ctx.skipped.append(f"wall.idle-rss [{r['tool']}]: {r.get('status') or 'the test run failed'}")
                continue
            rows.append({**common, "subject": "wall.idle-rss", "value": r["idle_rss_mb"] << 20, "unit": "bytes",
                         "settle-s": r["settle_s"], "root-rss": r["idle_root_rss_mb"] << 20})
            continue
        if "median_s" not in r:
            ctx.skipped.append(f"wall.{r['scenario']} [{r['tool']}]: {r.get('status') or 'every run failed'}")
            continue
        rows.append({**common, "subject": f"wall.{r['scenario']}", "value": round(r["median_s"], 2), "unit": "s",
                     "p90-s": round(r["p90_s"], 2), "target": TARGETS.get((r["tool"], r["scenario"]))})
        rows.append({**common, "subject": f"wall.{r['scenario']}.peak-rss", "value": r["peak_rss_mb"] << 20,
                     "unit": "bytes"})
    return rows


# --------------------------------------------------------------------------- what the docs publish

LABELS = {"clean": "Clean build", "rebuild": "Warm rebuild", "noop": "No-op", "edit": "One-file edit", "test": "Test run"}
NAMES = {"jk": "jk", "gradle": "Gradle", "maven": "Maven"}


def pub(row: dict, field: str):
    """The latest measurement of a field: a jk row keeps it beside the ratchet's banked best."""
    return row.get(f"latest-{field}", row.get(field))


def mib(row: dict) -> int:
    return int(pub(row, "value")) >> 20


def cells(rows: list[dict]) -> dict[tuple[str, str], dict]:
    return {(r["tool"], r["subject"]): r for r in rows}


def footer(rows: list[dict]) -> str:
    """Every number names its commit and conditions: the bankings, then each tool's conditions."""
    bankings: dict[tuple[str, str], set[str]] = {}
    for r in rows:
        bankings.setdefault((pub(r, "date"), pub(r, "commit")), set()).add(NAMES[r["tool"]])
    versions = "; ".join(sorted({pub(r, "tool-version") for r in rows}))
    walls = [r for r in rows if r.get("unit") == "s"]
    return (f"Measured on {rows[0].get('host', '')} (host `{rows[0]['host-id']}`); project "
            f"spring-projects/spring-petclinic; {versions}; {pub(walls[0] if walls else rows[0], 'runs')} timed "
            f"runs per cell. Banked "
            + "; ".join(f"{date} at jk tree {commit}" + ("" if len(bankings) == 1 else f" ({', '.join(sorted(t))})")
                        for (date, commit), t in sorted(bankings.items()))
            + ". Conditions: "
            + "; ".join(f"{NAMES[t]} {c}" for t in TOOLS
                        if (c := next((pub(r, "conditions") for r in walls if r["tool"] == t), None)))
            + ".")


def table(rows: list[dict]) -> str:
    cell = cells(rows)
    lines = ["| Scenario | " + " | ".join(f"{NAMES[t]} median / p90" for t in TOOLS) + " |", "|---|" + "---:|" * len(TOOLS)]
    for s in SCENARIOS:
        shown = [f"{pub(c, 'value'):.2f} s / {pub(c, 'p90-s'):.2f} s" if (c := cell.get((t, f"wall.{s}"))) else "—"
                 for t in TOOLS]
        lines.append(f"| {LABELS[s]} | " + " | ".join(shown) + " |")
    lines += ["", "| Scenario | " + " | ".join(f"{NAMES[t]} peak RSS" for t in TOOLS) + " |", "|---|" + "---:|" * len(TOOLS)]
    for s in SCENARIOS:
        shown = [f"{mib(c):,} MiB" if (c := cell.get((t, f"wall.{s}.peak-rss"))) else "—" for t in TOOLS]
        lines.append(f"| {LABELS[s]} | " + " | ".join(shown) + " |")
    shown = [f"{mib(c):,} MiB" if (c := cell.get((t, "wall.idle-rss"))) else "—" for t in TOOLS]
    lines.append("| Idle, a minute after the test run | " + " | ".join(shown) + " |")
    lines += ["", footer(rows)]
    return "\n".join(lines)


def _join(parts: list[str]) -> str:
    return parts[0] if len(parts) == 1 else ", ".join(parts[:-1]) + " and " + parts[-1]


def split(cell: dict, other: str, memory: bool) -> tuple[list[str], list[str]]:
    """(rows where jk is lower than `other`, rows where it is not), each with both numbers."""
    wins, losses = [], []
    for s in SCENARIOS:
        subject = f"wall.{s}.peak-rss" if memory else f"wall.{s}"
        j, o = cell.get(("jk", subject)), cell.get((other, subject))
        if not (j and o):
            continue
        if memory:
            pair, lower = f"{LABELS[s].lower()} ({mib(j):,} MiB against {mib(o):,})", mib(j) < mib(o)
        else:
            jv, ov = pub(j, "value"), pub(o, "value")
            pair, lower = f"{LABELS[s].lower()} ({jv:.2f} s against {ov:.2f} s)", jv < ov
        (wins if lower else losses).append(pair)
    return wins, losses


def _rows(pairs: list[str]) -> str:
    return _join([p.split(" (")[0] for p in pairs])


def claims(rows: list[dict], link: str) -> str:
    """The comparison sentences, worded from the numbers: a loss is said as plainly as a win."""
    cell = cells(rows)
    speed = []
    for other in ("gradle", "maven"):
        wins, losses = split(cell, other, memory=False)
        name = NAMES[other]
        if wins and not losses:
            speed.append(f"faster than {name} on every row: {_join(wins)}")
        elif wins:
            speed.append(f"faster than {name} on {_join(wins)}, and slower on {_join(losses)}")
        elif losses:
            speed.append(f"slower than {name} on every row: {_join(losses)}")
    paragraphs = []
    if speed:
        paragraphs.append(f"On [spring-petclinic]({link}), jk is " + ". It is ".join(speed) + ".")

    peak, peak_losses = [], False
    for other in ("gradle", "maven"):
        wins, losses = split(cell, other, memory=True)
        peak_losses |= bool(losses)
        name = NAMES[other]
        if wins and not losses:
            peak.append(f"lower than {name}'s on every row")
        elif wins:
            peak.append(f"lower than {name}'s on {_rows(wins)} and higher on {_rows(losses)}")
        elif losses:
            peak.append(f"higher than {name}'s on every row")
    idle = {t: mib(c) for t in TOOLS if (c := cell.get((t, "wall.idle-rss")))}
    resident = ""
    if "jk" in idle:
        j = cell[("jk", "wall.idle-rss")]
        engine = int(pub(j, "root-rss") or 0) >> 20
        resident = (f"Between builds jk keeps {idle['jk']:,} MiB resident"
                    + (f": {engine:,} MiB for the engine, whose heap is capped at {default_heap_mb()} MiB and "
                       f"trimmed back after every job, and the rest for warm compilers that exit after 30 idle "
                       f"minutes or as soon as the host runs short of memory" if engine else "")
                    + ". One engine serves every project on the machine.")
        if "gradle" in idle:
            ratio = idle["gradle"] / idle["jk"] if idle["jk"] else 0
            resident += (f" Gradle keeps {idle['gradle']:,} MiB"
                         + (f", {ratio:.1f}× as much" if ratio >= 1.5 else "")
                         + ": its daemon, which idles for three hours by default, and its workers.")
        if "maven" in idle:
            noop = cell.get(("maven", "wall.noop"))
            resident += (" Maven keeps nothing resident and starts a fresh JVM for every build instead"
                         + (f" ({pub(noop, 'value'):.2f} s for a no-op)" if noop else "") + ".")
    during = ("Peak whole-tree RSS during a run is " + "; it is ".join(peak) + ".") if peak else ""
    # A peak that loses anywhere leads with what the machine keeps between builds, then states the peak plainly.
    memory = [resident, during] if peak_losses else [during, resident]
    paragraphs.append(" ".join(p for p in memory if p))
    return "\n\n".join(p for p in paragraphs if p)


def strip(rows: list[dict]) -> str:
    """The website's proof tiles: jk's number large, the other tools' beside it."""
    cell = cells(rows)
    tiles = []
    for s in ("noop", "edit", "rebuild"):
        j = cell.get(("jk", f"wall.{s}"))
        if not j:
            continue
        jv = pub(j, "value")
        others = [(NAMES[t], pub(c, "value")) for t in ("gradle", "maven") if (c := cell.get((t, f"wall.{s}")))]
        lead = ""
        if others and jv > 0:
            name, best = min(others, key=lambda o: o[1])
            if best / jv >= 2:
                lead = f"{best / jv:.0f}× faster than {name}"
        tiles.append((LABELS[s], f"{jv:.2f} s", " · ".join(f"{n} {v:.2f} s" for n, v in others), lead))
    idle = {t: mib(c) for t in TOOLS if (c := cell.get((t, "wall.idle-rss")))}
    if "jk" in idle:
        vs = []
        if "gradle" in idle:
            vs.append(f"Gradle {idle['gradle']:,} MiB, its daemon up 3 hours")
        if "maven" in idle:
            vs.append("Maven none: a cold JVM every build")
        tiles.append(("Resident between builds", f"{idle['jk']:,} MiB", " · ".join(vs),
                      f"one engine for every project, heap capped at {default_heap_mb()} MiB"))
    versions = "; ".join(sorted({pub(r, "tool-version") for r in rows}))
    out = ['<div class="proof-strip">']
    for label, value, vs, lead in tiles:
        out += ['  <div class="proof-tile">',
                f'    <div class="proof-label">{html.escape(label)}</div>',
                f'    <div class="proof-value">{html.escape(value)}</div>',
                f'    <div class="proof-vs">{html.escape(vs)}</div>']
        if lead:
            out.append(f'    <div class="proof-lead">{html.escape(lead)}</div>')
        out.append("  </div>")
    out += ["</div>",
            f'<p class="proof-note">spring-petclinic, the same work for every tool; {html.escape(versions)}. '
            f'<a href="{SITE_LINK}" target="_blank" rel="noopener noreferrer">How it is measured, and where jk loses →</a></p>']
    return "\n".join(out)


def _splice(doc: Path, name: str, text: str) -> None:
    begin, end = f"<!-- wall-{name}:begin -->", f"<!-- wall-{name}:end -->"
    body = doc.read_text(encoding="utf-8")
    if begin not in body or end not in body:
        print(f"{doc}: no wall-{name} markers, left alone", file=sys.stderr)
        return
    pre, rest = body.split(begin, 1)
    _, post = rest.split(end, 1)
    doc.write_text(f"{pre}{begin}\n{text}\n{end}{post}", encoding="utf-8")
    print(f"rendered wall-{name} into {doc.relative_to(JK_TREE)}")


def render(board: list[dict], host: str) -> None:
    rows = [r for r in board if r.get("dim") == "wall"]
    hosts = {r["host-id"] for r in rows}
    if not rows:
        for doc in TABLE_DOCS:
            _splice(doc, "table", "_No banked wall yet: run `bench/scoreboard --tier night`._")
        return
    latest = host if host in hosts else max(hosts, key=lambda h: max(pub(r, "date") for r in rows if r["host-id"] == h))
    mine = [r for r in rows if r["host-id"] == latest]
    for doc in TABLE_DOCS:
        _splice(doc, "table", table(mine))
    for doc, link in CLAIM_DOCS.items():
        _splice(doc, "claims", claims(mine, link))
    _splice(SITE, "strip", strip(mine))
