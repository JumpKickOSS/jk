#!/usr/bin/env bash
# The ETA corpus: a fixed list of builds of jk's own tree, run by one engine in a private home, so
# score.py can hold every shape to the ETA targets with numbers instead of anecdotes.
#
#   bench/eta/corpus.sh --dist <checkout>/target/dist --worktree <checkout pinned to the workload>
#                       [--home DIR] [--label NAME] [--logs DIR] [--iterations N] [--only S3,S8]
#                       [--keep-home] [--no-fail-fast]
#
# --dist      the engine under test: a `jk build` dist (client + engine jar), installed into --home
# --worktree  the tree the corpus builds; edits are applied to fixed files and reverted at the end.
#             Use a dedicated worktree: the corpus edits, builds and tests in it.
# --home      the private JK_HOME (default ~/src/scratch/eta-home); wiped first unless --keep-home,
#             so S1 is a first build on an empty ledger
# --logs      where the logs, manifest and scores go (default ~/src/scratch/eta-corpus/<label>)
#
# Every run is spot-checked the moment it finishes (score.py --spot): its R0, ETA and bar errors
# print beside its exit, and the corpus stops at the first run that shows a broken estimate (no ETA,
# no rung on any task-finish, a warm ETA off by more than half late in the run), so a regression
# surfaces after minutes, not after the whole corpus. --no-fail-fast reports and runs on.
#
# Every edit is unique to its run (a counter in a private method's name), so a repeat never comes
# back as a cache hit. The manifest (run dir, scenario, iteration) lands in the logs directory;
# iteration 1 of each scenario is scored against the cold band, the rest against the warm one.
set -euo pipefail

HOME_DIR="$HOME/src/scratch/eta-home"
DIST=""
WT=""
LABEL="$(date +%Y%m%d-%H%M)"
ITER=3
ONLY=""
KEEP=0
FAIL_FAST=1
LOGS=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dist) DIST="$2"; shift 2 ;;
    --worktree) WT="$2"; shift 2 ;;
    --home) HOME_DIR="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --logs) LOGS="$2"; shift 2 ;;
    --iterations) ITER="$2"; shift 2 ;;
    --only) ONLY=",$2,"; shift 2 ;;
    --keep-home) KEEP=1; shift ;;
    --no-fail-fast) FAIL_FAST=0; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[[ -x "$DIST/jk" ]] || { echo "--dist must name a dist directory holding jk" >&2; exit 2; }
[[ -f "$WT/jk.toml" ]] || { echo "--worktree must name a jk checkout" >&2; exit 2; }
WT="$(cd "$WT" && pwd)"
DIST="$(cd "$DIST" && pwd)"
case "$HOME_DIR" in "$HOME/.jk"|"$HOME/.jk/") echo "refusing the default home" >&2; exit 2 ;; esac

LOGS="${LOGS:-$HOME/src/scratch/eta-corpus/$LABEL}"
mkdir -p "$LOGS"
LOGS="$(cd "$LOGS" && pwd)"
MANIFEST="$LOGS/manifest.tsv"
: > "$MANIFEST"
echo "# run_dir	scenario	iteration	exit	started" >> "$MANIFEST"

export JK_HOME="$HOME_DIR" JK_SELF_UPDATE=never
J="$JK_HOME/bin/jk"

if [[ $KEEP -eq 0 ]]; then
  [[ -x "$J" ]] && "$J" engine stop --now >/dev/null 2>&1 || true
  rm -rf "$HOME_DIR"
fi
mkdir -p "$HOME_DIR"
[[ -f "$HOME_DIR/config.toml" ]] || printf '[http]\nport = 0\n' > "$HOME_DIR/config.toml"
bash "$WT/install.sh" "$DIST/jk" > "$LOGS/install.log" 2>&1
echo "engine: $DIST ($(cd "$(dirname "$DIST")/.." && git rev-parse --short HEAD 2>/dev/null || echo '?'))" > "$LOGS/README"
echo "workload: $WT ($(git -C "$WT" rev-parse --short HEAD))" >> "$LOGS/README"

LEAF="$WT/clients/maven-spy/src/main/java/cc/jumpkick/maven/spy/MojoTimer.java"
MID="$WT/server/engine/src/main/java/cc/jumpkick/runtime/StepWalls.java"
CORE="$WT/shared/core/src/main/java/cc/jumpkick/config/ModuleOrder.java"
for f in "$LEAF" "$MID" "$CORE"; do [[ -f "$f" ]] || { echo "missing edit target $f" >&2; exit 2; }; done
N=0

