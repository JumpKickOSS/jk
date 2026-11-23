#!/usr/bin/env bash
# Fixtures for the merge queue under scripts/queue/, in scratch repositories with a stand-in `jk`
# and a stand-in gate. Submitting refuses a branch whose tip has not passed the Branch tier; a
# batch applies every waiting branch onto origin/main once (a branch cut from another one does not
# carry its commits twice), ejects the branch that conflicts, gates the batch and fast-forwards
# origin/main to it; a red batch is split, apart from the branches that changed the module its failed
# class is in when there is one and in halves when there is not, so the good branches land and the
# bad one is ejected with the failing class, which its ticket is told along with the failure; a
# branch that leaves the queue any other way is recorded as dropped; when origin/main moves during a
# gate the queue does not force its push, and lands the batch on the new origin/main next time, or
# at once when what moved it is docs-only. A batch that touches only docs no test reads is gated
# with the docs tier; one that touches a doc a test reads, or code, with the Queue tier. While the
# pause file exists the loop starts no batch, even one it was waiting on the gate lock for, logs once
# that it is paused and why, and status says so; removing the file resumes it. Every landing and
# ejection is timed (ready, lock, gate, done), every batch line carries its wait and gate seconds,
# and status --stats prints the last 20 landings with their medians and the slowest three.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-queue.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
Q="$ROOT/scripts/queue"

fail() {
  echo "test-queue: $*" >&2
  exit 1
}

# The stand-in jk passes every Branch-tier step and logs each call; asked which modules a branch
# changed, it names jk-a by its jar under mod/a. The stand-in gate is red for a tip carrying a
# red.txt, naming its failed class as in the module red.txt holds, and with the failure beside the
# log; when MOVE_ORIGIN is set it pushes a commit of MOVE_FILE (default mod/a/moved.txt) to
# origin/main itself before it says GREEN, and when DROP_REF is set it deletes that branch's
# submission. It logs the tier it was asked for. The stand-in ka logs each comment.
mkdir -p "$WORK/bin"
cat >"$WORK/bin/jk" <<'JK'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$JK_LOG"
[ "$1" != show ] || printf 'cc.example:jk-a\t%s/mod/a/target/jk-a.jar\n' "$(git rev-parse --show-toplevel)"
JK
chmod +x "$WORK/bin/jk"
export JK_LOG="$WORK/jk.log"
export PATH="$WORK/bin:$PATH"
# macOS has no flock: there the loop's lock is a stand-in that runs the batch.
fake_flock=""
if ! command -v flock >/dev/null 2>&1; then
  fake_flock=1
  printf '%s\n' '#!/usr/bin/env bash' 'shift 3' 'exec "$@"' >"$WORK/bin/flock"
  chmod +x "$WORK/bin/flock"
fi
cat >"$WORK/gate.sh" <<'GATE'
#!/usr/bin/env bash
n="$1" tip="$2"
L="$GATE_LOG_DIR/integ-gate-$n.log"
echo "$n $GATE_TIER" >>"$GATE_LOG_DIR/tiers.log"
cd "$GATE_CHECKOUT" && git checkout -q --detach "$tip"
[ -z "${DROP_REF:-}" ] || git --git-dir="$DROP_IN" update-ref -d "refs/queue/$DROP_REF"
red="$(git ls-tree -r --name-only HEAD | grep -E '(^|/)red\.txt$' | head -1)"
if [ -n "$red" ]; then
  { echo "FAIL cc.example.RedTest"; echo "FAILED cc.example:$(cat "$red") cc.example.RedTest"; echo "RED"; } >"$L"
  printf '%s\n' "cc.example.RedTest#fails() (cc.example:$(cat "$red"))" "    at cc.example.RedTest.fails(RedTest.java:7)" \
    >"$GATE_LOG_DIR/g$n-failures.txt"
else
  if [ -n "${MOVE_ORIGIN:-}" ]; then
    git -C "$MOVE_ORIGIN" fetch -q origin && git -C "$MOVE_ORIGIN" reset -q --hard origin/main
    mf="${MOVE_FILE:-mod/a/moved.txt}"
    mkdir -p "$MOVE_ORIGIN/$(dirname "$mf")" && echo moved >"$MOVE_ORIGIN/$mf"
    git -C "$MOVE_ORIGIN" add "$mf" && git -C "$MOVE_ORIGIN" commit -q -m "written past the queue"
    git -C "$MOVE_ORIGIN" push -q origin HEAD:main
  fi
  echo "GREEN $(git rev-parse HEAD)" >"$L"
