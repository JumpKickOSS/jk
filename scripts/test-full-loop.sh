#!/usr/bin/env bash
# Fixtures for the Full tier's loop, scripts/gate/full-loop.sh, beside the merge queue in scratch
# repositories with a stand-in `jk`, `ka` and gate. A green tip of origin/main moves origin's green
# branch to it, and a tip already judged is not gated again. A red tip names the batch that made it
# red, found by bisecting the batches landed since green with each commit gated once. When the
# classes it failed fail with one of its commits and pass on that commit's parent, the loop submits
# the commit's revert to the queue, comments on its ticket and reopens it; when they pass on a
# re-run, or the revert conflicts with later work, it reverts nothing and comments on the batch's
# tickets why. After two red cycles the queue lands only the fix (a revert of the batch's commit, or
# a commit for its ticket) and holds the rest, until a green cycle lifts the hold. Two gates in a row
# green only because a class passed on the gate's re-run are green cycles that name the class: green
# moves, no ticket is told, and the queue is not held. While the pause file exists the loop starts no
# cycle, even one it was waiting on the gate lock for, and says once in full-runs.log that it is
# paused and why; removing the file resumes it. A jk that cannot start its engine, before a gate or
# when a gate is red at guard, is an infra failure: no verdict, no culprit, no red cycle, no hold. A
# red cycle after an earlier red gate for something else names the batch whose gate fails what this
# one fails, not the earlier one; a red in no class or fixture names no batch and never holds the
# queue, and a red set unlike the last one counts as the first red cycle again.
#
# The loop runs as on a host of its own: it reaches the queue's state only over ssh (a stand-in that
# runs the command here for the host queuehost), takes a gate lock that is not the queue's, and
# neither waits for the queue's lock nor holds it; when the queue's host cannot be reached it gates
# nothing.
set -euo pipefail

unset "${!GATE_@}" "${!FULL_@}" "${!QUEUE_@}" JK_HOME

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-full-loop.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
Q="$ROOT/scripts/queue"

fail() {
  echo "test-full-loop: $*" >&2
  exit 1
}

# The stand-in jk passes the Branch tier, and cannot start its engine while jk-broken exists; ka logs each call and says every ticket is done. The
# stand-in gate passes the Queue tier always and the Full tier unless the tip carries red.txt or
# flaky.txt, when cc.example.RedTest fails, and logs each Full gate's tip and base; on a tip whose
# own commit writes once.txt the class passes on the gate's re-run, and the gate is green naming it. A tip
# whose own commit writes guard.txt is red at guard; one that carries other.txt fails
# cc.example.OtherTest. The stand-in
# re-run fails that class on a commit that carries red.txt, and logs each commit it runs on.
mkdir -p "$WORK/bin"
cat >"$WORK/bin/jk" <<JK
#!/usr/bin/env bash
if [ "\$1 \$2" = "engine start" ] && [ -e "$WORK/jk-broken" ]; then
  echo "jk: ! Engine > no build engine for JumpKick 9.9.9-SNAPSHOT"
  exit 70
fi
exit 0
JK
cat >"$WORK/bin/ka" <<'KA'
#!/usr/bin/env bash
printf '%s|' "$@" >>"$KA_LOG"
echo >>"$KA_LOG"
[ "$1" != show ] || echo '{"status":"done"}'
KA
# The stand-in ssh knows one host, queuehost, and runs the command it is given here, as git and
# scripts/queue/common.sh give it: the options first, then the host and the command.
cat >"$WORK/bin/ssh" <<'SSH'
#!/usr/bin/env bash
while [ "${1#-}" != "$1" ]; do case "$1" in -[opliF]) shift 2 ;; *) shift ;; esac; done
echo "$1" >>"$SSH_LOG"
[ "$1" = queuehost ] || { echo "ssh: Could not resolve hostname $1" >&2; exit 255; }
shift
exec bash -c "$*"
SSH
chmod +x "$WORK/bin/jk" "$WORK/bin/ka" "$WORK/bin/ssh"
export PATH="$WORK/bin:$PATH" KA_LOG="$WORK/ka.log" SSH_LOG="$WORK/ssh.log"
: >"$KA_LOG"
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
cd "$GATE_CHECKOUT" && git checkout -q --detach "$tip"
# The install under the gate loses its engine: guard cannot start.
if [ -n "${FIXTURE_GATE_BREAKS_JK:-}" ]; then
  touch "$FIXTURE_GATE_BREAKS_JK"
  { echo "GUARD_EXIT=70"; echo "RED (guard)"; echo "GATE$n DONE"; } >"$L"
  exit 0