# Insert a member before the class's last closing brace. A private method is a body-only edit (no
# API change); a public one changes the module's API and cascades to its dependents.
edit() {
  local file="$1" vis="$2"
  N=$((N + 1))
  local tag
  tag="$(date +%s)${N}"
  python3 -I - "$file" "$vis" "$tag" <<'PY'
import sys
path, vis, tag = sys.argv[1], sys.argv[2], sys.argv[3]
s = open(path, encoding="utf-8").read()
i = s.rstrip().rfind("}")
member = f"\n    {vis} static int etaCorpus{tag}() {{\n        return {len(tag)};\n    }}\n"
open(path, "w", encoding="utf-8").write(s[:i] + member + s[i:])
PY
}

# S12's added test classes: copies of a mid-weight engine test class, one per run, removed at the end.
ADDED_TESTS=()
TEST_SOURCE="$WT/server/engine/src/test/java/cc/jumpkick/engine/jobs/JobEnvelopeTest.java"
add_test() {
  N=$((N + 1))
  local name="EtaCorpusAdded$(date +%s)${N}Test"
  local file="$(dirname "$TEST_SOURCE")/$name.java"
  sed "s/\bJobEnvelopeTest\b/$name/g" "$TEST_SOURCE" > "$file"
  ADDED_TESTS+=("$file")
}

revert() {
  git -C "$WT" checkout -- "$LEAF" "$MID" "$CORE"
  for f in "${ADDED_TESTS[@]}"; do rm -f "$f"; done
}
trap revert EXIT

run_dir_of_last() {
  local proj
  proj="$(ls -d "$JK_HOME"/state/builds/projects/*/ 2>/dev/null | while read -r d; do
    if grep -q "$WT" "$d/identity.toml" 2>/dev/null; then echo "$d"; fi; done | head -1)"
  [[ -n "$proj" ]] || return 0
  echo "${proj}runs/$(cat "${proj}run-number.txt" 2>/dev/null | tr -d '[:space:]')"
}

# One scored run: the scenario, its iteration, then the jk argv.
scored() {
  local scenario="$1" iteration="$2"; shift 2
  local started rc
  started="$(date +%T)"
  echo "== $scenario #$iteration: jk $* ($started)" | tee -a "$LOGS/progress.txt"
  set +e
  (cd "$WT" && "$J" "$@") > "$LOGS/${scenario}-${iteration}.log" 2>&1
  rc=$?
  set -e
  echo "   exit=$rc $(date +%T)" | tee -a "$LOGS/progress.txt"
  local run warm=()
  run="$(run_dir_of_last)"
  printf '%s\t%s\t%s\t%s\t%s\n' "$run" "$scenario" "$iteration" "$rc" "$started" >> "$MANIFEST"
  [[ "$iteration" != 1 ]] && warm=(--warm)
  set +e
  python3 -I "$(dirname "$0")/score.py" --spot "$run" "${warm[@]}" | tee -a "$LOGS/progress.txt"
  local spot=${PIPESTATUS[0]}
  set -e
  if [[ "$spot" == 3 && "$FAIL_FAST" == 1 ]]; then
    echo "STOPPED: $scenario #$iteration shows a broken estimate (--no-fail-fast to run on)" | tee -a "$LOGS/progress.txt"
    "$J" engine stop --now >/dev/null 2>&1 || true
    exit 3
  fi
}

want() { [[ -z "$ONLY" || "$ONLY" == *",$1,"* ]]; }

# S1 runs once: the first build on an empty ledger, nothing but calibration to price it.
want S1 && scored S1-first-build 1 build

for i in $(seq 1 "$ITER"); do
  want S2 && scored S2-noop-build "$i" build
  want S3 && { edit "$LEAF" private; scored S3-leaf-body-build "$i" build; }
  want S4 && { edit "$MID" private; scored S4-engine-body-build "$i" build; }
  want S5 && { edit "$CORE" public; scored S5-core-api-build "$i" build; }
  want S6 && { edit "$LEAF" private; scored S6-leaf-body-skip-tests "$i" build --skip-tests; }
  want S7 && { edit "$MID" private; scored S7-engine-body-test "$i" test; }
  want S9 && { edit "$MID" private; scored S9-integration-engine-only "$i" test -p integration -m jk-engine; }
  want S10 && { edit "$MID" private; scored S10-class-filter "$i" test --class=TestEffortTest; }
  # A test class added to the engine's suite: its replayed wall did not run it (JK-4N8PA).
  want S12 && { add_test; scored S12-engine-test-added "$i" test -m jk-engine; }
done
# The long ones last, so a short-scenario regression shows up early in progress.txt.
for i in $(seq 1 "$ITER"); do
  want S8 && scored S8-integration-forced "$i" test -p integration -F
  want S11 && scored S11-forced-build "$i" build -F
done

"$J" engine stop --now >/dev/null 2>&1 || true
echo DONE | tee -a "$LOGS/progress.txt"
python3 -I "$(dirname "$0")/score.py" --home "$JK_HOME" --manifest "$MANIFEST" --min-wall 0 \
  --json "$LOGS/scores.jsonl" > "$LOGS/score.txt" 2>&1 || true
echo "scored: $LOGS/score.txt"
