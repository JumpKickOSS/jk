"""The code graph's published numbers: tokens per correct answer and index time, from bench/graph-retrieval.

No tier runs it: the retrieval bench is its own harness (`bench/graph-retrieval/bench run`, then
`index` and `edges` on an idle host), and its results directory is checked in. `render` writes the
newest results into the `graph-claims` blocks of the docs that pitch the graph. No other text may
compare the graph's tokens or index time against another tool; the `speed-claims-are-generated`
guard holds the docs to that.
"""

from __future__ import annotations

import json
import statistics
import sys
from pathlib import Path

TIERS: tuple[str, ...] = ()
JK_TREE = Path(__file__).resolve().parent.parent.parent
RESULTS = JK_TREE / "bench" / "graph-retrieval" / "results"
# Each doc that carries the graph sentences, and its link to the bench.
CLAIM_DOCS = {
    JK_TREE / "README.md": "bench/graph-retrieval/README.md",
    JK_TREE / "docs" / "user" / "why.md": "../../bench/graph-retrieval/README.md",
    JK_TREE / "docs" / "user" / "comparison.md": "../../bench/graph-retrieval/README.md",
    JK_TREE / "docs" / "user" / "graph.md": "../../bench/graph-retrieval/README.md",
}
TOOL_ARMS = {"jk": "jk-tool", "grep": "grep-read", "graphify": "graphify-tool", "RepoQL": "rql-tool"}


def measure(ctx) -> list[dict]:
    return []


def latest() -> Path | None:
    dirs = sorted(d for d in RESULTS.glob("*") if (d / "rows.jsonl").is_file())
    return dirs[-1] if dirs else None


def _rows(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text().splitlines() if line.strip()] if path.is_file() else []


def summary(out: Path) -> dict:
    """Per repo and arm: median tokens, recall, tokens per found entity; per repo and tool: index seconds."""
    rows = [r for r in _rows(out / "rows.jsonl") if "error" not in r]
    arms: dict[tuple[str, str], dict] = {}
    for repo, arm in {(r["repo"], r["arm"]) for r in rows}:
        rs = [r for r in rows if r["repo"] == repo and r["arm"] == arm]
        found = sum(r["found"] for r in rs)
        arms[(repo, arm)] = {
            "loc": rs[0]["loc"], "questions": len(rs),
            "tokens": statistics.median(r["tokens"] for r in rs),
            "recall": statistics.mean(r["recall"] for r in rs),
            "per_found": sum(r["tokens"] for r in rs) / found if found else None,
        }
    index = {(r["repo"], r["tool"], r["mode"]): r["median_s"] for r in _rows(out / "index.jsonl")}
    edges = {(r["repo"], r["tool"]): r for r in _rows(out / "edges.jsonl")}
    return {"arms": arms, "index": index, "edges": edges}


def _n(x: float) -> str:
    return f"{x:,.0f}"


def _s(x: float | None) -> str:
    if x is None:
        return "—"
    return f"{x:.1f} s" if x >= 1 else f"{x:.2f} s"


def claims(s: dict, link: str, out: Path) -> str:
    arms = s["arms"]
    repos = sorted({repo for repo, _ in arms}, key=lambda r: arms[(r, "jk-tool")]["loc"] if (r, "jk-tool") in arms else 0)
    big = next((r for r in reversed(repos) if (r, "jk-tool") in arms and (r, "grep-read") in arms), None)
    if big is None:
        return "_No graph retrieval results yet: run `bench/graph-retrieval/bench run`._"
    jk, grep = arms[(big, "jk-tool")], arms[(big, "grep-read")]
    lines = [
        f"On {big} ({_n(jk['loc'])} lines), asked who calls a method, what implements or overrides it and where it is "
        f"defined, jk's graph tools answered in a median {_n(jk['tokens'])} tokens and named "
        f"{jk['recall']:.0%} of the compiler's answer; grep and reading the files it matched took "
        f"{_n(grep['tokens'])} tokens for {grep['recall']:.0%}. Per correct answer that is "
        f"{_n(jk['per_found'])} tokens against {_n(grep['per_found'])}."
    ]
    others = []
    for name, arm in (("graphify", "graphify-tool"), ("RepoQL", "rql-tool")):
        if (big, arm) in arms:
            a = arms[(big, arm)]
            others.append(f"{name}'s {_n(a['tokens'])} tokens for {a['recall']:.0%}")
    if others:
        lines.append("On the same questions: " + "; ".join(others) + ".")
    idx = s["index"]
    if (big, "jk", "full") in idx:
        parts = [f"jk {_s(idx[(big, 'jk', 'full')])} ({_s(idx.get((big, 'jk', 'incremental')))} after a one-line edit)"]
        for name, tool in (("graphify", "graphify"), ("RepoQL", "rql")):
            if idx.get((big, tool, "full")) is not None:
                parts.append(f"{name} {_s(idx[(big, tool, 'full')])}")
        lines.append("Indexing it from nothing: " + ", ".join(parts) + ".")
    e = s["edges"].get((big, "jk"))
    if e and e.get("recall") is not None:
        lines.append(f"jk's call edges match the compiled classes' at {e['precision']:.0%} precision and "
                     f"{e['recall']:.0%} recall.")
    lines.append(f"([The bench]({link}), results of {out.name}.)")
    return " ".join(lines)


def table(s: dict) -> str:
    arms = s["arms"]
    head = ["| Repo | Lines | " + " | ".join(f"{t} tokens · found" for t in TOOL_ARMS) + " |",
            "|---|---:|" + "---:|" * len(TOOL_ARMS)]
    repos = sorted({repo for repo, _ in arms}, key=lambda r: next(iter(a["loc"] for (rr, _), a in arms.items() if rr == r)))
    for repo in repos:
        loc = next(a["loc"] for (rr, _), a in arms.items() if rr == repo)
        cells = []
        for arm in TOOL_ARMS.values():
            a = arms.get((repo, arm))
            cells.append(f"{_n(a['tokens'])} · {a['recall']:.0%}" if a else "—")
        head.append(f"| {repo} | {_n(loc)} | " + " | ".join(cells) + " |")
    return "\n".join(head)


def _splice(doc: Path, name: str, text: str) -> None:
    begin, end = f"<!-- graph-{name}:begin -->", f"<!-- graph-{name}:end -->"
    body = doc.read_text(encoding="utf-8")
    if begin not in body or end not in body:
        return
    pre, rest = body.split(begin, 1)
    _, post = rest.split(end, 1)
    doc.write_text(f"{pre}{begin}\n{text}\n{end}{post}", encoding="utf-8")
    print(f"rendered graph-{name} into {doc.relative_to(JK_TREE)}")


def render(board: list[dict], host: str) -> None:
    out = latest()
    if out is None:
        print("graph: no retrieval results", file=sys.stderr)
        return
    s = summary(out)
    for doc, link in CLAIM_DOCS.items():
        _splice(doc, "claims", claims(s, link, out))
        _splice(doc, "table", table(s))