fi
[ "$GATE_TIER" = full ] && echo "$tip" >>"$GATE_LOG_DIR/full-gated" && echo "$GATE_BASE" >>"$GATE_LOG_DIR/full-bases" \
  && git tag --list 'v*' >"$GATE_LOG_DIR/full-tags"
if [ "$GATE_TIER" = full ] && git log -1 --name-only --format= HEAD | grep -qx guard.txt; then
  { echo "GUARD_EXIT=70"; echo "RED (guard)"; } >"$L"
elif [ "$GATE_TIER" = full ] && git ls-tree -r --name-only HEAD | grep -qx other.txt; then
  { echo "FAIL cc.example.OtherTest"; echo "RED"; } >"$L"
  echo '{"type":"error","code":"test-failure","module":"core","testClass":"cc.example.OtherTest"}' >"$GATE_LOG_DIR/g$n-rerun.jsonl"
elif [ "$GATE_TIER" = full ] && git ls-tree -r --name-only HEAD | grep -qxE '(red|flaky).txt'; then
  { echo "FAIL cc.example.RedTest"; echo "RED"; } >"$L"
  echo '{"type":"error","code":"test-failure","module":"core","testClass":"cc.example.RedTest$Inner"}' >"$GATE_LOG_DIR/g$n-rerun.jsonl"
elif [ "$GATE_TIER" = full ] && git log -1 --name-only --format= HEAD | grep -qx once.txt; then
  { echo "FLAKY core cc.example.RedTest passed on the re-run"; echo "GREEN $(git rev-parse HEAD)"; } >"$L"
else
  echo "GREEN $(git rev-parse HEAD)" >"$L"
fi
echo "GATE$n DONE" >>"$L"
GATE
cat >"$WORK/rerun.sh" <<'RERUN'
#!/usr/bin/env bash
grep -qx 'core cc.example.RedTest' "$2" || exit 2
echo "$1" >>"$GATE_LOG_DIR/reruns"
git -C "$GATE_CHECKOUT" ls-tree -r --name-only "$1" | grep -qx red.txt && exit 1
exit 0
RERUN

export GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@example.invalid
export GIT_COMMITTER_NAME=dev GIT_COMMITTER_EMAIL=dev@example.invalid
when=1700000000
tick() {
  when=$((when + 1))
  export GIT_COMMITTER_DATE="@$when +0000" GIT_AUTHOR_DATE="@$when +0000"
}
commit() { # <repo> <file> <subject>: one commit that writes the file
  tick
  echo "$3" >"$1/$2"
  git -C "$1" add "$2"
  git -C "$1" commit -qm "$3"
}

git init -q --bare -b main "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/seed" 2>/dev/null
commit "$WORK/seed" base.txt "base"
git -C "$WORK/seed" push -q origin HEAD:main
for c in dev qco fco; do git clone -q "$WORK/origin.git" "$WORK/$c"; done
export JK_QUEUE_REMOTE="$WORK/queue.git" QUEUE_REPO="$WORK/queue.git" GATE_LOCK="$WORK/gate.lock"
git init -q --bare "$QUEUE_REPO"
dev="$WORK/dev"
logs="$WORK/logs"

