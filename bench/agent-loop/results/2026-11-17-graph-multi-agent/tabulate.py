#!/usr/bin/env python3
"""TABLE.md body for the multi-agent graph bench.   tabulate.py TIER=ROWS.jsonl[,ROWS...] ... > TABLE.md

Per config x tier x arm: runs, green, errors, median tokens, turns, wall s, jk s, est. cost. Then per config and
tier, A against B and C: sums of per-task medians over the tasks both arms have, and whether the ranges overlap
(with repeats: A's per-task max summed against the other's per-task min summed, both ways)."""
import json
import statistics
import sys
from collections import defaultdict

ARM = {"jk": "A", "jk-before": "B", "jk-nograph": "C"}
CFG = {
    ("claude-code", "claude-sonnet-5-5"): "claude2 / sonnet-5-5 medium",
    ("claude-code", "claude-opus-5-5"): "claude2 / opus-5-5 medium",
    ("codex", "gpt-6.1-terra"): "codex / gpt-6.1-terra low (default)",
    ("codex", "gpt-6.1-sol"): "codex / gpt-6.1-sol low (default)",
    ("grok", "grok-4.7"): "grok / grok-4.7 high 256k",
    ("grok", "grok-4.5"): "grok / grok-4.5 high",
    ("cursor", "composer-2.5"): "cursor / composer-2.5",
    ("cursor", "grok-4.7-high"): "cursor / grok-4.7-high",
    ("agy", "gemini-4-hydrogen-medium"): "agy / gemini-4-hydrogen-medium",
    ("muse", "muse-spark-1.3-contributor"): "muse / spark-1.3-contributor high",
}
ORDER = list(CFG.values())
METRICS = [("tokens", "Tokens"), ("turns", "Turns"), ("wall_s", "Wall s"), ("cost_est_usd", "Cost")]


def load(spec):
    tier, paths = spec.split("=", 1)
    rows = []
    for p in paths.split(","):
        with open(p) as fh:
            rows += [json.loads(x) for x in fh if x.strip()]
    keep = {}
    # A rerun of the same cell and run number (a time-cap retry) replaces the first.
    for r in rows:
        if r.get("arm") in ARM and (r.get("driver"), r.get("model")) in CFG:
            keep[(r["driver"], r["model"], r["task"], r["arm"], r.get("run"))] = r
    return tier, list(keep.values())


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def fmt(v, key):
    if v is None:
        return "–"
    if key == "tokens":
        return f"{v / 1000:.1f}k"
    if key == "cost_est_usd":
        return f"${v:.4f}"
    if key == "turns":
        return f"{v:g}"
    return f"{v:.1f}"


def pct(a, b):
    if a is None or not b:
        return "–"
    return f"{(b - a) / b * 100:+.0f}%"


