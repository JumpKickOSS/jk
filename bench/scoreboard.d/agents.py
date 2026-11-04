"""Agents and the code graph: how often each agent reaches for the graph, what that saves, and what jk's
MCP cards cost on every turn.

Two sources. The card cost is measured: `jk mcp` started on this tree (the cards do not depend on the
project), its
default and extended `tools/list` taken as JSON and sized at characters / 4 (the estimate the
retrieval bench uses). An agent's host resends the listed cards each turn, so this is the floor of
jk's per-turn overhead.

The agent rows are read from the checked-in change-task results (`bench/agent-loop/results/*/rows.jsonl`;
`change-tasks` runs them, on an idle host). A cell is one (driver, arm, task) on the driver's default
model, and it takes the newest series that has it: the newest result date, and within that date the
highest hundred of `--first-run` (each round on a new build or prescription numbers its runs from a
new hundred). Per driver and arm, over accepted runs:

  agents.<driver>.questions.non-graph-nav   share of navigation calls that were not graph calls
                                            (grep, glob and file reads over all three), median per
                                            caller/impact question, then the median of those
  agents.<driver>.questions.turns           per-question median turns, summed over the questions
  agents.<driver>.questions.tokens          the same for tokens (input, output, cache read and write)
  agents.<driver>.renames.turns / .tokens   the same over the method-rename tasks

`tool` is the arm (`jk`, `jk-nograph`), so jk without the graph is the comparison row beside each.
A subtype question is not counted: it is answered from declarations, and grep is as cheap there.
"""

from __future__ import annotations

import ast
import json
import re
import statistics
import sys
import tomllib
from pathlib import Path

TIERS = ("night", "release")

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "agent-loop"))

JK_TREE = Path(__file__).resolve().parent.parent.parent
RESULTS = JK_TREE / "bench" / "agent-loop" / "results"
PROJECT = "change-tasks"
# The share of navigation an agent still does by hand, and the turn and token sums, may drift by this
# much between result sets before the row fails: agent runs vary from one set to the next.
AGENT_BAND = 0.25
CARD_TARGET = {"default": 600, "extended": 2500}


def series(r: dict) -> tuple[str, int]:
    return str(r.get("date", "")), int(r.get("run") or 0) // 100


def cells() -> dict[tuple[str, str, str], list[dict]]:
    """Every (driver, arm, task) cell of the current tasks, from its newest series, every run kept."""
    sys.path.insert(0, str(RESULTS.parent))
    tasks = tomllib.loads((RESULTS.parent / "tasks.toml").read_text())["tasks"]
    models = _default_models()
    by: dict[tuple[str, str, str], dict[tuple[str, int], list[dict]]] = {}
    for f in sorted(RESULTS.glob("*/rows.jsonl")):
        for line in f.read_text().splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            # A row with no verdict is a run that never started (its baseline was red): not a series.
            if r.get("task") not in tasks or r.get("model") != models.get(r.get("driver")) or "accepted" not in r:
                continue
            r["kind"] = tasks[r["task"]].get("kind", r.get("kind", ""))
            r["results"] = f.parent.name
            by.setdefault((r["driver"], r["arm"], r["task"]), {}).setdefault(series(r), []).append(r)
    return {k: v[max(v)] for k, v in by.items()}


def _default_models() -> dict[str, str]:
    text = (RESULTS.parent / "change-tasks").read_text()
    start = text.index("DEFAULT_MODEL = ")
    return ast.literal_eval(text[start + len("DEFAULT_MODEL = "):text.index("}", start) + 1])


def _graph_question(task: str, kind: str) -> bool:
    return kind == "question" and "subtypes" not in task


def groups(by: dict[tuple[str, str, str], list[dict]]) -> dict[tuple[str, str, str], dict[str, list[dict]]]:
    """(driver, arm, group) to that group's cells by task, accepted runs only."""
    out: dict[tuple[str, str, str], dict[str, list[dict]]] = {}
    for (driver, arm, task), rs in by.items():
        ok = [r for r in rs if r.get("accepted")]
        if not ok:
            continue
        kind = rs[0]["kind"]
        group = "questions" if _graph_question(task, kind) else "renames" if kind.startswith("rename") else None
        if group:
            out.setdefault((driver, arm, group), {})[task] = ok
    return out


def agent_rows(by: dict[tuple[str, str, str], list[dict]]) -> list[dict]:
    found: list[dict] = []
    for (driver, arm, group), tasks in sorted(groups(by).items()):
        runs = min(len(rs) for rs in tasks.values())
        sums = {
            "turns": sum(statistics.median(r["turns"] for r in rs) for rs in tasks.values()),
            "tokens": sum(statistics.median(_known(rs, "tokens") or [0]) for rs in tasks.values()),
        }
        for counter in ("turns", "tokens"):
            found.append(row(f"agents.{driver}.{group}.{counter}", arm, sums[counter], counter, runs, len(tasks)))
        if group == "questions":
            share = statistics.median(
                statistics.median(1 - (r.get("graph_share") or 0.0) for r in rs) for rs in tasks.values())
            found.append(row(f"agents.{driver}.questions.non-graph-nav", arm, round(share, 3), "share", runs,
                             len(tasks)))
    return found