# land <name> <file> <subject> [<group>]: a branch with one commit, landed by the queue as a batch
# of its own; with a group, as that group of a red batch's split, whose batch line names it.
land() {
  git -C "$dev" fetch -q && git -C "$dev" checkout -q -b "$1" origin/main
  commit "$dev" "$2" "$3"
  submit "$1"
  [[ -z "${4:-}" ]] || echo "waiting $(git -C "$dev" rev-parse HEAD) group $4: batch ${4%.*} was red" >"$QUEUE_REPO/queue-state/$1"
  queue || fail "the queue did not run for $1"
  [[ "$(status "$1")" == "$1: landed "* ]] || fail "$1 did not land: $(status "$1")"
}
submit() {
  (cd "$dev" && bash "$Q/branch-tier.sh" >/dev/null && bash "$Q/submit.sh" "$1" >/dev/null) || fail "submitting $1 failed"
}
status() { (cd "$dev" && bash "$Q/status.sh" "$1"); }
# Greps a branch's status from a captured copy: piped into grep -q, status would die of SIGPIPE
# writing a line after the match, and pipefail would fail the check.
status_has() {
  local out
  out="$(status "$1")" || return 1
  grep "${@:2}" <<<"$out"
}
queue() {
  QUEUE_CHECKOUT="$WORK/qco" QUEUE_GATE="$WORK/gate.sh" QUEUE_LOG_DIR="$logs" bash "$Q/loop.sh" --once
}
# The Full loop's host reaches the queue's repository over ssh and has a gate lock of its own.
remote="queuehost:$QUEUE_REPO" full_lock="$WORK/full-host.lock"
full() {
  QUEUE_REPO="$remote" GATE_LOCK="$full_lock" FULL_CHECKOUT="$WORK/fco" FULL_GATE="$WORK/gate.sh" \
    FULL_LOG_DIR="$logs" FULL_KA=ka FULL_RERUN="$WORK/rerun.sh" GATE_LOG_DIR="$logs" \
    bash "$ROOT/scripts/gate/full-loop.sh" --once
}
origin() { git -C "$WORK/origin.git" rev-parse -q --verify "$1"; }
gated() { wc -l <"$logs/full-gated" | tr -d ' '; }
runs() { cat "$QUEUE_REPO/full-runs.log"; }
runs_has() { grep "$@" "$QUEUE_REPO/full-runs.log"; }
last_run_has() { grep "$@" <<<"$(tail -1 "$QUEUE_REPO/full-runs.log")"; }
reruns() { cat "$logs/reruns" 2>/dev/null; }
bounded() { # <command...>: run it, but fail it when it has not ended within a minute
  local pid
  "$@" &
  pid=$!
  for _ in $(seq 600); do kill -0 "$pid" 2>/dev/null || break; sleep 0.1; done
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid"
    return 124
  fi
  wait "$pid"
}

# --- green: the tip passes and green names it; the same tip is not gated twice; a release tag
# cut after the checkout was cloned is in the checkout the gate runs in ---
land a a.txt "TK-AAAA1: a"
git -C "$WORK/origin.git" tag v9.9.8 main
full || fail "the first cycle failed: $(runs)"
grep -qx v9.9.8 "$logs/full-tags" || fail "the gate's checkout has no tag cut after its clone: $(cat "$logs/full-tags")"
grep -qx queuehost "$SSH_LOG" || fail "the loop did not reach the queue's repository over ssh"
[[ "$(origin green)" == "$(origin main)" ]] || fail "green does not name the tip that passed: $(runs)"
full || fail "a cycle with nothing new failed"
[[ "$(gated)" -eq 1 ]] || fail "a judged tip was gated again"
# Before there is a green, every plugin changed since the gate's base: the empty tree.
[[ "$(head -1 "$logs/full-bases")" == "$(git hash-object -t tree /dev/null)" ]] || fail "the first gate's base was $(head -1 "$logs/full-bases")"