fi
echo "GATE$n DONE" >>"$L"
GATE
cat >"$WORK/bin/ka" <<'KA'
#!/usr/bin/env bash
printf '%s\n' "$*" >>"$KA_LOG"
KA
chmod +x "$WORK/bin/ka"
export KA_LOG="$WORK/ka.log"
: >"$KA_LOG"

export GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@example.invalid
export GIT_COMMITTER_NAME=dev GIT_COMMITTER_EMAIL=dev@example.invalid
when=1700000000
commit() { # <repo> <file> <content>: one commit, a second later than the one before
  when=$((when + 1))
  mkdir -p "$(dirname "$1/$2")"
  echo "$3" >"$1/$2"
  git -C "$1" add "$2"
  GIT_COMMITTER_DATE="@$when +0000" GIT_AUTHOR_DATE="@$when +0000" git -C "$1" commit -qm "$2: $3"
}

git init -q --bare -b main "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/seed" 2>/dev/null
# A workspace of two members, jk-a and jk-b, the script that maps a change onto them, and the one
# that tells a docs-only change, with one doc a test reads.
mkdir -p "$WORK/seed/mod/a" "$WORK/seed/mod/b" "$WORK/seed/scripts/gate"
printf '%s\n' '[workspace]' 'modules = ["mod/a", "mod/b"]' >"$WORK/seed/jk.toml"
echo 'name = "jk-a"' >"$WORK/seed/mod/a/jk.toml"
echo 'name = "jk-b"' >"$WORK/seed/mod/b/jk.toml"
cp "$ROOT/scripts/gate/changed-modules.sh" "$ROOT/scripts/gate/docs-only.sh" "$WORK/seed/scripts/gate/"
printf '%s\n' '# docs a test reads' docs/read.md >"$WORK/seed/scripts/gate/test-reads.txt"
git -C "$WORK/seed" add -A
commit "$WORK/seed" base.txt base
git -C "$WORK/seed" push -q origin HEAD:main
first_main="$(git -C "$WORK/seed" rev-parse HEAD)"
for c in dev co other; do git clone -q "$WORK/origin.git" "$WORK/$c"; done
git init -q --bare "$WORK/queue.git"
export JK_QUEUE_REMOTE="$WORK/queue.git"
dev="$WORK/dev"

# branch <name> <from> <file> <content>: a work branch with one commit, through the Branch tier.
branch() {
  git -C "$dev" checkout -q -b "$1" "$2"
  commit "$dev" "$3" "$4"
  (cd "$dev" && bash "$Q/branch-tier.sh" >/dev/null) || fail "the Branch tier refused $1"
}
submit() { (cd "$dev" && bash "$Q/submit.sh" "$1" >/dev/null 2>"$WORK/submit.err"); }
status() { (cd "$dev" && bash "$Q/status.sh" "$1"); }
# Greps a branch's status from a captured copy: piped into grep -q, status would die of SIGPIPE
# writing a line after the match, and pipefail would fail the check.
status_has() {
  local out
  out="$(status "$1")" || return 1
  grep "${@:2}" <<<"$out"
}
queue() {
  env QUEUE_REPO="$WORK/queue.git" QUEUE_CHECKOUT="$WORK/co" QUEUE_GATE="$WORK/gate.sh" \
    QUEUE_LOG_DIR="$WORK/logs" GATE_LOCK="$WORK/gate.lock" DROP_IN="$WORK/queue.git" "$@" bash "$Q/loop.sh" --once
}
origin_main() { git -C "$WORK/origin.git" rev-parse main; }
holds() { git -C "$WORK/origin.git" cat-file -e "main:$1" 2>/dev/null; }