def main():
    tiers = [load(s) for s in sys.argv[1:]]
    out = []
    totals = defaultdict(lambda: defaultdict(float))
    for tier, rows in tiers:
        cells = defaultdict(list)
        for r in rows:
            cells[(CFG[(r["driver"], r["model"])], r["task"], ARM[r["arm"]])].append(r)
        out.append(f"\n## {tier}\n")
        out.append("| Config | Arm | Runs | Green | Errors | Median tokens | Turns | Wall s | jk s | Est. cost | CLI versions |")
        out.append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|")
        for cfg in ORDER:
            for arm in "ABC":
                rs = [r for (c, t, a), v in cells.items() if c == cfg and a == arm for r in v]
                if not rs:
                    continue
                ok = [r for r in rs if r.get("tokens")]
                green = sum(r.get("outcome") == "green" for r in rs)
                errs = sum(r.get("outcome") == "error" for r in rs)
                # Medians over every measured run of the arm (all tasks pooled), per-task detail below.
                vals = {k: med([r.get(k) for r in ok]) for k in ("tokens", "turns", "wall_s", "jk_s", "cost_est_usd")}
                vers = sorted({str(r.get("cli_version")) for r in rs})
                out.append(f"| {cfg} | {arm} | {len(rs)} | {green}/{len(rs)} ({green / len(rs):.0%}) | {errs} | "
                           f"{fmt(vals['tokens'], 'tokens')} | {fmt(vals['turns'], 'turns')} | {fmt(vals['wall_s'], 'w')} | "
                           f"{fmt(vals['jk_s'], 'w')} | {fmt(vals['cost_est_usd'], 'cost_est_usd')} | {', '.join(vers)} |")
                for r in rs:
                    totals[arm]["runs"] += 1
                    totals[arm]["green"] += r.get("outcome") == "green"
                    totals[arm]["cost"] += r.get("cost_est_usd") or 0
                    totals[arm]["tokens"] += r.get("tokens") or 0
        out.append(f"\n### {tier}: savings of A against B and C\n")
        out.append("Per config, summed over the tasks both arms measured (sum of per-task medians); positive means A "
                   "lower. Noise: with repeats, `overlap` means the arms' summed per-task ranges overlap, so the "
                   "difference is inside run-to-run noise; with one run per cell there is no noise estimate.\n")
        out.append("| Config | vs | Tasks | Green A / other | Tokens A → other | Δ tokens | Δ turns | Δ wall | Δ cost | Tokens noise |")
        out.append("|---|---|---:|---|---|---:|---:|---:|---:|---|")
        for cfg in ORDER:
            for other in "BC":
                tasks = sorted({t for (c, t, a) in cells if c == cfg and a == "A"} &
                               {t for (c, t, a) in cells if c == cfg and a == other})
                sums = {}
                rng = {}
                greens = {}
                ok_tasks = []
                for t in tasks:
                    a_ok = [r for r in cells[(cfg, t, "A")] if r.get("tokens")]
                    o_ok = [r for r in cells[(cfg, t, other)] if r.get("tokens")]
                    if a_ok and o_ok:
                        ok_tasks.append(t)
                if not ok_tasks:
                    continue
                for side in ("A", other):
                    allr = [r for t in tasks for r in cells[(cfg, t, side)]]
                    greens[side] = f"{sum(r.get('outcome') == 'green' for r in allr)}/{len(allr)}"
                    for k, _ in METRICS:
                        sums[(side, k)] = sum(med([r.get(k) for r in cells[(cfg, t, side)] if r.get("tokens")]) or 0
                                              for t in ok_tasks)
                    toks = [[r["tokens"] for r in cells[(cfg, t, side)] if r.get("tokens")] for t in ok_tasks]
                    rng[side] = (sum(min(x) for x in toks), sum(max(x) for x in toks), min(len(x) for x in toks))
                if min(rng["A"][2], rng[other][2]) < 2:
                    noise = "1 run"
                else:
                    overlap = rng["A"][1] >= rng[other][0] and rng[other][1] >= rng["A"][0]
                    noise = "overlap (noise)" if overlap else "separate"
                out.append(f"| {cfg} | {other} | {len(ok_tasks)} | {greens['A']} / {greens[other]} | "
                           f"{fmt(sums[('A', 'tokens')], 'tokens')} → {fmt(sums[(other, 'tokens')], 'tokens')} | "
                           + " | ".join(pct(sums[("A", k)], sums[(other, k)]) for k, _ in METRICS) + f" | {noise} |")
        out.append(f"\n### {tier}: per task\n")
        out.append("| Config | Task | Arm | Runs | Green | Median tokens (min–max) | Turns | Wall s | Est. cost |")
        out.append("|---|---|---|---:|---:|---|---:|---:|---:|")
        for cfg in ORDER:
            for (c, t, a) in sorted(k for k in cells if k[0] == cfg):
                rs = cells[(c, t, a)]
                ok = [r for r in rs if r.get("tokens")]
                toks = [r["tokens"] for r in ok]
                span = f" ({min(toks) / 1000:.1f}–{max(toks) / 1000:.1f}k)" if len(toks) > 1 else ""
                out.append(f"| {cfg} | {t} | {a} | {len(rs)} | {sum(r.get('outcome') == 'green' for r in rs)} | "
                           f"{fmt(med(toks), 'tokens')}{span} | {fmt(med([r.get('turns') for r in ok]), 'turns')} | "
                           f"{fmt(med([r.get('wall_s') for r in ok]), 'w')} | "
                           f"{fmt(med([r.get('cost_est_usd') for r in ok]), 'cost_est_usd')} |")
    out.append("\n## Totals (all tiers so far)\n")
    out.append("| Arm | Runs | Green | Tokens (sum) | Est. cost (sum) |")
    out.append("|---|---:|---:|---:|---:|")
    for arm in "ABC":
        t = totals[arm]
        if t["runs"]:
            out.append(f"| {arm} | {t['runs']:.0f} | {t['green']:.0f} | {t['tokens'] / 1e6:.2f}M | ${t['cost']:.2f} |")
    print("\n".join(out))


if __name__ == "__main__":
    main()
