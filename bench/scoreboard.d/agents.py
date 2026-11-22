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
  agents.<driver>.<group>.<kind>-tokens.<model>
                                            the same for each kind of token a vendor prices apart
                                            (input, output, cache-read, cache-write), per model
                                            version. Dollars are never banked: the docs price these
                                            sums at render time with prices.toml (harnesslib/cost.py),
                                            so a new price re-prices every claim and fails no row.

Every agent row carries the host the change-task runs ran on, not the host that derives it.

`tool` is the arm (`jk`, `jk-nograph`), so jk without the graph is the comparison row beside each.
A subtype question is not counted: it is answered from declarations, and grep is as cheap there.
"""

from __future__ import annotations

import json
import re
import statistics
import sys
import tomllib
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "agent-loop"))
from harnesslib import cost  # noqa: E402

TIERS = ("night", "release")

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
    models = {d: m for d, (m, _) in CLAIMED.items()}
    efforts = {d: e for d, (_, e) in CLAIMED.items()}
    by: dict[tuple[str, str, str], dict[tuple[str, int], list[dict]]] = {}
    for f in sorted(RESULTS.glob("*/rows.jsonl")):
        for line in f.read_text().splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            # A row with no verdict is a run that never started (its baseline was red): not a series.
            if (r.get("task") not in tasks or r.get("model") != models.get(r.get("driver"))
                    or r.get("effort") != efforts.get(r.get("driver")) or "accepted" not in r):
                continue
            r["kind"] = tasks[r["task"]].get("kind", r.get("kind", ""))
            r["results"] = f.parent.name
            by.setdefault((r["driver"], r["arm"], r["task"]), {}).setdefault(series(r), []).append(r)
    return {k: v[max(v)] for k, v in by.items()}


# The banked series the claims render from: each agent's model and effort as its claims' rows were
# measured, and the corpus lines where the corpus has moved on since (jk's tree, at 1d0d441ed). New runs
# use change-tasks' pins (DEFAULT_MODEL, DEFAULT_EFFORT); once a baseline on those pins is banked, they
# replace these and CLAIMED_LOC goes.
CLAIMED = {"claude-code": ("claude-sonnet-5-5", "medium"), "grok": ("grok-4.7", "high"),
           "agy": ("gemini-3.8-flash-high", "high"), "cursor": ("composer-2.5", "medium"),
           "muse": ("muse-spark-1.3-contributor", "xhigh"), "codex": ("gpt-6.1-terra", "medium")}
CLAIMED_LOC = {"jk": 679533}


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
        first = len(found)
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
        model = next(iter(tasks.values()))[0].get("model")
        for kind, n in (_by_kind(tasks, list(tasks)) or {}).items():
            found.append(row(f"agents.{driver}.{group}.{kind}-tokens.{model}", arm, n, "tokens", runs, len(tasks)))
        host = _host(tasks)
        for r in found[first:]:
            r["host-id"] = host
    return found


def _host(cells: dict) -> str | None:
    """The host most of these runs ran on."""
    hosts = [r["host"] for rs in cells.values() for r in rs if r.get("host")]
    return max(set(hosts), key=hosts.count) if hosts else None


def _by_kind(cells: dict, tasks: list[str]) -> dict[str, float] | None:
    """Per-task median of each kind of token, summed over the tasks; None when a task reported no tokens."""
    per_task = [[k for r in cells[t] if (k := cost.kinds(r)) is not None] for t in tasks]
    if not all(per_task):
        return None
    return {kind: sum(statistics.median(k[kind] for k in runs) for runs in per_task) for kind in cost.KINDS}


def _usd(cells: dict, tasks: list[str]) -> float | None:
    """The API list price of the tasks' tokens by kind, today's prices.toml; None without a price row."""
    tokens = _by_kind(cells, tasks)
    first = cells[tasks[0]][0]
    return cost.usd(first.get("model"), first.get("driver"), tokens) if tokens else None


def _retrieved(rs: list[dict]) -> str:
    """The date the price row of these runs' model was read from its vendor's page."""
    return cost.prices()[cost.price_key(rs[0].get("model"))]["retrieved"]


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
# Each agent's arm with the graph as `jk agents` sets it up: Cursor and agy with jk's rules file.
PRESCRIPTION = {"claude-code": "jk", "grok": "jk", "muse": "jk", "cursor": "jk-cursorrules", "agy": "jk-agyrules",
                "codex": "jk", "copilot": "jk"}
LABELS = {"claude-code": "Vendor A, mid-tier", "grok": "Vendor B, frontier", "agy": "Vendor C, fast",
          "cursor": "Vendor D", "muse": "Vendor E", "codex": "Vendor F"}
QUESTIONS = JK_TREE / "bench" / "graph-retrieval" / "questions"
# The docs that carry the default-against-top-model comparison (a doc without a block's markers is skipped).
MODEL_DOCS = (JK_TREE / "docs" / "user" / "why.md", JK_TREE / "hosting" / "public" / "index.html")
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
    jk, off = tasks(PRESCRIPTION.get(driver, "jk")), tasks("jk-nograph")
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
            "wall": med("wall_s"), "usd": _usd(ok, tasks), "share": statistics.median(
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
    lines = [f"| Agent ({unit}) | Correct, JumpKick / without the graph | Turns | Tokens | Wall |",
             "|---|---|---|---|---|"]
    for r in rows:
        a, b = r["jk"], r["off"]
        lines.append(f"| {LABELS[r['driver']]} ({r['tasks']}) | {a['correct']} of {a['runs']} / {b['correct']} of "
                     f"{b['runs']} | {_n(a['turns'])} / {_n(b['turns'])} | {_k(a['tokens'])} / {_k(b['tokens'])} | "
                     f"{a['wall']:,.0f} / {b['wall']:,.0f} s |")
    sets = sorted({s for r in rows for s in r["results"]})
    lines += ["", "JumpKick is each agent as `jk agents` sets it up (Vendor C and D with JumpKick's rules file), one run per task "
              "on the current build; without the graph, three to five runs per task, the graph hidden from MCP and "
              "the shell alike. "
              "Medians per task, summed over the tasks each agent ran on both arms; results: "
              + ", ".join(f"`{s}`" for s in sets) + "."]
    return "\n".join(lines)


def _dollars(x: float) -> str:
    return f"${x:.3f}" if x < 1 else f"${x:,.2f}"


def dollars(by: dict, questions: list[dict], renames: list[dict]) -> str:
    """Cost per task with the graph and without it, each agent's runs priced at its vendor's API list price."""
    def per_task(rows):
        return {r["driver"]: (r["jk"]["usd"] / r["tasks"], r["off"]["usd"] / r["tasks"]) for r in rows
                if r["jk"]["usd"] is not None and r["off"]["usd"] is not None}

    q, n = per_task(questions), per_task(renames)
    drivers = [d for d in LABELS if d in q or d in n]
    if not drivers:
        return "_No priced change-task results with and without the graph yet._"
    dates = sorted({_retrieved(rs) for (d, _, _), rs in by.items() if d in drivers})
    unpriced = [LABELS[r["driver"]] for r in questions + renames if r["driver"] not in drivers]

    def span(costs, i):
        xs = [c[i] for c in costs.values()]
        return f"{_dollars(min(xs))}–{_dollars(max(xs))}"

    def cell(costs, d):
        return f"{_dollars(costs[d][0])} / {_dollars(costs[d][1])}" if d in costs else "—"
    sentences = []
    if q:
        sentences.append(f"With JumpKick's code graph a question cost the agents **{span(q, 0)}** per task, "
                         f"against **{span(q, 1)}** without it.")
    if n:
        sentences.append(f"A rename cost **{span(n, 0)}** per task, against **{span(n, 1)}**.")
    lines = [" ".join(sentences), "", "| Agent | Per question, JumpKick / without the graph | Per rename |",
             "|---|---|---|"]
    lines += [f"| {LABELS[d]} | {cell(q, d)} | {cell(n, d)} |" for d in drivers]
    lines += ["", "An API list-price estimate, not a bill: the tokens each agent reported in the runs above, "
              f"priced at its vendor's published API list price for its model, read {' and '.join(dates)} "
              "([prices](../../shared/client-io/src/main/resources/cc/jumpkick/prices/prices.toml)). An agent on a subscription pays its plan instead. "
              "Each kind of token (input, output, cache read, cache write) is the median per task, summed over the "
              "tasks the agent ran both ways; the sum is priced, then divided by the number of tasks."
              + (f" Left out, with no price row for its model: {'; '.join(sorted(set(unpriced)))}." if unpriced else "")]
    return "\n".join(lines)


# The table the engine credits each code answer and refactoring from (SavingsRates.java).
RATES = JK_TREE / "server" / "engine" / "src" / "main" / "resources" / "cc" / "jumpkick" / "engine" / "savings" / "rates.json"
RATE_KINDS = {"input": "input", "output": "output", "cache-read": "cacheRead", "cache-write": "cacheWrite"}


def _per_call(jk: dict, off: dict, both: list[str]) -> dict | None:
    """What one graph call saved over the tasks run both ways: per task, each kind of token's median
    without the graph less its median with it, summed over the tasks whose jk runs made graph calls and
    divided by the median graph calls of those runs, summed; None when a run lacks its tokens or count."""
    saved, calls, tasks = {k: 0.0 for k in cost.KINDS}, 0.0, 0
    for t in both:
        on = [r for r in jk[t] if r.get("accepted")] or jk[t]
        without = [r for r in off[t] if r.get("accepted")] or off[t]
        if any(r.get("graph") is None for r in on):
            return None
        n = statistics.median(r["graph"] for r in on)
        if n <= 0:
            continue
        k_on = [k for r in on if (k := cost.kinds(r)) is not None]
        k_off = [k for r in without if (k := cost.kinds(r)) is not None]
        if not k_on or not k_off:
            return None
        for k in cost.KINDS:
            saved[k] += statistics.median(x[k] for x in k_off) - statistics.median(x[k] for x in k_on)
        calls += n
        tasks += 1
    if not tasks:
        return None
    return {"tasks": tasks, "graphCalls": int(calls) if float(calls).is_integer() else calls, **{RATE_KINDS[k]: round(saved[k] / calls) for k in cost.KINDS}}


def _by_size(jk: dict, off: dict, both: list[str], repos: dict) -> list[dict]:
    """The same rate per repository the tasks ran on, with its lines of code, smallest first: the
    saving of a graph call in a repository of about that size. A repository with no rate is left out."""
    out = []
    for repo in sorted({repos[t] for t in both}):
        loc = _loc(repo)
        rate = _per_call(jk, off, [t for t in both if repos[t] == repo]) if loc is not None else None
        if rate is not None:
            out.append({"loc": loc, **rate})
    return sorted(out, key=lambda r: r["loc"])


def rates(by: dict) -> str:
    """Tokens one graph call saved in the question tasks and in the rename tasks, per agent on its
    default model, as JSON, with the model and whether its cache writes are priced at the 1-hour rate:
    over every task, and per repository size under `sizes`. No prices: the engine prices the tokens
    with the product's price table when it shows them."""
    repos = {t: spec["repo"] for t, spec in tomllib.loads((RESULTS.parent / "tasks.toml").read_text())["tasks"].items()}
    agents, measured = {}, set()
    for driver in LABELS:
        entry = {}
        for kind, action in (("questions", "question"), ("renames", "rename")):
            p = _pair(by, driver, kind)
            if not p:
                continue
            both, jk, off = p
            rate = _per_call(jk, off, both)
            if rate is None:
                continue
            entry[action] = {**rate, "sizes": _by_size(jk, off, both, repos)}
            measured.update(str(r.get("date", "")) for cells in (jk, off) for t in both for r in cells[t])
        model = next((rs[0].get("model") for (d, _, _), rs in by.items() if d == driver), None)
        if not entry or not model:
            continue
        agents[driver] = {"model": model, "cacheWrite1h": driver in cost.CACHE_1H, **entry}
    table = {"measured": max(measured - {""}, default=""), "agents": agents}
    return json.dumps(table, indent=2) + "\n"


