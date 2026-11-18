#!/usr/bin/env python3
"""Stage P (anchor push on or off, one build): per config and arm, traps and decoys apart.

    pushnoise.py [--map OLD=NEW] [--runs OUT.jsonl] LABEL=ROWS.jsonl ... > md

For each run, from its transcript (calls.py) and diff.patch next to the sandbox:
  pushes       jk answers carrying a stale-anchor push ("... changed these names; these places still use the old one:")
  pushed files the files those lines name
  push reads   reads/searches after the first push whose arguments name a pushed file
  noise reads  those of them on a file the run's diff does not touch (read, then left alone)
  pushed fixed pushed files the diff touches
plus green, tokens, turns, wall, jk s and all reads/searches. Traps and decoys are told apart by task name
(`decoy` in it). Verdict per config, S against O: traps, green and tokens; decoys, reads and turns."""
import collections
import json
import re
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import calls as C  # noqa: E402
from tabulate import CFG, ORDER  # noqa: E402

PUSH = re.compile(r"changed these names; these places still use the old one:")
FILE = re.compile(r"([\w./-]+\.[A-Za-z0-9]+):\d+")
DOCS = re.compile(r"(Docs use the old name too|changed a name the docs still use): ([^\n]*)")


def pushed_files(text):
    out = set()
    if not PUSH.search(text):
        return out
    for line in text[PUSH.search(text).end():].splitlines():
        if not line.startswith("  "):
            if line.strip():
                break
            continue
        tail = line.split(": ", 1)[1] if ": " in line else line
        out.update(FILE.findall(tail))
    return out


def diff_files(p):
    try:
        return set(re.findall(r"^diff --git a/(\S+) b/", p.read_text(errors="replace"), re.M))
    except OSError:
        return set()


def analyse(r, mp):
    sb = r["sandbox"]
    for old, new in mp:
        if sb.startswith(old):
            sb = new + sb[len(old):]
            break
    d = Path(sb).parent
    tr = d / "transcript.jsonl"
    if not tr.exists():
        return None
    cs = C.calls(tr, r["driver"])
    edited = diff_files(d / "diff.patch")
    pushed, first, docs = set(), None, 0
    for i, c in enumerate(cs):
        if c["cat"] == "jk":
            f = pushed_files(c["text"] or "")
            if f:
                pushed |= f
                first = i if first is None else first
            docs += bool(DOCS.search(c["text"] or ""))
    rs = [c for c in cs if c["cat"] in ("read", "search")]
    push_reads = noise = 0
    if first is not None:
        for c in cs[first + 1:]:
            if c["cat"] in ("read", "search"):
                a = json.dumps(c["args"])
                hit = [f for f in pushed if f in a or Path(f).name in a]
                if hit:
                    push_reads += 1
                    if not any(f in edited for f in hit):
                        noise += 1
    return {"cfg": CFG[(r["driver"], r["model"])], "arm": r["_arm"], "task": r["task"], "run": r.get("run"),
            "kind": "decoy" if "decoy" in r["task"] else "trap", "green": r.get("outcome") == "green",
            "outcome": r.get("outcome"), "tokens": r.get("tokens"), "turns": r.get("turns"), "wall_s": r.get("wall_s"),
            "jk_s": r.get("jk_s"), "anchor_tail": r.get("anchor_tail"), "jk_build": r.get("jk_build"),
            "pushes": sum(1 for c in cs if c["cat"] == "jk" and PUSH.search(c["text"] or "")), "docs_lines": docs,
            "pushed_files": sorted(pushed), "pushed_fixed": sorted(f for f in pushed if f in edited),
            "push_reads": push_reads, "noise_reads": noise, "reads": len(rs), "edited": sorted(edited)}


def med(xs):
    xs = [x for x in xs if x is not None]
    return statistics.median(xs) if xs else None