# --- submitting ---
git -C "$dev" checkout -q -b untried origin/main
commit "$dev" x.txt x
if submit untried; then fail "a branch that never ran the Branch tier was submitted"; fi
grep -q 'has not passed the Branch tier' "$WORK/submit.err" || fail "the refusal does not say why: $(cat "$WORK/submit.err")"
branch stale origin/main y.txt y
commit "$dev" y.txt y2
if submit stale; then fail "a tip committed after its Branch tier was submitted"; fi

# The Branch tier tests what -e selects, by module path, and not the modules those build on.
grep -qx 'test --exclusive -m mod/a' "$JK_LOG" || fail "the Branch tier did not test mod/a alone: $(cat "$JK_LOG")"
grep -qx 'test -s tree' "$JK_LOG" || fail "the Branch tier did not run the tree suite: $(cat "$JK_LOG")"

# --- a batch: two branches land once each, the conflicting one is ejected ---
branch a origin/main base.txt from-a
branch b a b.txt b
branch c origin/main base.txt from-c
for x in a b c; do submit "$x" || fail "submitting $x failed: $(cat "$WORK/submit.err")"; done
[[ "$(status a)" == "a: waiting "* ]] || fail "a submitted branch is not waiting: $(status a)"
[[ "$(status untried || true)" == "untried: not in the queue" ]] || fail "a refused branch shows as queued"

queue || fail "the first batch did not run"
holds b.txt || fail "b did not land: $(git -C "$WORK/origin.git" log --oneline main)"
[[ "$(git -C "$WORK/origin.git" show main:base.txt)" == from-a ]] || fail "a did not land"
[[ "$(git -C "$WORK/origin.git" rev-list --count main)" -eq 3 ]] || fail "a's commit landed twice: $(git -C "$WORK/origin.git" log --oneline main)"
git -C "$WORK/origin.git" merge-base --is-ancestor "$first_main" main || fail "origin/main was not fast-forwarded"
[[ "$(status a)" == "a: landed "* && "$(status b)" == "b: landed "* ]] || fail "a and b do not show as landed: $(status a) / $(status b)"
status_has c -q '^c: ejected .*conflicts' || fail "c does not show as ejected: $(status c)"
status_has c -q 'conflicts: base.txt' || fail "c's ejection does not name the file: $(status c)"
status_has c -q 'tickets told: none' || fail "c's ejection does not say no ticket was told: $(status c)"
# Every landing and ejection records when it was ready, when its batch took the lock and gated,
# and when it ended; the batch's line ends with its wait and gate seconds.
T="$WORK/queue.git/queue-timings.log"
grep -qE '^batch 1 .* wait [0-9]+s gate [0-9]+s$' "$WORK/queue.git/queue-batches.log" ||
  fail "the batch log does not carry the wait and gate seconds: $(cat "$WORK/queue.git/queue-batches.log")"
read -r done_a _ <<<"$(grep ' landed a ' "$T")"
awk '$2 == "landed" && $3 == "a" && $8 <= $10 && $10 <= $12 && $12 <= $13 && $13 <= $1 && $1 > 1700000000 { f = 1 } END { exit !f }' "$T" ||
  fail "a's timings are not ready <= lock <= gate start <= gate end <= landed: $(cat "$T")"
grep -qE "^[0-9]+ landed b $(git -C "$dev" rev-parse --short=12 b) batch 1 " "$T" || fail "b's landing is not timed: $(cat "$T")"
grep -qE '^[0-9]+ ejected c [0-9a-f]{12} batch 1 ready [0-9]+ lock [0-9]+ gate - -$' "$T" ||
  fail "c's ejection before the gate is not timed so: $(cat "$T")"
[[ -n "$done_a" && ! -e "$WORK/queue.git/queue-ready/a" ]] || fail "a is still recorded as ready after landing"

# --- a red batch whose failed class names no branch's module is split in halves ---
git -C "$dev" fetch -q
branch d origin/main docs/d.txt d
branch e origin/main docs/red.txt nomodule
for x in d e; do submit "$x" || fail "submitting $x failed"; done
queue || fail "the red batch did not run"
status_has d -q '^d: waiting .* group [0-9.]*: .* split in halves' || fail "d was not split out of the red batch: $(status d)"
for _ in 1 2; do queue || fail "the split batches did not run"; done
holds docs/d.txt || fail "d did not land after the split"
if holds docs/red.txt; then fail "the red branch landed"; fi
status_has e -q 'FAIL cc.example.RedTest' || fail "e's ejection does not name the failing class: $(status e)"
grep -q 'RED-SPLIT in halves' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the split"