# --- red: bisect the batches since green, name the one that made it red (a group of a split here),
# confirm the commit of it the failing class fails with, submit its revert and reopen its ticket ---
land b b.txt "TK-BBBB1: b"
git -C "$dev" fetch -q && git -C "$dev" checkout -q -b r origin/main
commit "$dev" r.txt "TK-RRRR1: prepare"
commit "$dev" red.txt "TK-RRRR1: breaks the Full tier"
submit r
echo "waiting $(git -C "$dev" rev-parse HEAD) group 1.2: batch 1 was red" >"$QUEUE_REPO/queue-state/r"
queue || fail "the queue did not run for r"
[[ "$(status r)" == "r: landed "* ]] || fail "r did not land: $(status r)"
culprit="$(origin main)" prep="$(origin main~1)" before_r="$(origin main~2)"
bad="$culprit"
grep -q "group 1.2 LANDED r@" "$QUEUE_REPO/queue-batches.log" || fail "r did not land as a group: $(cat "$QUEUE_REPO/queue-batches.log")"
land c c.txt "TK-CCCC1: c"
green="$(origin green)"
full || fail "the red cycle failed"
[[ "$(origin green)" == "$green" ]] || fail "a red tip moved green"
[[ "$(sed -n 2p "$logs/full-bases")" == "$green" ]] || fail "a gate's base was not green: $(cat "$logs/full-bases")"
runs_has -q "RED culprit batch [0-9]* ($(git -C "$dev" rev-parse --short=12 "$culprit"): r@" \
  || fail "the red cycle did not name r's batch: $(runs)"
[[ "$(gated)" -eq 4 ]] || fail "the red cycle gated $(gated) commits in all, want 4: a, then the tip, r and b"
[[ "$(reruns | tr '\n' ' ')" == "$culprit $before_r $prep " ]] \
  || fail "the re-runs were not the batch's tip, its base, then its first commit: $(reruns)"
rb="full-revert/TK-RRRR1-$(git -C "$dev" rev-parse --short=12 "$bad")"
revert="$(git --git-dir="$QUEUE_REPO" rev-parse -q --verify "refs/queue/$rb")" || fail "no revert waits in the queue: $(runs)"
grep -qx "This reverts commit $bad." <<<"$(git --git-dir="$QUEUE_REPO" log -1 --format=%B "$revert")" || fail "the revert does not revert $bad"
[[ "$(git --git-dir="$QUEUE_REPO" rev-parse "$revert^")" == "$(origin main)" ]] || fail "the revert is not on the red tip"
[[ -z "$(git -C "$WORK/fco" status --porcelain)" ]] || fail "the revert left the Full checkout dirty"
git -C "$WORK/fco" rev-parse -q --verify "refs/heads/$rb" >/dev/null && fail "the revert's branch was left in the Full checkout"
[[ "$(grep -c '^comment|TK-RRRR1|' "$KA_LOG")" -eq 1 ]] || fail "the culprit's ticket got no comment: $(cat "$KA_LOG")"
grep -q -- "submitted its revert to the merge queue as \`$rb\`" "$KA_LOG" || fail "the comment does not name the revert: $(cat "$KA_LOG")"
[[ "$(grep -c '^move|TK-RRRR1|ready|' "$KA_LOG")" -eq 1 ]] || fail "the culprit's ticket was not reopened: $(cat "$KA_LOG")"
grep -q 'TK-[BC]' "$KA_LOG" && fail "a ticket of a green batch got a comment: $(cat "$KA_LOG")"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "one red cycle held the queue"
queue || fail "the queue did not run for the revert"
[[ "$(status "$rb")" == "$rb: landed "* ]] || fail "the revert did not land: $(status "$rb")"
full || fail "the cycle after the revert failed"
[[ "$(origin green)" == "$(origin main)" ]] || fail "the reverted tip did not pass: $(runs)"

# --- a class that passes when run again is not a confirmed culprit: nothing is reverted, the batch's
# ticket is told why; a second red cycle holds the queue, and the culprit is not gated or told again ---
: >"$KA_LOG"
land s flaky.txt "TK-SSSS1: fails the Full tier now and then"
culprit="$(origin main)"
full || fail "the unconfirmed red cycle failed"
runs_has -q "reverts nothing: the classes its gate failed pass when run again" || fail "the loop did not say why it reverted nothing: $(runs)"
[[ "$(grep -c '^comment|TK-SSSS1|.*reverts nothing, because the classes its gate failed pass' "$KA_LOG")" -eq 1 ]] \
  || fail "the batch's ticket was not told why nothing was reverted: $(cat "$KA_LOG")"
