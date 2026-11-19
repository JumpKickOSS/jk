"""pconv.py RUNS.jsonl [LABEL]: paired by config, task and repeat, S against O on traps: conversions (S green, O not) and
losses (O green, S not), split by whether S showed a push; pooled trap green and one-sided Fisher exact tests."""
import json, sys, collections
from math import comb
rs = [json.loads(l) for l in open(sys.argv[1])]
idx = {(r['cfg'], r['task'], r['run'], r['arm']): r for r in rs}
def fisher_one(a, n1, b, n2):
    # P(S green >= b | margins), hypergeometric; a,b greens of O (n1), S (n2)
    k = a + b; N = n1 + n2
    return sum(comb(n2, x) * comb(n1, k - x) for x in range(b, min(k, n2) + 1)) / comb(N, k)
per = collections.defaultdict(lambda: collections.Counter())
for (cfg, task, run, arm), s in idx.items():
    if arm != 'S' or s['kind'] != 'trap': continue
    o = idx.get((cfg, task, run, 'O'))
    if not o: continue
    p = 'pushed' if s['pushes'] else 'not pushed'
    if s['green'] and not o['green']: per[cfg]['conv ' + p] += 1
    if o['green'] and not s['green']: per[cfg]['loss ' + p] += 1
print("| Config | Conversions, pushed | Conversions, not pushed | Losses, pushed | Losses, not pushed |\n|---|---:|---:|---:|---:|")
tot = collections.Counter()
for cfg in sorted({r['cfg'] for r in rs}):
    c = per[cfg]; tot.update(c)
    print(f"| {cfg} | {c['conv pushed']} | {c['conv not pushed']} | {c['loss pushed']} | {c['loss not pushed']} |")
print(f"| all | {tot['conv pushed']} | {tot['conv not pushed']} | {tot['loss pushed']} | {tot['loss not pushed']} |")
def pool(pred):
    g = collections.Counter(); n = collections.Counter()
    for r in rs:
        if pred(r): g[r['arm']] += r['green']; n[r['arm']] += 1
    return g['O'], n['O'], g['S'], n['S']
for name, pred in [("all traps", lambda r: r['kind'] == 'trap')] + [
        (t, (lambda t: lambda r: r['task'] == t)(t)) for t in sorted({r['task'] for r in rs if r['kind'] == 'trap'})]:
    a, n1, b, n2 = pool(pred)
    print(f"- {name}: O {a}/{n1}, S {b}/{n2}, one-sided Fisher p(S >= observed) = {fisher_one(a, n1, b, n2):.3f}")