# --- one bad branch of three: the rest lands, the bad one alone is ejected and its ticket told ---
git -C "$dev" fetch -q
branch g origin/main mod/a/A.java g
branch w/QA-BAD01 origin/main mod/b/red.txt jk-b
branch i origin/main docs/i.txt i
for x in g w/QA-BAD01 i; do submit "$x" || fail "submitting $x failed"; done
batches="$(wc -l <"$WORK/queue.git/queue-batches.log")"
queue || fail "the red batch of three did not run"
status_has w/QA-BAD01 -q 'waiting .* group [0-9.]*: .* split by the modules' ||
  fail "the branch that changed the failing module was not split out: $(status w/QA-BAD01)"
# j, submitted after the split, waits for the groups; its submission is lost while it waits, and
# the queue records that rather than forget it.
branch j origin/main docs/j.txt j
submit j || fail "submitting j failed"
queue DROP_REF=j || fail "the rest of the red batch did not run"
holds mod/a/A.java || fail "g did not land with the rest: $(status g)"
holds docs/i.txt || fail "i did not land with the rest: $(status i)"
if holds docs/j.txt; then fail "a branch submitted after the split was gated with the rest"; fi
status_has j -q '^j: dropped ' || fail "a lost branch is not recorded as dropped: $(status j || true)"
grep -q 'DROPPED j@' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the drop"
queue || fail "the suspect alone did not run"
if holds mod/b/red.txt; then fail "the bad branch landed"; fi
[[ "$(grep -c '^batch [0-9]* [0-9T:-]*Z ' <(tail -n +$((batches + 1)) "$WORK/queue.git/queue-batches.log"))" -eq 3 ]] ||
  fail "one bad branch of three took other than three batches: $(tail -4 "$WORK/queue.git/queue-batches.log")"
status_has w/QA-BAD01 -q '^w/QA-BAD01: ejected ' || fail "the bad branch was not ejected: $(status w/QA-BAD01)"
status_has w/QA-BAD01 -qx 'tickets told: QA-BAD01' || fail "the ticket was not told: $(status w/QA-BAD01)"
grep -q '^comment QA-BAD01 -m The merge queue ejected .w/QA-BAD01.' "$KA_LOG" || fail "no comment on the ticket: $(cat "$KA_LOG")"
grep -qx '    FAILED cc.example:jk-b cc.example.RedTest' "$KA_LOG" || fail "the ticket was not told the failing class: $(cat "$KA_LOG")"
grep -q 'at cc.example.RedTest.fails(RedTest.java:7)' "$KA_LOG" || fail "the ticket was not told the failure: $(cat "$KA_LOG")"

# --- origin/main moves during the gate: no forced push; the batch lands on the new main ---
git -C "$dev" fetch -q
branch f origin/main docs/f.txt f
submit f || fail "submitting f failed"
queue MOVE_ORIGIN="$WORK/other" 2>/dev/null || fail "the batch with a moving origin did not run"
moved="$(git -C "$WORK/other" rev-parse HEAD)"
[[ "$(origin_main)" == "$moved" ]] || fail "the queue overwrote a commit it did not gate"
status_has f -q '^f: waiting .*moved' || fail "f is not waiting again: $(status f)"
grep -q ' MOVED f@' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the move"
queue || fail "the batch after the move did not run"
holds docs/f.txt || fail "f did not land after origin/main moved"
git -C "$WORK/origin.git" merge-base --is-ancestor "$moved" main || fail "the landing dropped the commit that moved origin/main"