def row(subject: str, arm: str, value: float, unit: str, runs: int, tasks: int) -> dict:
    return {"dim": "agents", "subject": subject, "project": PROJECT, "tool": arm, "kind": "count",
            "value": value, "unit": unit, "band": AGENT_BAND, "runs": runs, "tasks": tasks}


def card_rows(ctx) -> list[dict]:
    from harnesslib.mcp import StdioMcp  # noqa: E402

    found = []
    for surface in ("default", "extended"):
        subject = f"mcp.cards.{surface}.tokens"
        if not ctx.wants(subject):
            continue
        client = StdioMcp(["jk", "-C", str(JK_TREE), "mcp"], cwd=JK_TREE)
        try:
            client.initialize()
            params = {"extended": True} if surface == "extended" else {}
            tools = client.request("tools/list", params).get("tools") or []
        finally:
            client.close()
        size = len(json.dumps(tools, separators=(",", ":"))) // 4
        found.append({"dim": "agents", "subject": subject, "project": "any", "tool": "jk",
                      "kind": "count", "value": size, "unit": "tokens", "band": 0.02, "runs": 1,
                      "target": CARD_TARGET[surface], "cards": len(tools)})
    return found


def measure(ctx) -> list[dict]:
    rows = card_rows(ctx)
    return rows + [r for r in agent_rows(cells()) if ctx.wants(r["subject"])]


# ---- docs ----------------------------------------------------------------------------------------

# User docs name agents by vendor and tier, never by product.
LABELS = {"claude-code": "Vendor A, mid-tier", "grok": "Vendor B, frontier", "agy": "Vendor C, fast",
          "cursor": "Vendor D", "muse": "Vendor E"}
QUESTIONS = JK_TREE / "bench" / "graph-retrieval" / "questions"
# Each doc that carries the agent sentences, and its link to the change-task bench.
CLAIM_DOCS = {
    JK_TREE / "README.md": "docs/user/why.md#asking-about-the-code",
    JK_TREE / "docs" / "user" / "why.md": "#asking-about-the-code",
    JK_TREE / "docs" / "user" / "graph.md": "why.md#asking-about-the-code",
    JK_TREE / "docs" / "user" / "comparison.md": "why.md#asking-about-the-code",
    JK_TREE / "hosting" / "public" / "index.html": None,
}


def _k(x: float | None) -> str:
    if x is None:
        return "n/a"
    if x >= 1e6:
        return f"{x / 1e6:.1f}M"
    return f"{x / 1e3:.0f}k" if x >= 1e3 else f"{x:.0f}"


def _n(x: float) -> str:
    return f"{x:.0f}" if float(x).is_integer() else f"{x:.1f}"


def _pair(by: dict, driver: str, kind: str) -> tuple[list[str], dict, dict] | None:
    """The tasks of `kind` that driver ran on both arms, and each arm's cells for them (all runs)."""
    def tasks(arm):
        return {t: rs for (d, a, t), rs in by.items() if d == driver and a == arm
                and (_graph_question(t, rs[0]["kind"]) if kind == "questions" else rs[0]["kind"].startswith("rename"))}
    jk, off = tasks("jk"), tasks("jk-nograph")
    both = sorted(set(jk) & set(off))
    return (both, jk, off) if both else None


def _known(rs: list[dict], key: str) -> list:
    """The runs' values of key, without the runs that never reported it (tokens of a killed agent)."""
    return [r[key] for r in rs if r.get(key) is not None]


def _sums(cells: dict, tasks: list[str]) -> dict:
    ok = {t: [r for r in cells[t] if r.get("accepted")] or cells[t] for t in tasks}

    def med(key: str) -> float | None:
        per_task = [_known(ok[t], key) for t in tasks]
        return None if not all(per_task) else sum(statistics.median(v) for v in per_task)

    return {"correct": sum(1 for t in tasks for r in cells[t] if r.get("accepted")),
            "runs": sum(len(cells[t]) for t in tasks), "turns": med("turns"), "tokens": med("tokens"),
            "wall": med("wall_s"), "share": statistics.median(
                statistics.median(r.get("graph_share") or 0.0 for r in ok[t]) for t in tasks)}


def comparison(by: dict, kind: str) -> list[dict]:
    out = []
    for driver in LABELS:
        p = _pair(by, driver, kind)
        if p:
            both, jk, off = p
            out.append({"driver": driver, "tasks": len(both), "jk": _sums(jk, both), "off": _sums(off, both),
                        "results": sorted({r["results"] for t in both for r in jk[t] + off[t]})})
    return out


