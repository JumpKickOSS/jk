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
# moves, no ticket is told, and the queue is not held.
set -euo pipefail

unset "${!GATE_@}" "${!FULL_@}" "${!QUEUE_@}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-full-loop.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
Q="$ROOT/scripts/queue"

fail() {
  echo "test-full-loop: $*" >&2
  exit 1
}

# The stand-in jk passes the Branch tier; ka logs each call and says every ticket is done. The
# stand-in gate passes the Queue tier always and the Full tier unless the tip carries red.txt or
# flaky.txt, when cc.example.RedTest fails, and logs each Full gate's tip and base; on a tip whose
# own commit writes once.txt the class passes on the gate's re-run, and the gate is green naming it. The stand-in
# re-run fails that class on a commit that carries red.txt, and logs each commit it runs on.
mkdir -p "$WORK/bin"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$WORK/bin/jk"
cat >"$WORK/bin/ka" <<'KA'
#!/usr/bin/env bash
printf '%s|' "$@" >>"$KA_LOG"
echo >>"$KA_LOG"
[ "$1" != show ] || echo '{"status":"done"}'
KA
chmod +x "$WORK/bin/jk" "$WORK/bin/ka"
export PATH="$WORK/bin:$PATH" KA_LOG="$WORK/ka.log"
: >"$KA_LOG"
if ! command -v flock >/dev/null 2>&1; then
  printf '%s\n' '#!/usr/bin/env bash' 'shift 3' 'exec "$@"' >"$WORK/bin/flock"
  chmod +x "$WORK/bin/flock"
fi
cat >"$WORK/gate.sh" <<'GATE'
#!/usr/bin/env bash
n="$1" tip="$2"
L="$GATE_LOG_DIR/integ-gate-$n.log"
cd "$GATE_CHECKOUT" && git checkout -q --detach "$tip"
[ "$GATE_TIER" = full ] && echo "$tip" >>"$GATE_LOG_DIR/full-gated" && echo "$GATE_BASE" >>"$GATE_LOG_DIR/full-bases"
if [ "$GATE_TIER" = full ] && git ls-tree -r --name-only HEAD | grep -qxE '(red|flaky).txt'; then
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
queue() {
  QUEUE_CHECKOUT="$WORK/qco" QUEUE_GATE="$WORK/gate.sh" QUEUE_LOG_DIR="$logs" bash "$Q/loop.sh" --once
}
full() {
  FULL_CHECKOUT="$WORK/fco" FULL_GATE="$WORK/gate.sh" FULL_LOG_DIR="$logs" FULL_KA=ka \
    FULL_RERUN="$WORK/rerun.sh" GATE_LOG_DIR="$logs" \
    bash "$ROOT/scripts/gate/full-loop.sh" --once
}
origin() { git -C "$WORK/origin.git" rev-parse -q --verify "$1"; }
gated() { wc -l <"$logs/full-gated" | tr -d ' '; }
runs() { cat "$QUEUE_REPO/full-runs.log"; }
reruns() { cat "$logs/reruns" 2>/dev/null; }

# --- green: the tip passes and green names it; the same tip is not gated twice ---
land a a.txt "TK-AAAA1: a"
full || fail "the first cycle failed: $(runs)"
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
runs | grep -q "RED culprit batch [0-9]* ($(git -C "$dev" rev-parse --short=12 "$culprit"): r@" \
  || fail "the red cycle did not name r's batch: $(runs)"
[[ "$(gated)" -eq 4 ]] || fail "the red cycle gated $(gated) commits in all, want 4: a, then the tip, r and b"
[[ "$(reruns | tr '\n' ' ')" == "$culprit $before_r $prep " ]] \
  || fail "the re-runs were not the batch's tip, its base, then its first commit: $(reruns)"
rb="full-revert/TK-RRRR1-$(git -C "$dev" rev-parse --short=12 "$bad")"
revert="$(git --git-dir="$QUEUE_REPO" rev-parse -q --verify "refs/queue/$rb")" || fail "no revert waits in the queue: $(runs)"
git --git-dir="$QUEUE_REPO" log -1 --format=%B "$revert" | grep -qx "This reverts commit $bad." || fail "the revert does not revert $bad"
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
runs | grep -q "reverts nothing: the classes its gate failed pass when run again" || fail "the loop did not say why it reverted nothing: $(runs)"
[[ "$(grep -c '^comment|TK-SSSS1|.*reverts nothing, because the classes its gate failed pass' "$KA_LOG")" -eq 1 ]] \
  || fail "the batch's ticket was not told why nothing was reverted: $(cat "$KA_LOG")"
grep -q '^move|' "$KA_LOG" && fail "an unconfirmed culprit's ticket was reopened"
git --git-dir="$QUEUE_REPO" for-each-ref refs/queue/full-revert | grep -q . && fail "an unconfirmed culprit was reverted"
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
status e | grep -q '^e: waiting .* held:' || fail "an unrelated branch was not held: $(status e)"
[[ "$(status fix)" == "fix: landed "* ]] || fail "the revert did not land: $(status fix)"
[[ "$(status fwd)" == "fwd: landed "* ]] || fail "a commit for the culprit's ticket did not land: $(status fwd)"
queue || fail "the held queue failed with nothing but held branches"
status e | grep -q '^e: waiting .* held:' || fail "the held branch moved: $(status e)"

# --- the fix is green: green moves, the hold lifts, the held branch lands ---
full || fail "the green cycle after the fix failed"
[[ "$(origin green)" == "$(origin main)" ]] || fail "green did not move to the fixed tip: $(runs)"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "a green cycle left the hold"
runs | tail -1 | grep -q 'GREEN green; the queue.s hold is lifted' || fail "the green cycle does not say the hold lifted: $(runs)"
queue || fail "the queue did not run after the hold"
[[ "$(status e)" == "e: landed "* ]] || fail "the held branch did not land after the hold: $(status e)"

# --- two cycles in a row green only on the gate's re-run: each moves green and names the class;
# no ticket is told and the queue is not held ---
: >"$KA_LOG"
for k in 1 2; do
  land "once$k" once.txt "TK-OOOO$k: lands beside a flaky class"
  full || fail "flaky cycle $k failed"
  [[ "$(origin green)" == "$(origin main)" ]] || fail "flaky cycle $k did not move green: $(runs)"
  runs | tail -1 | grep -q "GREEN green; passed on the re-run: core cc.example.RedTest$" ||
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
runs | grep -q "reverts nothing: its revert of [0-9a-f]* conflicts with later work" || fail "the loop did not say the revert conflicts: $(runs)"
grep -q '^comment|TK-XXXX1|.*conflicts with later work' "$KA_LOG" || fail "x's ticket was not told the revert conflicts: $(cat "$KA_LOG")"
grep -q '^move|' "$KA_LOG" && fail "a culprit that was not reverted had its ticket reopened"
[[ -z "$(git -C "$WORK/fco" status --porcelain)" ]] || fail "the conflicting revert left the Full checkout dirty"
[[ "$(git --git-dir="$QUEUE_REPO" for-each-ref refs/queue/full-revert)" == "" ]] || fail "a conflicting revert was submitted"

echo "test-full-loop: ok ($(gated) Full gates, $(reruns | wc -l | tr -d ' ') re-runs)"