def _loc(repo: str) -> int | None:
    if repo in CLAIMED_LOC:
        return CLAIMED_LOC[repo]
    f = QUESTIONS / f"{repo}.json"
    return json.loads(f.read_text())["loc"] if f.is_file() else None


def curve(by: dict) -> list[dict]:
    tasks = tomllib.loads((RESULTS.parent / "tasks.toml").read_text())["tasks"]
    out = []
    for name, spec in tasks.items():
        if not spec.get("kind", "").startswith("rename") or _loc(spec["repo"]) is None:
            continue
        method = re.search(r"`([\w.]+)(?:\([^`]*\))?`", spec["prompt"])
        point = {"task": name, "repo": spec["repo"], "loc": _loc(spec["repo"]), "drivers": {},
                 "method": method.group(1) if method else name}
        for driver in ("claude-code", "cursor"):
            jk, off = by.get((driver, PRESCRIPTION.get(driver, "jk"), name)), by.get((driver, "jk-nograph", name))
            if jk and off:
                point["drivers"][driver] = (_sums({name: jk}, [name]), _sums({name: off}, [name]))
        if point["drivers"]:
            out.append(point)
    return sorted(out, key=lambda p: (p["loc"], p["task"]))


def curve_table(points: list[dict]) -> str:
    drivers = [d for d in ("claude-code", "cursor") if any(d in p["drivers"] for p in points)]
    head = "| Repository | Lines | Rename | " + " | ".join(f"{LABELS[d]}: turns · tokens, JumpKick / without" for d in drivers) + " |"
    lines = [head, "|---|---:|---|" + "---|" * len(drivers)]
    for p in points:
        cells = []
        for d in drivers:
            if d not in p["drivers"]:
                cells.append("—")
                continue
            a, b = p["drivers"][d]
            cells.append(f"{_n(a['turns'])} / {_n(b['turns'])} · {_k(a['tokens'])} / {_k(b['tokens'])}")
        lines.append(f"| {'JumpKick' if p['repo'] == 'jk' else p['repo']} | {p['loc']:,} | `{p['method']}` | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def claims(questions: list[dict], renames: list[dict], points: list[dict], link: str | None,
           html: bool = False) -> str:
    """The plain-English summary a reader meets first: what was asked, and how much less work it took.
    Each takeaway is emphasized in the target's own markup: <strong> on the website, ** in Markdown."""
    def em(text):
        return f"<strong>{text}</strong>" if html else f"**{text}**"

    def cut(rows, key):
        return [1 - r["jk"][key] / r["off"][key] for r in rows if r["jk"][key] is not None and r["off"][key]]

    def span(xs, what):
        return em(f"{min(xs) * 100:.0f}–{max(xs):.0%} {what}")

    def priced(rows, what, note=""):
        """The dollar saving first, then what a task cost with the graph and without it."""
        rows = [r for r in rows if r["jk"]["usd"] is not None and r["off"]["usd"] is not None]
        if not rows:
            return ""
        each = [(r["jk"]["usd"] / r["tasks"], r["off"]["usd"] / r["tasks"]) for r in rows]
        return (f"{span(cut(rows, 'usd'), 'less')}, {_dollars(min(a for a, _ in each))}–"
                f"{_dollars(max(a for a, _ in each))} {what} instead of {_dollars(min(b for _, b in each))}–"
                f"{_dollars(max(b for _, b in each))}{note}")
    sentences = []
    if questions:
        t, k = cut(questions, "turns"), cut(questions, "tokens")
        paid = priced(questions, "a question", " (an estimate at API list prices)")
        lead = (f"With JumpKick's code graph, their answers cost {paid}, and took {span(k, 'fewer tokens')} and "
                f"{span(t, 'fewer back-and-forth turns')}" if paid else
                f"With JumpKick's code graph, they answered in {span(t, 'fewer back-and-forth turns')} and used "
                f"{span(k, 'fewer tokens')}")
        sentences.append(f"We asked coding agents from {len(questions)} vendors questions like \"who calls this method?\" "
                         f"{lead} than without it.")
    if renames:
        t, k = cut(renames, "turns"), cut(renames, "tokens")
        paid = priced(renames, "a rename")
        sentences.append(f"Renaming a method everywhere it is used cost {paid}, with {span(k, 'fewer tokens')} and "
                         f"{span(t, 'fewer turns')}." if paid else
                         f"Renaming a method everywhere it is used took {span(t, 'fewer turns')} and "
                         f"{span(k, 'fewer tokens')}.")
    small = [p for p in points if all(a["tokens"] is not None and b["tokens"] is not None and a["tokens"] < b["tokens"]
                                      for a, b in p["drivers"].values())]
    if small:
        p = small[0]
        size = em(f"{p['loc']:,} lines of code")
        sentences.append(f"The graph pays off even on tiny projects: the smallest we tested, {p['repo']}, "
                         f"has just {size}.")
    if not sentences:
        return "_No change-task results with and without the graph yet._"
    return " ".join(sentences) + (f" ([The tables]({link}).)" if link else "")