grep -q '^move|' "$KA_LOG" && fail "an unconfirmed culprit's ticket was reopened"
[[ -n "$(git --git-dir="$QUEUE_REPO" for-each-ref refs/queue/full-revert)" ]] && fail "an unconfirmed culprit was reverted"
n="$(gated)"
land d d.txt "TK-DDDD1: d"
full || fail "the second red cycle failed"
[[ "$(gated)" -eq $((n + 1)) ]] || fail "the second red cycle gated $(($(gated) - n)) commits, want 1: only its tip is new"
[[ "$(grep -c '^comment|TK-SSSS1|' "$KA_LOG")" -eq 1 ]] || fail "the culprit's ticket was commented on twice"
grep -qx 'ticket TK-SSSS1' "$QUEUE_REPO/full-hold" || fail "the hold does not name the ticket: $(cat "$QUEUE_REPO/full-hold")"
grep -qx "commit $culprit" "$QUEUE_REPO/full-hold" || fail "the hold does not name the commit"

# --- held: a revert and a commit for the ticket land, an unrelated branch waits, even in a group ---
git -C "$dev" fetch -q
git -C "$dev" checkout -q -b e origin/main && commit "$dev" e.txt "TK-EEEE1: e" && submit e
# e waits in a group of a red split, which is gated before anything else; held, it leaves the group.
echo "waiting $(git -C "$dev" rev-parse HEAD) group 7.1: batch 7 was red" >"$QUEUE_REPO/queue-state/e"
git -C "$dev" checkout -q -b fix origin/main && tick && git -C "$dev" revert --no-edit "$culprit" >/dev/null && submit fix
git -C "$dev" checkout -q -b fwd origin/main && commit "$dev" f.txt "TK-SSSS1: also note the fix" && submit fwd
queue || fail "the held queue did not run"
status_has e -q '^e: waiting .* held:' || fail "an unrelated branch was not held: $(status e)"
[[ "$(status fix)" == "fix: landed "* ]] || fail "the revert did not land: $(status fix)"
[[ "$(status fwd)" == "fwd: landed "* ]] || fail "a commit for the culprit's ticket did not land: $(status fwd)"
queue || fail "the held queue failed with nothing but held branches"
status_has e -q '^e: waiting .* held:' || fail "the held branch moved: $(status e)"

# --- the fix is green: green moves, the hold lifts, the held branch lands ---
full || fail "the green cycle after the fix failed"
[[ "$(origin green)" == "$(origin main)" ]] || fail "green did not move to the fixed tip: $(runs)"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "a green cycle left the hold"
last_run_has -q 'GREEN green; the queue.s hold is lifted' || fail "the green cycle does not say the hold lifted: $(runs)"
queue || fail "the queue did not run after the hold"
[[ "$(status e)" == "e: landed "* ]] || fail "the held branch did not land after the hold: $(status e)"

# --- two cycles in a row green only on the gate's re-run: each moves green and names the class;
# no ticket is told and the queue is not held ---
: >"$KA_LOG"
for k in 1 2; do
  land "once$k" once.txt "TK-OOOO$k: lands beside a flaky class"
  full || fail "flaky cycle $k failed"
  [[ "$(origin green)" == "$(origin main)" ]] || fail "flaky cycle $k did not move green: $(runs)"
  last_run_has -q "GREEN green; passed on the re-run: core cc.example.RedTest$" ||
    fail "flaky cycle $k does not name the class that passed on the re-run: $(runs)"
done
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "two flaky cycles held the queue: $(cat "$QUEUE_REPO/full-hold")"
[[ "$(cat "$QUEUE_REPO/full-reds")" -eq 0 ]] || fail "a flaky cycle counted as red: $(cat "$QUEUE_REPO/full-reds")"
[[ -s "$KA_LOG" ]] && fail "a flaky cycle told a ticket: $(cat "$KA_LOG")"