# --- a docs-only batch runs the docs tier; a doc a test reads, or code, the Queue tier ---
tier_of() { awk -v n="$1" '$1 == n { print $2 }' "$WORK/logs/tiers.log"; }
batch_n() { cat "$WORK/queue.git/queue-batch-n"; }
git -C "$dev" fetch -q
branch k origin/main docs/k.md k
submit k || fail "submitting k failed"
queue || fail "the docs-only batch did not run"
holds docs/k.md || fail "the docs-only branch did not land: $(status k)"
[[ "$(tier_of "$(batch_n)")" == docs ]] || fail "a docs-only batch was not gated with the docs tier: $(cat "$WORK/logs/tiers.log")"
tail -1 "$WORK/queue.git/queue-batches.log" | grep -q ' docs-only LANDED' || fail "the batch log does not say docs-only: $(tail -1 "$WORK/queue.git/queue-batches.log")"
git -C "$dev" fetch -q
branch l origin/main docs/read.md l
submit l || fail "submitting l failed"
queue || fail "the batch of a doc a test reads did not run"
[[ "$(tier_of "$(batch_n)")" == queue ]] || fail "a doc a test reads skipped the Queue tier: $(cat "$WORK/logs/tiers.log")"
git -C "$dev" fetch -q
branch m origin/main docs/m.md m
commit "$dev" mod/b/M.java m
(cd "$dev" && bash "$Q/branch-tier.sh" >/dev/null) || fail "the Branch tier refused m"
submit m || fail "submitting m failed"
queue || fail "the batch of docs and code did not run"
[[ "$(tier_of "$(batch_n)")" == queue ]] || fail "a batch with code skipped the Queue tier: $(cat "$WORK/logs/tiers.log")"

# --- origin/main moved by docs-only commits during the gate: the verdict holds, the batch lands ---
git -C "$dev" fetch -q
branch o origin/main mod/a/O.java o
submit o || fail "submitting o failed"
queue MOVE_ORIGIN="$WORK/other" MOVE_FILE=docs/moved.md 2>/dev/null || fail "the batch with a docs-only move did not run"
moved="$(git -C "$WORK/other" rev-parse HEAD)"
holds mod/a/O.java || fail "o did not land over a docs-only move: $(status o)"
git -C "$WORK/origin.git" merge-base --is-ancestor "$moved" main || fail "the landing dropped the docs-only commit"
status_has o -q '^o: landed .* after docs-only commits moved origin/main' || fail "o's record does not say why it landed: $(status o)"
[[ "$(git -C "$WORK/origin.git" rev-list --count "$moved..main")" -eq 1 ]] || fail "o landed other than once on the new main"

# --- nothing waits: no batch ---
lines="$(wc -l <"$WORK/queue.git/queue-batches.log")"
queue || fail "an empty queue failed"
[[ "$(wc -l <"$WORK/queue.git/queue-batches.log")" -eq "$lines" ]] || fail "an empty queue ran a batch"

# --- paused: no batch starts, even the one the loop waited on the gate lock for, however many looks
# it takes; the loop says so once with the reason, status says so; removing the file resumes it ---
git -C "$dev" fetch -q
branch p origin/main docs/p.md p
submit p || fail "submitting p failed"
gates="$(wc -l <"$WORK/logs/tiers.log")"
echo "a bench night" >"$WORK/queue.git/pause"
rc=0
env QUEUE_REPO="$WORK/queue.git" QUEUE_CHECKOUT="$WORK/co" QUEUE_GATE="$WORK/gate.sh" QUEUE_LOG_DIR="$WORK/logs" \
  bash "$Q/loop.sh" --batch || rc=$?
[[ "$rc" -eq 4 ]] || fail "a batch started while paused exited $rc, want 4"
# Where flock is real, the loop is already waiting on the gate lock when the pause starts.
if [[ -z "$fake_flock" ]]; then
  rm "$WORK/queue.git/pause"
  exec 8>>"$WORK/gate.lock"
  flock 8
fi
queue QUEUE_POLL=1 >"$WORK/paused.out" 2>&1 8>&- &
looping=$!
if [[ -z "$fake_flock" ]]; then
  sleep 1
  echo "a bench night" >"$WORK/queue.git/pause"
  flock -u 8
  exec 8>&-
fi
for _ in $(seq 100); do grep -q '^queue: paused' "$WORK/paused.out" && break; sleep 0.1; done
sleep 3
kill -0 "$looping" 2>/dev/null || fail "the paused loop ended: $(cat "$WORK/paused.out")"
[[ "$(wc -l <"$WORK/logs/tiers.log")" -eq "$gates" ]] || fail "a paused loop started a gate"
[[ "$(grep -c '^queue: paused .*: a bench night (' "$WORK/paused.out")" -eq 1 ]] ||
  fail "the loop does not say once that it is paused and why: $(cat "$WORK/paused.out")"
