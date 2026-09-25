"""Rows to a table: median and p90 per tool for turns, tokens and wall, the green rate, and every scenario."""

from __future__ import annotations

import datetime as dt
import json
import statistics
import sys
from pathlib import Path

_BENCH = Path(__file__).resolve().parents[2]
if str(_BENCH) not in sys.path:
    sys.path.insert(0, str(_BENCH))
import benchtools  # noqa: E402

TOOLS = ("jk", "mvn", "gradle")


def load_rows(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def key(row: dict) -> tuple:
    return row["driver"], row["tool"], row["repo"], row["failure"]


def merge(existing: list[dict], new: list[dict]) -> list[dict]:
    """A re-run replaces its own rows and keeps the rest."""
    merged = {key(r): r for r in existing}
    merged.update({key(r): r for r in new})
    return sorted(merged.values(), key=lambda r: (r["driver"], r["repo"], r["failure"], TOOLS.index(r["tool"]) if r["tool"] in TOOLS else 9))


def write_rows(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows), encoding="utf-8")


def p90(values: list[float]) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(0.9 * (len(ordered) - 1))))]


def fmt_s(ms: float) -> str:
    return f"{ms / 1000:.1f}s"


def fmt_n(n: float) -> str:
    return f"{int(n):,}" if n else "0"


def fmt_unit_cost(n: float | None) -> str:
    """Per-green-run cost. Totals at or above a dollar keep cents; smaller totals keep four places."""
    if n is None:
        return "—"
    if n == 0:
        return "$0"
    if abs(n) >= 1:
        return f"${n:.2f}"
    return f"${n:.4f}"


def _median(values: list[float]) -> float | None:
    return statistics.median(values) if values else None


def _median_text(values: list[float], numeric: bool = False) -> str:
    med = _median(values)
    if med is None:
        return "—"
    return fmt_n(med) if numeric else f"{med:g}"


def _present_median(rows: list[dict], key: str, numeric: bool = False) -> str:
    values = [r[key] for r in rows if r.get(key) is not None]
    return _median_text(values, numeric)


def _cost_to_green(rows: list[dict]) -> str:
    green = [r for r in rows if r["outcome"] == "green"]
    if not green or all(r.get("cost_usd") is None for r in green):
        return "—"
    return fmt_unit_cost(sum(r.get("cost_usd") or 0 for r in green) / len(green))


def _quality_cell(rows: list[dict]) -> str:
    counts = {name: sum(1 for r in rows if r.get("fix_quality") == name) for name in ("exact", "equivalent", "collateral", "cheat")}
    return " · ".join(f"{counts[name]} {name}" for name in ("exact", "equivalent", "collateral", "cheat"))


def outcome_cell(r: dict) -> str:
    mark = {"green": "green", "red": "**red**", "claimed": "**claimed**", "error": "**error**"}.get(r["outcome"], r["outcome"])
    cell = f"{mark} · {r['turns']}t · {fmt_s(r['wall_ms'])}"
    if r.get("tokens"):
        cell += f" · {fmt_n(r['tokens'])} tok"
    return cell