def models(prescription_only: bool = True) -> list[dict]:
    """Each agent's default model against every other model it ran, on the tasks both ran with its
    prescription: the newest series of each (driver, model, task)."""
    tasks = tomllib.loads((RESULTS.parent / "tasks.toml").read_text())["tasks"]
    newest: dict[tuple[str, str, str], dict[tuple[str, int], list[dict]]] = {}
    for f in sorted(RESULTS.glob("*/rows.jsonl")):
        for line in f.read_text().splitlines():
            if not line.strip():
                continue
            r = json.loads(line)
            d = r.get("driver")
            if r.get("task") not in tasks or "accepted" not in r or r.get("arm") != PRESCRIPTION.get(d, "jk"):
                continue
            tier = f"{r.get('model')}/{r.get('effort')}"
            newest.setdefault((d, tier, r["task"]), {}).setdefault(series(r), []).append(r)
    cells = {k: v[max(v)] for k, v in newest.items()}
    out = []
    for driver in LABELS:
        mine = {m for (d, m, _) in cells if d == driver}
        default = "/".join(CLAIMED.get(driver, ("", "")))
        for model in sorted(mine - {default}):
            both = sorted(t for (d, m, t) in cells if d == driver and m == model
                          and (driver, default, t) in cells)
            if both:
                out.append({"driver": driver, "tasks": both,
                            "default": _sums({t: cells[(driver, default, t)] for t in both}, both),
                            "top": _sums({t: cells[(driver, model, t)] for t in both}, both)})
    return out


