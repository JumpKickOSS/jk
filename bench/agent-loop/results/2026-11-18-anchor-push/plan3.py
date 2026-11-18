#!/usr/bin/env python3
"""Plans for stage R and stage P (one build, arm M = origin/main in its own home) for runner.py.

    plan3.py --arms M|O,S[,Sc,Sk] --tasks R|P [--cfg NAME ...] [--repeats N] [--first-run K] --name BATCH > plan.json

All arms are change-tasks `--arm jk` run by $BENCH_JK_M_HOME's jk; they differ only in $AGENT_LOOP_ANCHOR_TAIL:
  M  stage R (unset: main's default, anchor-tail off)
  O  stage P, off (unset);  S  silent;  Sc  count;  Sk  kinds
Stage P puts $BENCH_NODE_BIN (Node.js 22.6+) on PATH so the ts-client pair's hidden node test runs; stage R does
not, as the sliver's arms did not. Interleaved: every cell's run k before any run k+1; arm order rotates."""
import argparse
import json
import os

RUN = os.environ["BENCH_RUN"]
HOME = os.environ["BENCH_JK_M_HOME"]
NODE = os.environ.get("BENCH_NODE_BIN", "")
COMMON_ENV = {
    "MUSE_NO_AUTO_UPDATE": "1",
    "AGY_CLI_DISABLE_AUTO_UPDATE": "1",
    "JK_BENCH_CLAUDE": "claude2",
    "JK_BENCH_HOME": os.environ["BENCH_JK_BENCH_HOME"],
    "JK_BEFORE_HOME": os.environ["BENCH_JK_BEFORE_HOME"],
}
TAIL = {"M": None, "O": None, "S": "silent", "Sc": "count", "Sk": "kinds"}
# The two configs the budget drops first from stage P (cursor grok-4.7-high, then grok-4.5) run last in each round.
CONFIGS = {
    "sonnet-5-5": ("claude-code", "claude-sonnet-5-5", "medium", None),
    "opus-5-5": ("claude-code", "claude-opus-5-5", "medium", None),
    "codex-terra": ("codex", "gpt-6.1-terra", "low", None),
    "codex-sol": ("codex", "gpt-6.1-sol", "low", None),
    "grok-4.7": ("grok", "grok-4.7", "high", 256000),
    "cursor-composer": ("cursor", "composer-2.5", "default", None),
    "agy-hydrogen": ("agy", "gemini-4-hydrogen-medium", "medium", None),
    "muse-spark": ("muse", "muse-spark-1.3-contributor", "high", None),
    "grok-4.5": ("grok", "grok-4.5", "high", None),
    "cursor-grok-4.7": ("cursor", "grok-4.7-high", "high", None),
}
# Stage R: the sliver's four tasks (20 min cap) and tier 1's petclinic task (15 min cap, as tier 1 ran it).
TASKS = {
    "R": [("petclinic-rename-telephone", 20), ("petclinic-anchor-relaxed-env", 20),
          ("petclinic-anchor-composed-route", 20), ("petclinic-anchor-column-case", 20),
          ("petclinic-callers-addpet", 15)],
    "P": [(t, 20) for t in ("petclinic-push-column-h2", "petclinic-push-decoy-getpet",
                            "petclinic-push-config-key", "petclinic-push-decoy-limit-value",
                            "petclinic-push-json-property", "petclinic-push-decoy-not-found",
                            "petclinic-push-ts-client", "petclinic-push-ts-client-decoy")],
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--arms", required=True)
    ap.add_argument("--tasks", required=True, choices=TASKS)
    ap.add_argument("--cfg", action="append")
    ap.add_argument("--repeats", type=int, default=2)
    ap.add_argument("--first-run", type=int, default=1)
    ap.add_argument("--name", required=True)
    ns = ap.parse_args()
    arms = ns.arms.split(",")
    cfgs = ns.cfg or list(CONFIGS)
    base = f"{RUN}/{ns.name}"
    node = NODE + ":" if NODE and ns.tasks == "P" else ""
    runs = []
    for k in range(ns.first_run, ns.first_run + ns.repeats):
        for ci, cfg in enumerate(cfgs):
            driver, model, effort, context = CONFIGS[cfg]
            shift = (k + ci) % len(arms)
            order = arms[shift:] + arms[:shift]
            for task, minutes in TASKS[ns.tasks]:
                for a in order:
                    cmd = ["./change-tasks", "--driver", driver, "--model", model, "--effort", effort,
                           "--task", task, "--arm", "jk", "--max-minutes", str(minutes), "--max-turns", "60"]
                    if context:
                        cmd += ["--context", str(context)]
                    env = dict(COMMON_ENV)
                    env.update({"JK_HOME": HOME, "JK_HTTP_PORT": "0", "PATH": HOME + "/bin:" + node + os.environ["PATH"],
                                "AGENT_LOOP_HOME": f"{base}/al-{a}/{cfg}"})
                    if TAIL[a]:
                        env["AGENT_LOOP_ANCHOR_TAIL"] = TAIL[a]
                    cmd += ["--runs", "1", "--first-run", str(k), "--out", f"{base}/out-{a}"]
                    runs.append({"cfg": cfg, "arm": a, "repeat": k, "task": task, "env": env, "cmd": cmd,
                                 "cap_s": minutes * 60 + 900})
    print(json.dumps({"tier": ns.name, "rows_globs": [f"{base}/out-{a}/rows.jsonl" for a in arms], "runs": runs}, indent=1))


if __name__ == "__main__":
    main()