# --- a confirmed culprit whose revert conflicts with a later batch is not reverted ---
: >"$KA_LOG"
land x red.txt "TK-XXXX1: x"
land y red.txt "TK-YYYY1: y builds on x"
full || fail "the conflicting red cycle failed"
runs_has -q "reverts nothing: its revert of [0-9a-f]* conflicts with later work" || fail "the loop did not say the revert conflicts: $(runs)"
grep -q '^comment|TK-XXXX1|.*conflicts with later work' "$KA_LOG" || fail "x's ticket was not told the revert conflicts: $(cat "$KA_LOG")"
grep -q '^move|' "$KA_LOG" && fail "a culprit that was not reverted had its ticket reopened"
[[ -z "$(git -C "$WORK/fco" status --porcelain)" ]] || fail "the conflicting revert left the Full checkout dirty"
[[ "$(git --git-dir="$QUEUE_REPO" for-each-ref refs/queue/full-revert)" == "" ]] || fail "a conflicting revert was submitted"

# --- paused: no cycle starts, even the one the loop waited on the gate lock for, however many looks
# it takes; full-runs.log says so once with the reason; removing the file resumes it ---
land z z.txt "TK-ZZZZ1: z"
n="$(gated)"
echo "a bench night" >"$QUEUE_REPO/pause"
rc=0
QUEUE_REPO="$remote" FULL_CHECKOUT="$WORK/fco" FULL_LOG_DIR="$logs" bash "$ROOT/scripts/gate/full-loop.sh" --cycle || rc=$?
[[ "$rc" -eq 4 ]] || fail "a cycle started while paused exited $rc, want 4"
# Where flock is real, the loop is already waiting on the gate lock when the pause starts.
if [[ -z "$fake_flock" ]]; then
  rm "$QUEUE_REPO/pause"
  exec 8>>"$full_lock"
  flock 8
fi
FULL_POLL=1 full >"$WORK/paused.out" 2>&1 8>&- &
looping=$!
if [[ -z "$fake_flock" ]]; then
  sleep 1
  echo "a bench night" >"$QUEUE_REPO/pause"
  flock -u 8
  exec 8>&-
fi
for _ in $(seq 100); do runs_has -q '^full paused' && break; sleep 0.1; done
sleep 3
kill -0 "$looping" 2>/dev/null || fail "the paused loop ended: $(cat "$WORK/paused.out")"
[[ "$(gated)" -eq "$n" ]] || fail "a paused loop started a gate"
[[ "$(runs | grep -c '^full paused .*: a bench night (')" -eq 1 ]] || fail "the log does not say once that it is paused and why: $(runs)"
rm "$QUEUE_REPO/pause"
for _ in $(seq 200); do kill -0 "$looping" 2>/dev/null || break; sleep 0.1; done
if kill -0 "$looping" 2>/dev/null; then kill "$looping"; fail "the loop did not resume: $(runs)"; fi
wait "$looping" || fail "the resumed loop failed: $(cat "$WORK/paused.out")"
runs_has -q '^full resumed ' || fail "the log does not say the loop resumed: $(runs)"
[[ "$(gated)" -eq $((n + 1)) ]] || fail "the resumed loop did not gate the new tip: $(runs)"

# --- no lock is shared with the queue: a cycle runs while the queue's host holds its gate lock, and
# a batch lands while the Full loop's host holds its own ---
# The fix of the red batch above lands first, so the cycle is green and the queue is not held.
git -C "$dev" fetch -q && git -C "$dev" checkout -q -b unred origin/main
tick && git -C "$dev" rm -q red.txt && git -C "$dev" commit -qm "TK-XXXX1: no longer breaks the Full tier"
submit unred
queue || fail "the queue did not run for the fix"
[[ "$(status unred)" == "unred: landed "* ]] || fail "the fix did not land: $(status unred)"
if [[ -z "$fake_flock" ]]; then
  exec 8>>"$GATE_LOCK"
  flock 8
  n="$(gated)"
  bounded full 8>&- || fail "a cycle did not run while the queue's lock was held: $(runs)"
  [[ "$(gated)" -eq $((n + 1)) ]] || fail "the cycle beside the queue's lock gated nothing: $(runs)"
  [[ "$(origin green)" == "$(origin main)" ]] || fail "the fixed tip did not pass: $(runs)"
  flock -u 8
  exec 8>&-
  exec 8>>"$full_lock"
  flock 8
  bounded land v v.txt "TK-VVVV1: v" 8>&- || fail "a batch did not land while the Full loop's lock was held"
  flock -u 8
  exec 8>&-
