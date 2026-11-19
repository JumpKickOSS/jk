#!/usr/bin/env bash
# chain5 (sixth driver): chain4 again after its install failed on AnchorsLeftTest line 265, which asserts a guard verdict
# says nothing (the bug itself); this also drops that one assertion for the install. Was chain4: after chain3 (stage P) ends: build M2 = origin/main 750848aea + the one-word JK-HVWV7 fix (AnchorsLeft's
# REFRESHED also matches a gate's "OK guard" verdict) into its own home, warm it, then stage P' (push off vs silent
# on M2, 8 push tasks, 2 repeats). The fix is in the working tree only for the install. run/STOP5 stops it.
set -u
export PATH=$HOME/.jk/bin:$HOME/.grok/bin:$HOME/.local/bin:$PATH
B=/var/tmp/jk-bench; R=$B/run; J=$HOME/src/oss/jk; M=$B/jk-M2-home
F=server/engine/src/main/java/cc/jumpkick/engine/graph/AnchorsLeft.java
T=server/engine/src/test/java/cc/jumpkick/engine/graph/AnchorsLeftTest.java
export BENCH_RUN=$R BENCH_JK_BEFORE_HOME=$B/jk-0.51.1-home/.jk BENCH_JK_BENCH_HOME=$B/home BENCH_GATE=$B/GATE_RUNNING
export BENCH_AGENT_LOOP=$J/bench/agent-loop BENCH_JK_M_HOME=$M/.jk BENCH_NODE_BIN=$HOME/.nvm/versions/node/v24.21.0/bin
export JK_SELF_UPDATE=never
cd $R
step() { echo "$(date -Is) CHAIN5 $*"; }
stop() { step "STOP: $*"; step DONE; exit 2; }
step "START pid $$"
[ -f $R/STOP5 ] && stop "STOP5 present before build"
[ "$(git -C $J rev-parse HEAD)" = 750848aeaf8b621b330ca191f2a1d18a5f6e595c ] || stop "checkout not at 750848aea"
python3 - "$J/$F" <<'PY2' || stop "pattern line not found"
import sys
p = sys.argv[1]; s = open(p).read()
old = 'Pattern.compile("^OK (test|build|gate)\\\\b")'
if s.count(old) != 1:
    sys.exit(1)
open(p, "w").write(s.replace(old, 'Pattern.compile("^OK (test|build|gate|guard)\\\\b")'))
PY2
python3 - "$J/$T" <<'PY3' || { git -C $J checkout -q -- $F; stop "test line not found"; }
import sys
p = sys.argv[1]; s = open(p).read()
old = '        assertThat(AnchorsLeft.after(dir, "OK guard spring-petclinic \u00b7 8.3s\\n")).isEmpty();\n'
if s.count(old) != 1:
    sys.exit(1)
open(p, "w").write(s.replace(old, ""))
PY3
git -C $J diff -- $F $T
{
  echo "install $M from $(git -C $J rev-parse HEAD) + JK-HVWV7 fix (working tree) $(date -Is)"; mkdir -p $M
  ( cd $J && HOME=$M JK_HOME=$M/.jk JK_SELF_UPDATE=never timeout --foreground 5400 $HOME/.jk/bin/jk install < /dev/null 2>&1 | tail -40 )
  grep -q '^self-update' $M/.jk/config.toml 2>/dev/null || printf 'self-update = "never"\n' >> $M/.jk/config.toml
  HOME=$M JK_HOME=$M/.jk $M/.jk/bin/jk engine stop < /dev/null 2>&1 | tail -1
  echo "reinstall $M by itself $(date -Is)"
  ( cd $J && HOME=$M JK_HOME=$M/.jk JK_SELF_UPDATE=never timeout --foreground 5400 $M/.jk/bin/jk install < /dev/null 2>&1 | tail -40 )
  grep -E '^(version|engine-sha256)' $M/.jk/lib/jk-engine/jk-engine.toml; cat $M/.jk/config.toml
  HOME=$M JK_HOME=$M/.jk $M/.jk/bin/jk --version < /dev/null
  HOME=$M JK_HOME=$M/.jk $M/.jk/bin/jk engine stop < /dev/null 2>&1 | tail -1
  echo "installed $(date -Is)"
} > $R/install-M2b.log 2>&1
git -C $J checkout -q -- $F $T
tail -6 $R/install-M2b.log
step "checkout after install: $(git -C $J status --porcelain | tr '\n' ' ')"
[ -x $M/.jk/bin/jk ] || stop "no M2 jk"
hit=0
for jar in $(grep -rl --include='*.jar' 'cc/jumpkick/engine/graph/AnchorsLeft' $M/.jk/lib $M/.jk/store/repos/jk-local 2>/dev/null); do
  unzip -p "$jar" cc/jumpkick/engine/graph/AnchorsLeft.class 2>/dev/null | strings | grep -q 'gate|guard' && { hit=1; echo "fixed pattern in $jar"; }
done
[ $hit = 1 ] || stop "the installed engine does not carry the fixed pattern"
cd $J/bench/agent-loop
TS=""; for t in petclinic-push-column-h2 petclinic-push-decoy-getpet petclinic-push-config-key petclinic-push-decoy-limit-value petclinic-push-json-property petclinic-push-decoy-not-found petclinic-push-ts-client petclinic-push-ts-client-decoy; do TS="$TS --task $t"; done
step "warm M2"
JK_BENCH_HOME=$B/home JK_BENCH_CLAUDE=claude2 JK_HOME=$M/.jk JK_HTTP_PORT=0 PATH=$M/.jk/bin:$BENCH_NODE_BIN:$PATH AGENT_LOOP_HOME=$R/warm-M2/al \
  timeout --foreground 7200 ./change-tasks --driver claude-code --model claude-sonnet-5-5 --effort low --max-turns 2 --max-minutes 3 \
  --arm jk $TS --runs 1 --out $R/warm-M2/out </dev/null > $R/warm-M2.log 2>&1
cd $R
python3 -c "
import json
for l in open('$R/warm-M2/out/rows.jsonl'):
    r = json.loads(l); print(r['task'], r.get('jk_version'), r.get('jk_build'), r['outcome'])" > $R/warm-M2.rows 2>&1
cat $R/warm-M2.rows
n=$(wc -l < $R/warm-M2.rows); builds=$(awk '{print $3}' $R/warm-M2.rows | sort -u | tr '\n' ' ')
step "warm rows $n, builds: $builds"
[ "$n" -lt 8 ] && stop "warm: $n rows of 8"
[ "$(awk '{print $3}' $R/warm-M2.rows | sort -u | wc -l)" -ne 1 ] && stop "warm: more than one jk build"
case "$builds" in *193531a77b30*) stop "warm: M2 answered as M";; esac
[ -f $R/STOP5 ] && stop "STOP5 present before stage P'"
step stP2; python3 plan3.py --arms O,S --tasks P --repeats 2 --name stP2 > stP2-plan.json && python3 runner.py stP2-plan.json stP2.log
step DONE