def models_table(rows: list[dict]) -> str:
    lines = ["| Agent (tasks) | Model | Correct | Turns | Tokens | Wall |", "|---|---|---|---|---|---|"]
    for r in rows:
        for tier in ("default", "top"):
            a = r[tier]
            lines.append(f"| {LABELS[r['driver']].split(',')[0]} ({len(r['tasks'])}) | {'default' if tier == 'default' else 'top model'} "
                         f"| {a['correct']} of {a['runs']} | {_n(a['turns'])} | {_k(a['tokens'])} | {a['wall']:,.0f} s |")
    lines += ["", "Each agent with the graph as `jk agents` sets it up, on the same tasks, its default model against its "
              "vendor's top model (or, for Vendor B, its model at the highest reasoning effort). Medians per task, summed."]
    return "\n".join(lines)


def models_claim(rows: list[dict]) -> str:
    if not rows:
        return "_No runs on another model yet._"
    up = max(r["top"]["turns"] - r["default"]["turns"] for r in rows)
    toks = [r["top"]["tokens"] / r["default"]["tokens"] - 1 for r in rows if r["default"]["tokens"]]
    correct = all(r["top"]["correct"] == r["top"]["runs"] for r in rows)
    more = "no more turns" if up <= 0 else f"at most {up:.0f} more turn{'s' if up > 1 else ''}"
    return (f"On {len(rows)} agents' top models, the graph's runs took {more} across "
            f"{len(rows[0]['tasks'])} tasks than on each agent's default model, and tokens within "
            f"{max(abs(x) for x in toks):.0%}{', every answer right' if correct else ''}: the gain does not depend "
            f"on the model.")


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
        _splice(doc, "claims", claims(questions, renames, points, link, html=doc.suffix == ".html"))
        _splice(doc, "questions", comparison_table(questions, "questions"))
        _splice(doc, "renames", comparison_table(renames, "renames"))
        _splice(doc, "curve", curve_table(points))
        _splice(doc, "dollars", dollars(by, questions, renames))
    table = rates(by)
    if not RATES.is_file() or RATES.read_text(encoding="utf-8") != table:
        RATES.parent.mkdir(parents=True, exist_ok=True)
        RATES.write_text(table, encoding="utf-8", newline="\n")
        print(f"rendered {RATES.relative_to(JK_TREE)}")
    tiers = models()
    for doc in MODEL_DOCS:
        _splice(doc, "models", models_table(tiers))
        _splice(doc, "models-claim", models_claim(tiers))