def comparison_table(rows: list[dict], unit: str) -> str:
    lines = [f"| Agent ({unit}) | Correct, jk / without the graph | Turns | Tokens | Wall |",
             "|---|---|---|---|---|"]
    for r in rows:
        a, b = r["jk"], r["off"]
        lines.append(f"| {LABELS[r['driver']]} ({r['tasks']}) | {a['correct']} of {a['runs']} / {b['correct']} of "
                     f"{b['runs']} | {_n(a['turns'])} / {_n(b['turns'])} | {_k(a['tokens'])} / {_k(b['tokens'])} | "
                     f"{a['wall']:,.0f} / {b['wall']:,.0f} s |")
    sets = sorted({s for r in rows for s in r["results"]})
    lines += ["", "Medians per task, summed over the tasks each agent ran on both arms; results: "
              + ", ".join(f"`{s}`" for s in sets) + "."]
    return "\n".join(lines)


def _loc(repo: str) -> int | None:
    f = QUESTIONS / f"{repo}.json"
    return json.loads(f.read_text())["loc"] if f.is_file() else None


def curve(by: dict) -> list[dict]:
    tasks = tomllib.loads((RESULTS.parent / "tasks.toml").read_text())["tasks"]
    out = []
    for name, spec in tasks.items():
        if not spec.get("kind", "").startswith("rename") or _loc(spec["repo"]) is None:
            continue
        method = re.search(r"`([\w.]+)`", spec["prompt"])
        point = {"task": name, "repo": spec["repo"], "loc": _loc(spec["repo"]), "drivers": {},
                 "method": method.group(1) if method else name}
        for driver in ("claude-code", "cursor"):
            jk, off = by.get((driver, "jk", name)), by.get((driver, "jk-nograph", name))
            if jk and off:
                point["drivers"][driver] = (_sums({name: jk}, [name]), _sums({name: off}, [name]))
        if point["drivers"]:
            out.append(point)
    return sorted(out, key=lambda p: (p["loc"], p["task"]))


def curve_table(points: list[dict]) -> str:
    drivers = [d for d in ("claude-code", "cursor") if any(d in p["drivers"] for p in points)]
    head = "| Repository | Lines | Rename | " + " | ".join(f"{LABELS[d]}: turns · tokens, jk / without" for d in drivers) + " |"
    lines = [head, "|---|---:|---|" + "---|" * len(drivers)]
    for p in points:
        cells = []
        for d in drivers:
            if d not in p["drivers"]:
                cells.append("—")
                continue
            a, b = p["drivers"][d]
            cells.append(f"{_n(a['turns'])} / {_n(b['turns'])} · {_k(a['tokens'])} / {_k(b['tokens'])}")
        lines.append(f"| {p['repo']} | {p['loc']:,} | `{p['method']}` | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def claims(questions: list[dict], renames: list[dict], points: list[dict], link: str | None) -> str:
    def cut(rows, key):
        return [1 - r["jk"][key] / r["off"][key] for r in rows if r["jk"][key] is not None and r["off"][key]]
    parts = []
    if questions:
        t, k = cut(questions, "turns"), cut(questions, "tokens")
        parts.append(f"Asked who calls a method or what can end up running it, agents from {len(questions)} vendors "
                     f"answered from the graph in {min(t):.0%}–{max(t):.0%} fewer turns and {min(k):.0%}–{max(k):.0%} "
                     f"fewer tokens than with no graph")
    if renames:
        t, k = cut(renames, "turns"), cut(renames, "tokens")
        parts.append(f"renaming a method and every caller took {min(t):.0%}–{max(t):.0%} fewer turns and "
                     f"{min(k):.0%}–{max(k):.0%} fewer tokens")
    small = [p for p in points if all(a["tokens"] is not None and b["tokens"] is not None and a["tokens"] < b["tokens"]
                                      for a, b in p["drivers"].values())]
    if small:
        p = small[0]
        parts.append(f"down to the smallest repository measured, {p['repo']} at {p['loc']:,} lines")
    if not parts:
        return "_No change-task results with and without the graph yet._"
    text = "; ".join(parts) + "."
    return text[0].upper() + text[1:] + (f" ([The tables]({link}).)" if link else "")


def _splice(doc: Path, name: str, text: str) -> None:
    begin, end = f"<!-- agents-{name}:begin -->", f"<!-- agents-{name}:end -->"
    body = doc.read_text(encoding="utf-8")
    if begin not in body or end not in body:
        return
    pre, rest = body.split(begin, 1)
    _, post = rest.split(end, 1)
    doc.write_text(f"{pre}{begin}\n{text}\n{end}{post}", encoding="utf-8", newline="\n")
    print(f"rendered agents-{name} into {doc.relative_to(JK_TREE)}")


def render(board: list[dict], host: str) -> None:
    by = cells()
    questions, renames, points = comparison(by, "questions"), comparison(by, "renames"), curve(by)
    for doc, link in CLAIM_DOCS.items():
        _splice(doc, "claims", claims(questions, renames, points, link))
        _splice(doc, "questions", comparison_table(questions, "questions"))
        _splice(doc, "renames", comparison_table(renames, "renames"))
        _splice(doc, "curve", curve_table(points))