else
  # Without flock there is no lock to hold; the fixed tip still passes, which lifts the hold.
  full || fail "the cycle after the fix failed: $(runs)"
  [[ "$(origin green)" == "$(origin main)" ]] || fail "the fixed tip did not pass: $(runs)"
fi

# --- the queue's host cannot be reached: the cycle gates nothing and says so ---
land w w.txt "TK-WWWW1: w"
n="$(gated)"
rc=0
remote="downhost:$QUEUE_REPO" full 2>"$WORK/down.err" || rc=$?
[[ "$rc" -eq 2 ]] || fail "a cycle with the queue's host down exited $rc, want 2"
[[ "$(gated)" -eq "$n" ]] || fail "a cycle with the queue's host down gated"
grep -q 'cannot be reached' "$WORK/down.err" || fail "the loop did not say the queue's host is down: $(cat "$WORK/down.err")"
[[ -z "$(find "$WORK" "$ROOT" -maxdepth 3 -name "*host:*" -print -quit)" ]] || fail "the loop wrote the queue's state to a local path named for the host"

# --- jk cannot start: before the gate, and when the gate is red at guard; each is an infra failure,
# with no verdict, no culprit, no red cycle and no hold, and the next cycle gates the tip ---
: >"$KA_LOG"
n="$(gated)" reds="$(cat "$QUEUE_REPO/full-reds")"
touch "$WORK/jk-broken"
rc=0
FULL_HOLD_AFTER=1 full || rc=$?
[[ "$rc" -eq 5 ]] || fail "a cycle whose jk cannot start exited $rc, want 5: $(runs)"
[[ "$(gated)" -eq "$n" ]] || fail "a cycle whose jk cannot start gated"
last_run_has -q "$(git -C "$dev" rev-parse --short=12 "$(origin main)") INFRA jk cannot start: ! Engine > no build engine for JumpKick 9.9.9-SNAPSHOT; no verdict" \
  || fail "the infra failure was not logged: $(runs)"
rm "$WORK/jk-broken"
rc=0
FIXTURE_GATE_BREAKS_JK="$WORK/jk-broken" FULL_HOLD_AFTER=1 full || rc=$?
[[ "$rc" -eq 5 ]] || fail "a cycle red at guard with no engine exited $rc, want 5: $(runs)"
[[ "$(gated)" -eq "$n" ]] || fail "a gate red at guard with no engine counted as gated"
last_run_has -q "INFRA jk cannot start" || fail "a guard that could not start jk was not logged as infra: $(runs)"
grep -q "^$(origin main) " "$QUEUE_REPO/full-verdicts" && fail "a cycle whose jk cannot start recorded a verdict"
[[ "$(cat "$QUEUE_REPO/full-reds")" == "$reds" ]] || fail "a cycle whose jk cannot start counted as red"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "a cycle whose jk cannot start held the queue: $(cat "$QUEUE_REPO/full-hold")"
[[ -s "$KA_LOG" ]] && fail "a cycle whose jk cannot start told a ticket: $(cat "$KA_LOG")"
rm "$WORK/jk-broken"
full || fail "the cycle after jk started again failed: $(runs)"
[[ "$(gated)" -eq $((n + 1)) ]] || fail "the cycle after jk started again did not gate the tip"
[[ "$(origin green)" == "$(origin main)" ]] || fail "the tip did not pass once jk started: $(runs)"