def k(v):
    return "–" if v is None else f"{v / 1000:.1f}k"


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
    recs = []
    for spec in specs:
        label, path = spec.split("=", 1)
        keep = {}
        for line in open(path):
            if line.strip():
                r = json.loads(line)
                if (r.get("driver"), r.get("model")) in CFG:
                    r["_arm"] = label
                    keep[(r["driver"], r["model"], r["task"], r.get("run"))] = r
        recs += [x for x in (analyse(r, mp) for r in keep.values()) if x]
    if runs_out:
        with open(runs_out, "w") as fh:
            for x in recs:
                fh.write(json.dumps(x) + "\n")
    arms = sorted({x["arm"] for x in recs}, key=lambda s: ("O", "S", "Sc", "Sk").index(s) if s in ("O", "S", "Sc", "Sk") else 9)
    g = collections.defaultdict(list)
    for x in recs:
        g[(x["cfg"], x["arm"], x["kind"])].append(x)
    print("| Config | Arm | Kind | Runs | Green | Median tokens | Turns | Wall s | jk s | Reads+searches/run | Runs pushed | Pushed files fixed / named | Reads of pushed files/run | Of them on files left alone/run |")
    print("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|")
    for cfg in ORDER:
        for kind in ("trap", "decoy"):
            for arm in arms:
                rs = g.get((cfg, arm, kind))
                if not rs:
                    continue
                n = len(rs)
                named = sum(len(x["pushed_files"]) for x in rs); fixed = sum(len(x["pushed_fixed"]) for x in rs)
                print(f"| {cfg} | {arm} | {kind} | {n} | {sum(x['green'] for x in rs)}/{n} | {k(med([x['tokens'] for x in rs]))} | "
                      f"{med([x['turns'] for x in rs])} | {med([x['wall_s'] for x in rs]):.1f} | {med([x['jk_s'] for x in rs]) or 0:.1f} | "
                      f"{sum(x['reads'] for x in rs) / n:.2f} | {sum(x['pushes'] > 0 for x in rs)}/{n} | {fixed} / {named} | "
                      f"{sum(x['push_reads'] for x in rs) / n:.2f} | {sum(x['noise_reads'] for x in rs) / n:.2f} |")
    # per config verdict, S against O
    if "O" in arms and "S" in arms:
        print("\n| Config | Traps green O → S | Trap tokens O → S (sum of per-task medians) | Decoys green O → S | Decoy reads/run O → S | Decoy turns O → S (sum of per-task medians) | Noise reads/run in S (traps, decoys) |")
        print("|---|---|---|---|---|---|---|")
        for cfg in ORDER:
            def part(arm, kind):
                return [x for x in recs if x["cfg"] == cfg and x["arm"] == arm and x["kind"] == kind]
            if not part("O", "trap"):
                continue
            def summ(rs, key):
                by = collections.defaultdict(list)
                for x in rs:
                    by[x["task"]].append(x[key])
                return sum(med(v) or 0 for v in by.values())
            o_t, s_t, o_d, s_d = part("O", "trap"), part("S", "trap"), part("O", "decoy"), part("S", "decoy")
            if not (s_t and s_d):
                continue
            print(f"| {cfg} | {sum(x['green'] for x in o_t)}/{len(o_t)} → {sum(x['green'] for x in s_t)}/{len(s_t)} | "
                  f"{k(summ(o_t, 'tokens'))} → {k(summ(s_t, 'tokens'))} | {sum(x['green'] for x in o_d)}/{len(o_d)} → {sum(x['green'] for x in s_d)}/{len(s_d)} | "
                  f"{sum(x['reads'] for x in o_d) / len(o_d):.2f} → {sum(x['reads'] for x in s_d) / len(s_d):.2f} | "
                  f"{summ(o_d, 'turns'):g} → {summ(s_d, 'turns'):g} | "
                  f"{sum(x['noise_reads'] for x in s_t) / len(s_t):.2f}, {sum(x['noise_reads'] for x in s_d) / len(s_d):.2f} |")


if __name__ == "__main__":
    main()
