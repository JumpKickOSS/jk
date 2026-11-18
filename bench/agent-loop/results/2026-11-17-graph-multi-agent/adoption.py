#!/usr/bin/env python3
"""Adoption per config and arm from the transcripts.   adoption.py [--map OLD=NEW] LABEL=ROWS.jsonl[:TASK+TASK] ... > md

calls.py (the graph-adoption investigation's extractor, copied here) reads each run's transcript.jsonl next to
its sandbox; --map rewrites the sandbox path's prefix where the run directories were copied. Per config and arm,
per run: jk calls by tool, calls using a 0.52 capability, reads/searches before and after the first graph answer
(callers/rename), `skill` and card detours, and how many jk answers carried "compile skipped" (JK-3MF95: on a
project with [build-info] the graph's own test compile failed to plan, and every callers answer ended "compile
skipped: ... search it for calls too").  --runs FILE also writes one JSON record per run."""
import collections
import json
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import calls as C  # noqa: E402
from tabulate import CFG, ORDER, ARM  # noqa: E402

ANCHOR = re.compile(r"^(route|table|column|env|config|sql|json|file):", re.I)
SKIPPED = re.compile(r"compile skipped", re.I)


def newcap(c):
    n, a, t = c["name"], c["args"], c["text"]
    if n == "jk.rename":
        if a.get("also") or re.match(r"Applied c\d", t):
            return "rename also="
        if re.match(r"Renamed \S+ \((field|class|package|type|record|enum|interface)", t):
            return "rename field/class"
    if n == "jk.callers":
        if ANCHOR.match(str(a.get("address") or "")) or re.match(r"(Uses|Callers) of (route|table|column|env|config)", t):
            return "callers anchor"
        if re.match(r"Uses of \S+ \((field|type|class|annotation)", t):
            return "callers field/type"
    return None


def analyse(r, mp):
    sb = r["sandbox"]
    for old, new in mp:
        if sb.startswith(old):
            sb = new + sb[len(old):]
    tr = Path(sb).parent / "transcript.jsonl"
    if not tr.exists():
        return None
    cs = C.calls(tr, r["driver"])
    jk = [c for c in cs if c["cat"] == "jk"]
    gi = next((i for i, c in enumerate(cs) if c["name"] in ("jk.callers", "jk.rename")), None)
    rs = ("read", "search")
    before = sum(1 for c in cs[: gi if gi is not None else len(cs)] if c["cat"] in rs)
    after = sum(1 for c in cs[gi + 1:] if c["cat"] in rs) if gi is not None else 0
    return {"cfg": CFG[(r["driver"], r["model"])], "arm": r["_arm"], "task": r["task"], "run": r.get("run"),
            "outcome": r.get("outcome"), "jk_build": r.get("jk_build"), "jk_version": r.get("jk_version"),
            "tools": dict(collections.Counter(c["name"][3:] if c["name"].startswith("jk.") else "jk-shell" for c in jk)),
            "newcaps": [x for x in map(newcap, jk) if x], "graph_answered": gi is not None,
            "before": before, "after": after,
            "skill": sum(1 for c in jk if c["name"] == "jk.skill"), "discover": sum(1 for c in cs if c["cat"] == "discover"),
            "answers": len(jk), "skipped_answers": sum(1 for c in jk if SKIPPED.search(c["text"] or "")),
            "callers": sum(1 for c in jk if c["name"] == "jk.callers"),
            "seq": [c["name"][3:] if c["cat"] == "jk" else c["cat"] for c in cs]}


def main():
    a = sys.argv[1:]
    mp, specs, runs_out = [], [], None
    while a:
        x = a.pop(0)
        if x == "--map":
            o, n = a.pop(0).split("=", 1); mp.append((o, n))
        elif x == "--runs":
            runs_out = a.pop(0)
        else:
            specs.append(x)
    recs, missing = [], 0
    for spec in specs:
        label, path = spec.split("=", 1)
        path, _, only = path.partition(":")
        keep = {}
        for line in open(path):
            if line.strip():
                r = json.loads(line)
                if (r.get("arm") in ARM and (r.get("driver"), r.get("model")) in CFG
                        and (not only or r["task"] in only.split("+"))):
                    r["_arm"] = label if label != "-" else ARM[r["arm"]]
                    keep[(r["driver"], r["model"], r["task"], r["_arm"], r.get("run"))] = r
        for r in keep.values():
            x = analyse(r, mp)
            if x:
                recs.append(x)
            else:
                missing += 1
    if runs_out:
        with open(runs_out, "w") as fh:
            for x in recs:
                fh.write(json.dumps(x) + "\n")
    g = collections.defaultdict(list)
    for x in recs:
        g[(x["cfg"], x["arm"])].append(x)
    print("| Config | Arm | Runs | jk calls/run (by tool) | 0.52-capability calls/run | graph answered | reads+searches before / after first graph answer, per run | skill/run | card lookups/run | jk answers with \"compile skipped\" | runs with it |")
    print("|---|---|---:|---|---:|---:|---|---:|---:|---:|---:|")
    for cfg in ORDER:
        for arm in "MFABC":
            rs = g.get((cfg, arm))
            if not rs:
                continue
            n = len(rs)
            tools = collections.Counter()
            for x in rs:
                tools.update(x["tools"])
            ts = ", ".join(f"{k} {v / n:.1f}" for k, v in tools.most_common(5))
            print(f"| {cfg} | {arm} | {n} | {sum(tools.values()) / n:.1f} ({ts}) | {sum(len(x['newcaps']) for x in rs) / n:.2f} | "
                  f"{sum(x['graph_answered'] for x in rs)}/{n} | {sum(x['before'] for x in rs) / n:.2f} / {sum(x['after'] for x in rs) / n:.2f} | "
                  f"{sum(x['skill'] for x in rs) / n:.2f} | {sum(x['discover'] for x in rs) / n:.2f} | "
                  f"{sum(x['skipped_answers'] for x in rs)} of {sum(x['answers'] for x in rs)} | {sum(x['skipped_answers'] > 0 for x in rs)}/{n} |")
    tot = collections.defaultdict(lambda: [0, 0, 0, 0])
    for x in recs:
        t = tot[x["arm"]]; t[0] += 1; t[1] += x["skipped_answers"] > 0; t[2] += x["skipped_answers"]; t[3] += x["callers"]
    print()
    print("| Arm | Runs | Runs with a \"compile skipped\" answer | \"compile skipped\" answers | callers calls |")
    print("|---|---:|---:|---:|---:|")
    for arm in "MFABC":
        if arm in tot:
            t = tot[arm]
            print(f"| {arm} | {t[0]} | {t[1]} | {t[2]} | {t[3]} |")
    if missing:
        print(f"\n{missing} rows had no transcript and are not counted.")


if __name__ == "__main__":
    main()