# --- a red after an earlier red gate: the batch red at guard is not blamed for a later red in a
# class, nor held for; the batch whose gate fails that class is, and the hold names it only once
# the same red repeats ---
: >"$KA_LOG"
land p guard.txt "TK-PPPP1: red at guard when gated"
FULL_HOLD_AFTER=1 full || fail "the cycle red at guard failed: $(runs)"
last_run_has -q "RED culprit unknown: this gate failed in no test class or fixture (exit GUARD, step guard)" \
  || fail "a red at guard alone named a batch or did not say why: $(runs)"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "a red that names no batch held the queue: $(cat "$QUEUE_REPO/full-hold")"
[[ -s "$KA_LOG" ]] && fail "a red that names no batch told a ticket: $(cat "$KA_LOG")"
guardlog="$(tail -1 "$QUEUE_REPO/full-verdicts" | cut -d' ' -f3)"
land k other.txt "TK-KKKK1: breaks OtherTest"
kbatch="$(git -C "$dev" rev-parse --short=12 "$(origin main)")"
full || fail "the red cycle after the red at guard failed: $(runs)"
runs_has -q "RED culprit batch [0-9]* ($kbatch: k@.* tickets TK-KKKK1; its gate fails core cc.example.OtherTest too; red cycles 1; log [^ ]*/integ-gate-$(tail -1 "$QUEUE_REPO/full-verdicts" | cut -d' ' -f3).log" \
  || fail "the cycle after the red at guard did not name k's batch from its own log: $(runs)"
runs_has -q "integ-gate-$guardlog.log, the batch's" && fail "a later red read the gate log of the red at guard: $(runs)"
grep -q 'TK-PPPP1' "$KA_LOG" && fail "the batch red at guard was told of a red in a class: $(cat "$KA_LOG")"
[[ "$(cat "$QUEUE_REPO/full-reds")" -eq 1 ]] || fail "a new red set did not count as the first red cycle: $(cat "$QUEUE_REPO/full-reds")"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "the first red cycle of a new red set held the queue"
land m m.txt "TK-MMMM1: m"
full || fail "the second red cycle in OtherTest failed: $(runs)"
runs_has -q "RED culprit batch [0-9]* ($kbatch: .*red cycles 2;" \
  || fail "the same red twice did not count two red cycles on k's batch: $(runs)"
grep -qx 'ticket TK-KKKK1' "$QUEUE_REPO/full-hold" || fail "the hold does not name k's ticket: $(cat "$QUEUE_REPO/full-hold" 2>/dev/null)"
grep -q 'TK-PPPP1\|TK-MMMM1' "$QUEUE_REPO/full-hold" && fail "the hold names a batch that did not fail the class: $(cat "$QUEUE_REPO/full-hold")"
# The red changes to one in no class or fixture: no batch, the hold lifts.
git -C "$dev" fetch -q && git -C "$dev" checkout -q -b unk origin/main
tick && git -C "$dev" rm -q other.txt && echo again >"$dev/guard.txt" && git -C "$dev" add guard.txt \
  && git -C "$dev" commit -qm "TK-KKKK1: no longer breaks OtherTest"
submit unk
queue || fail "the queue did not run for k's fix"
[[ "$(status unk)" == "unk: landed "* ]] || fail "k's fix did not land under the hold: $(status unk)"
full || fail "the cycle red at guard after the fix failed: $(runs)"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "a red that names no batch left the hold: $(cat "$QUEUE_REPO/full-hold")"
runs_has -q "the queue's hold is lifted: this red names no batch" || fail "the lifted hold was not logged: $(runs)"
[[ "$(cat "$QUEUE_REPO/full-reds")" -eq 1 ]] || fail "a changed red set did not start the count again: $(cat "$QUEUE_REPO/full-reds")"
land n2 n2.txt "TK-NNNN1: n"
full || fail "the cycle after the red at guard failed: $(runs)"
[[ "$(origin green)" == "$(origin main)" ]] || fail "the tip did not pass after the red at guard: $(runs)"

echo "test-full-loop: ok ($(gated) Full gates, $(reruns | wc -l | tr -d ' ') re-runs)"
