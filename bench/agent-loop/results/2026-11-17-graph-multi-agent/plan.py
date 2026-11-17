#!/usr/bin/env python3
"""Write a tier's plan for runner.py.   plan.py t0|t1|t2 [--cfg NAME ...] [--arms A,B,C] > plan.json

$BENCH_RUN (the run directory), $BENCH_JK_BEFORE_HOME (jk 0.51.1's home) and $BENCH_JK_BENCH_HOME (harness
scratch) default to the first host's (bocabox) paths."""
import argparse
import json

import os

RUN = os.environ.get("BENCH_RUN", "/var/tmp/jk-review2/bench-run")
COMMON_ENV = {
    "JK_BEFORE_HOME": os.environ.get("BENCH_JK_BEFORE_HOME", "/var/tmp/jk-review2/bench-smoke/jk-0.51.1-home/.jk"),
    "MUSE_NO_AUTO_UPDATE": "1",
    "AGY_CLI_DISABLE_AUTO_UPDATE": "1",
    "JK_BENCH_CLAUDE": "claude2",
}
if os.environ.get("BENCH_TEST_EXCLUDE"):
    # Test classes this host cannot run, left out of a jk sandbox's suites so the agent's gate runs what the verify runs.
    COMMON_ENV["AGENT_LOOP_TEST_EXCLUDE"] = os.environ["BENCH_TEST_EXCLUDE"]
# Arm F: arm `jk` run by a private home's jk (main with the graph fixes), $BENCH_JK_F_HOME; its rows go to their own
# out/ and agent-loop home so they never mix with arm A's.
F_HOME = os.environ.get("BENCH_JK_F_HOME")
if os.environ.get("BENCH_JK_BENCH_HOME"):
    # The corpus (graph-retrieval) and every other harness scratch under the bench's own directory.
    COMMON_ENV["JK_BENCH_HOME"] = os.environ["BENCH_JK_BENCH_HOME"]
# name: (driver, model, effort, context)
CONFIGS = {
    "sonnet-5-5": ("claude-code", "claude-sonnet-5-5", "medium", None),
    "opus-5-5": ("claude-code", "claude-opus-5-5", "medium", None),
    "codex-terra": ("codex", "gpt-6.1-terra", "low", None),
    "codex-sol": ("codex", "gpt-6.1-sol", "low", None),
    "grok-4.7": ("grok", "grok-4.7", "high", 256000),
    "grok-4.5": ("grok", "grok-4.5", "high", None),
    "cursor-composer": ("cursor", "composer-2.5", "default", None),
    "cursor-grok-4.7": ("cursor", "grok-4.7-high", "high", None),
    "agy-hydrogen": ("agy", "gemini-4-hydrogen-medium", "medium", None),
    "muse-spark": ("muse", "muse-spark-1.3-contributor", "high", None),
}
ARM = {"A": "jk", "B": "jk-before", "C": "jk-nograph", "F": "jk"}
TIERS = {
    "t0": dict(tool="smoke-test", tasks=["smoke-callers", "smoke-rename", "smoke-rename-field", "smoke-rename-class"],
               arms="ABC", repeats=1, minutes=8, turns=30),
    "t1": dict(tool="change-tasks", tasks=["petclinic-callers-addpet", "gs-multi-module-rename-message",
                                          "gs-uploading-rename-loadasresource"],
               arms="AC", repeats=3, minutes=15, turns=60),
    "t2": dict(tool="change-tasks", tasks=["petclinic-rename-telephone", "petclinic-anchor-relaxed-env",
                                          "petclinic-anchor-composed-route", "petclinic-anchor-column-case"],
               arms="ABC", repeats=3, minutes=20, turns=60),
}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tier")
    ap.add_argument("--cfg", action="append")
    ap.add_argument("--arms")
    ap.add_argument("--repeats", type=int)
    ap.add_argument("--first-run", type=int, default=1)
    ap.add_argument("--task", action="append")
    ap.add_argument("--name", help="batch directory under $BENCH_RUN (default: the tier)")
    ns = ap.parse_args()
    t = TIERS[ns.tier]
    arms = ns.arms or t["arms"]
    repeats = ns.repeats or t["repeats"]
    cfgs = ns.cfg or list(CONFIGS)
    tasks = ns.task or t["tasks"]
    base = f"{RUN}/{ns.name or ns.tier}"
    if "F" in arms and not F_HOME:
        raise SystemExit("arm F needs $BENCH_JK_F_HOME")
    runs = []
    # Interleaved: every cell's run k before any cell's run k+1; the arm order rotates per round and config.
    for k in range(ns.first_run, ns.first_run + repeats):
        for ci, cfg in enumerate(cfgs):
            driver, model, effort, context = CONFIGS[cfg]
            shift = (k + ci) % len(arms)
            order = arms[shift:] + arms[:shift]
            for task in tasks:
                for a in order:
                    cmd = ["./" + t["tool"], "--driver", driver, "--model", model, "--effort", effort,
                           "--task", task, "--arm", ARM[a], "--max-minutes", str(t["minutes"]),
                           "--max-turns", str(t["turns"])]
                    if context:
                        cmd += ["--context", str(context)]
                    env = dict(COMMON_ENV)
                    sub = "-F" if a == "F" else ""
                    if a == "F":
                        env.update({"JK_HOME": F_HOME, "JK_HTTP_PORT": "0",
                                    "PATH": F_HOME + "/bin:" + os.environ["PATH"]})
                    if t["tool"] == "smoke-test":
                        env["AGENT_LOOP_HOME"] = f"{base}/al{sub}"
                    else:
                        # change-tasks' run directories are driver/arm/task.run: one home per config keeps two
                        # models of one driver apart.
                        env["AGENT_LOOP_HOME"] = f"{base}/al{sub}/{cfg}"
                        cmd += ["--runs", "1", "--first-run", str(k), "--out", f"{base}/out{sub}"]
                    runs.append({"cfg": cfg, "env": env, "cmd": cmd, "cap_s": t["minutes"] * 60 + 900})
    subs = [""] + (["-F"] if "F" in arms else [])
    globs = ([f"{base}/al{x}/smoke/*/rows.jsonl" for x in subs] if t["tool"] == "smoke-test"
             else [f"{base}/out{x}/rows.jsonl" for x in subs])
    print(json.dumps({"tier": ns.tier, "rows_globs": globs, "runs": runs}, indent=1))


if __name__ == "__main__":
    main()
