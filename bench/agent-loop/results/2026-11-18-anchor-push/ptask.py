"""ptask.py RUNS.jsonl: per trap task green O/S over all configs; the pushed runs; an A/A noise view."""
import json,sys,collections
rs=[json.loads(l) for l in open(sys.argv[1])]
g=collections.defaultdict(lambda:[0,0])
for r in rs:
    k=(r['task'],r['arm']); g[k][0]+=r['green']; g[k][1]+=1
print("| Task | O green | S green |\n|---|---:|---:|")
for t in sorted({r['task'] for r in rs}):
    o=g[(t,'O')]; s=g[(t,'S')]; print(f"| {t} | {o[0]}/{o[1]} | {s[0]}/{s[1]} |")
print("\nRuns where S showed a push:\n\n| Config | Task | Run | Green | Pushed files fixed / named | Reads of pushed files |\n|---|---|---:|---|---|---:|")
for r in rs:
    if r['pushes']:
        print(f"| {r['cfg']} | {r['task']} | {r['run']} | {r['outcome']} | {len(r['pushed_fixed'])} / {len(r['pushed_files'])} | {r['push_reads']} |")
# the same config+task in O for those
print("\nO runs on the same config and task:\n")
for r in rs:
    if r['pushes']:
        os_=[x['outcome'] for x in rs if x['arm']=='O' and x['cfg']==r['cfg'] and x['task']==r['task']]
        print(f"- {r['cfg']} {r['task']}: O {os_}")
