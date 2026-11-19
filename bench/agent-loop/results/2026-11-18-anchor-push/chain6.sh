#!/usr/bin/env bash
# chain6 (sixth driver): count (Sc) and kinds (Sk) on M2, the four trap tasks only (a decoy leaves nothing stale, so no mode
# shows anything there), for the configs where P' showed an effect: codex sol, cursor composer, muse. 2 repeats, 48 runs.
set -u
export PATH=$HOME/.jk/bin:$HOME/.grok/bin:$HOME/.local/bin:$PATH
B=/var/tmp/jk-bench; R=$B/run; J=$HOME/src/oss/jk; M=$B/jk-M2-home
export BENCH_RUN=$R BENCH_JK_BEFORE_HOME=$B/jk-0.51.1-home/.jk BENCH_JK_BENCH_HOME=$B/home BENCH_GATE=$B/GATE_RUNNING
export BENCH_AGENT_LOOP=$J/bench/agent-loop BENCH_JK_M_HOME=$M/.jk BENCH_NODE_BIN=$HOME/.nvm/versions/node/v24.21.0/bin
export JK_SELF_UPDATE=never
cd $R
step() { echo "$(date -Is) CHAIN6 $*"; }
step "START pid $$"
[ "$(git -C $J rev-parse HEAD)" = 750848aeaf8b621b330ca191f2a1d18a5f6e595c ] || { step "STOP checkout moved"; step DONE; exit 2; }
python3 plan3.py --arms Sc,Sk --tasks P --cfg codex-sol --cfg cursor-composer --cfg muse-spark --repeats 2 --name stP2k > stP2k-all.json || { step "STOP plan"; step DONE; exit 2; }
python3 - <<'PY'
import json
p = json.load(open("stP2k-all.json"))
p["runs"] = [r for r in p["runs"] if "decoy" not in r["task"]]
json.dump(p, open("stP2k-plan.json", "w"), indent=1)
print(len(p["runs"]), "runs")
PY
step stP2k; python3 runner.py stP2k-plan.json stP2k.log
step DONE