def _driver_sections(rows: list[dict]) -> list[str]:
    lines: list[str] = []
    drivers = sorted({r["driver"] for r in rows})
    for driver in drivers:
        sub = [r for r in rows if r["driver"] == driver]
        models = sorted({r.get("model") or "" for r in sub} - {""})
        efforts = sorted({r.get("effort") or "" for r in sub} - {""})
        budgets = sorted({f"{r['max_turns']} turns / {r['max_minutes']} min" for r in sub})
        title = f"## `{driver}`"
        if models:
            title += f" · {', '.join(models)}"
        if efforts:
            title += f" · effort {', '.join(efforts)}"
        lines.append(title + f" · budget {', '.join(budgets)}")
        lines.append("")
        lines.append(
            "| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost "
            "| First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |"
        )
        lines.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for tool in TOOLS:
            tr = [r for r in sub if r["tool"] == tool]
            if not tr:
                continue
            green = [r for r in tr if r["outcome"] == "green"]
            turns = [r["turns"] for r in tr]
            tokens = [r.get("tokens") or 0 for r in tr]
            walls = [r["wall_ms"] for r in tr]
            cost = sum(r.get("cost_usd") or 0 for r in tr)
            produced = [(r.get("output_tokens") or 0) + (r.get("reasoning_tokens") or 0) for r in tr if "output_tokens" in r or "reasoning_tokens" in r]
            fresh = [r.get("input_tokens") or 0 for r in tr if "input_tokens" in r]
            cached = [r.get("cache_read_tokens") or 0 for r in tr if "cache_read_tokens" in r]
            lines.append(
                f"| {tool} | {len(tr)} | {len(green)} | {100 * len(green) / len(tr):.0f}% | {statistics.median(turns):g} | {p90(turns):g} "
                f"| {fmt_n(statistics.median(tokens))} | {fmt_n(p90(tokens))} | {fmt_s(statistics.median(walls))} | {fmt_s(p90(walls))} "
                f"| {'$' + format(cost, '.2f') if cost else '—'} "
                f"| {_present_median(tr, 'first_correct_edit_turn')} | {_present_median(tr, 'reads_before_fix')} "
                f"| {_median_text(produced, numeric=True)} | {_median_text(fresh, numeric=True)} | {_median_text(cached, numeric=True)} "
                f"| {_cost_to_green(tr)} | {_quality_cell(tr)} |"
            )
        lines.append("")
        lines.append("| Repo | Failure | jk | mvn | gradle |")
        lines.append("|---|---|---|---|---|")
        by_scenario: dict[tuple[str, str], dict[str, dict]] = {}
        for r in sub:
            by_scenario.setdefault((r["repo"], r["failure"]), {})[r["tool"]] = r
        for (repo, failure), cells in by_scenario.items():
            lines.append(f"| {repo} | {failure} | " + " | ".join(outcome_cell(cells[t]) if t in cells else "·" for t in TOOLS) + " |")
        lines.append("")
        findings = [r for r in sub if r.get("finding")]
        if findings:
            lines.append("### Findings")
            lines.append("")
            lines.append("What the results file did not say, per run: the oracle records where it needed more than the file, "
                         "and the LLM drivers record why they stopped.")
            lines.append("")
            lines.append("| Repo | Failure | Tool | Outcome | Fix source | Finding |")
            lines.append("|---|---|---|---|---|---|")
            for r in findings:
                lines.append(f"| {r['repo']} | {r['failure']} | {r['tool']} | {r['outcome']} | {', '.join(r.get('fix_sources') or []) or '—'} | {r['finding'].replace('|', '\\|')} |")
            lines.append("")
    return lines


def render(rows: list[dict], jk_version: str) -> str:
    info = benchtools.host_info()
    hid = benchtools.host_id(info)
    current = [r for r in rows if benchtools.row_host(r) == hid]
    others: dict[str, list[dict]] = {}
    for row in rows:
        host = benchtools.row_host(row)
        if host != hid:
            others.setdefault(host, []).append(row)
    drivers = sorted({r["driver"] for r in current}) or sorted({r["driver"] for r in rows})
    lines = ["# Agent loop: turns, tokens and wall to green", ""]
    lines.append(
        f"Date: {dt.date.today().isoformat()} · {jk_version} · host `{hid}` · {benchtools.host_summary(info)} · "
        f"drivers: {', '.join(drivers) if drivers else '—'} · {len(current)} (scenario × tool) runs on this host"
    )
    lines.append("")
    lines.append("A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, "
                 "then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. "
                 "A row is green only when the harness's own rerun after the agent stopped is green too. "
                 "Turns under the cap are informational. The comparison is the green rate, the cost to green "
                 "(sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit "
                 "that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached "
                 "input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). "
                 "Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. "
                 "The column rules are in the agent-loop README. "
                 "Rows from another host are listed under their own heading and are not mixed into these numbers.")
    lines.append("")
    if current:
        lines.extend(_driver_sections(current))
    else:
        lines.append(f"No rows for this host (`{hid}`).")
        lines.append("")
    for host, host_rows in sorted(others.items()):
        lines.append(f"## Other host `{host}`")
        lines.append("")
        lines.append("Not compared with this host. These runs do not enter the medians above.")
        lines.append("")
        lines.extend(_driver_sections(host_rows))
    return "\n".join(lines)