status_has p -q '^p: waiting ' || fail "p does not wait through the pause: $(status p)"
status_has p -qx 'the queue is paused: a bench night; it starts no batch until the pause file is removed' ||
  fail "status does not say the queue is paused: $(status p)"
rm "$WORK/queue.git/pause"
for _ in $(seq 200); do kill -0 "$looping" 2>/dev/null || break; sleep 0.1; done
if kill -0 "$looping" 2>/dev/null; then kill "$looping"; fail "the loop did not resume: $(cat "$WORK/paused.out")"; fi
wait "$looping" || fail "the resumed loop failed: $(cat "$WORK/paused.out")"
grep -q '^queue: resumed' "$WORK/paused.out" || fail "the loop does not say it resumed: $(cat "$WORK/paused.out")"
holds docs/p.md || fail "p did not land after the pause: $(status p)"
status_has p -q 'paused' && fail "status still says paused: $(status p)"

# --- --stats: the last 20 landings, ready to landed, lock wait and gate, their medians and the
# slowest three; an ejection is not a landing, and a time the queue does not know is left out ---
mkdir "$WORK/stats.git"
cat >"$WORK/stats.git/queue-timings.log" <<'LOG'
1000 landed x1 aaaaaaaaaaaa batch 1 ready 400 lock 500 gate 520 940
2000 landed x2 bbbbbbbbbbbb batch 2 ready 1000 lock 1300 gate 1310 1900
3000 ejected x3 cccccccccccc batch 3 ready 100 lock 2600 gate 2610 2900
3500 landed x4 dddddddddddd batch 4 ready 3000 lock 3100 gate 3110 3400
4000 landed x5 eeeeeeeeeeee batch 5 ready - lock 3700 gate 3710 3990
LOG
stats="$(cd "$dev" && JK_QUEUE_REMOTE="$WORK/stats.git" bash "$Q/status.sh" --stats)" || fail "--stats failed: $stats"
grep -qE '^x2 +2 +16m40s +5m00s +9m50s$' <<<"$stats" || fail "--stats does not show x2's times: $stats"
grep -qE '^x5 +5 +- +- +4m40s$' <<<"$stats" || fail "--stats does not show a landing with no ready time: $stats"
if grep -q '^x3 ' <<<"$stats"; then fail "--stats counts an ejection as a landing: $stats"; fi
grep -qE '^median of 4 +10m00s +1m40s +5m55s$' <<<"$stats" || fail "--stats medians are wrong: $stats"
[[ "$(sed -n '/^slowest:/,$p' <<<"$stats")" == $'slowest:\n  x2 (batch 2) 16m40s\n  x1 (batch 1) 10m00s\n  x4 (batch 4) 8m20s' ]] ||
  fail "--stats does not name the slowest three: $stats"
for i in $(seq 25); do echo "$((5000 + i)) landed old$i ffffffffffff batch $((5 + i)) ready 5000 lock 5000 gate 5000 5000"; done \
  >>"$WORK/stats.git/queue-timings.log"
stats="$(cd "$dev" && JK_QUEUE_REMOTE="$WORK/stats.git" bash "$Q/status.sh" --stats)" || fail "--stats failed: $stats"
grep -q '^median of 20 ' <<<"$stats" || fail "--stats does not take the last 20 landings: $stats"
if ! grep -q '^old6 ' <<<"$stats" || grep -q '^old5 ' <<<"$stats"; then fail "--stats does not take the latest 20: $stats"; fi
stats="$(cd "$dev" && JK_QUEUE_REMOTE="$WORK/queue.git" bash "$Q/status.sh" --stats)" || fail "--stats failed on the queue: $stats"
grep -qE '^a +1 +[0-9]+m[0-9]{2}s ' <<<"$stats" || fail "--stats does not show the queue's own landings: $stats"

echo "test-queue: ok ($(($(wc -l <"$WORK/queue.git/queue-batches.log"))) batches)"
