#!/usr/bin/env bash
# Fixtures for the Full tier's loop, scripts/gate/full-loop.sh, beside the merge queue in scratch
# repositories with a stand-in `jk`, `ka` and gate. A green tip of origin/main moves origin's green
# branch to it, and a tip already judged is not gated again. A red tip names the batch that made it
# red, found by bisecting the batches landed since green with each commit gated once, and comments
# once on its ticket. After two red cycles the queue lands only the fix (a revert of the batch's
# commit, or a commit for its ticket) and holds the rest, until a green cycle lifts the hold.
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

# The stand-in jk passes the Branch tier; ka logs each call. The stand-in gate passes the Queue
# tier always and the Full tier unless the tip carries red.txt, and logs each Full gate's tip and base.
mkdir -p "$WORK/bin"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$WORK/bin/jk"
cat >"$WORK/bin/ka" <<'KA'
#!/usr/bin/env bash
printf '%s|' "$@" >>"$KA_LOG"
echo >>"$KA_LOG"
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
if [ "$GATE_TIER" = full ] && git ls-tree -r --name-only HEAD | grep -qx red.txt; then
  { echo "FAIL cc.example.RedTest"; echo "RED"; } >"$L"
else
  echo "GREEN $(git rev-parse HEAD)" >"$L"
fi
echo "GATE$n DONE" >>"$L"
GATE

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
    bash "$ROOT/scripts/gate/full-loop.sh" --once
}
origin() { git -C "$WORK/origin.git" rev-parse -q --verify "$1"; }
gated() { wc -l <"$logs/full-gated" | tr -d ' '; }
runs() { cat "$QUEUE_REPO/full-runs.log"; }

# --- green: the tip passes and green names it; the same tip is not gated twice ---
land a a.txt "TK-AAAA1: a"
full || fail "the first cycle failed: $(runs)"
[[ "$(origin green)" == "$(origin main)" ]] || fail "green does not name the tip that passed: $(runs)"
full || fail "a cycle with nothing new failed"
[[ "$(gated)" -eq 1 ]] || fail "a judged tip was gated again"
# Before there is a green, every plugin changed since the gate's base: the empty tree.
[[ "$(head -1 "$logs/full-bases")" == "$(git hash-object -t tree /dev/null)" ]] || fail "the first gate's base was $(head -1 "$logs/full-bases")"

# --- red: bisect the batches since green, name the one that made it red (a group of a split here),
# comment on its ticket ---
land b b.txt "TK-BBBB1: b"
land r red.txt "TK-RRRR1: breaks the Full tier" 1.2
culprit="$(origin main)"
grep -q "group 1.2 LANDED r@" "$QUEUE_REPO/queue-batches.log" || fail "r did not land as a group: $(cat "$QUEUE_REPO/queue-batches.log")"
land c c.txt "TK-CCCC1: c"
green="$(origin green)"
full || fail "the red cycle failed"
[[ "$(origin green)" == "$green" ]] || fail "a red tip moved green"
[[ "$(sed -n 2p "$logs/full-bases")" == "$green" ]] || fail "a gate's base was not green: $(cat "$logs/full-bases")"
runs | grep -q "RED culprit batch [0-9]* ($(git -C "$dev" rev-parse --short=12 "$culprit"): r@" \
  || fail "the red cycle did not name r's batch: $(runs)"
[[ "$(gated)" -eq 4 ]] || fail "the red cycle gated $(gated) commits in all, want 4: a, then the tip, r and b"
[[ "$(grep -c '^comment|TK-RRRR1|' "$KA_LOG")" -eq 1 ]] || fail "the culprit's ticket got no comment: $(cat "$KA_LOG")"
grep -q -- 'Full tier is red' "$KA_LOG" || fail "the comment does not say the Full tier is red: $(cat "$KA_LOG")"
grep -q 'TK-[BC]' "$KA_LOG" && fail "a ticket of a green batch got a comment: $(cat "$KA_LOG")"
[[ -f "$QUEUE_REPO/full-hold" ]] && fail "one red cycle held the queue"

# --- a second red cycle holds the queue; the culprit is not gated or commented again ---
land d d.txt "TK-DDDD1: d"
full || fail "the second red cycle failed"
[[ "$(gated)" -eq 5 ]] || fail "the second red cycle gated $(gated) commits in all, want 5: only its tip is new"
[[ "$(grep -c '^comment|TK-RRRR1|' "$KA_LOG")" -eq 1 ]] || fail "the culprit's ticket was commented on twice"
grep -qx 'ticket TK-RRRR1' "$QUEUE_REPO/full-hold" || fail "the hold does not name the ticket: $(cat "$QUEUE_REPO/full-hold")"
grep -qx "commit $culprit" "$QUEUE_REPO/full-hold" || fail "the hold does not name the commit"

# --- held: a revert and a commit for the ticket land, an unrelated branch waits, even in a group ---
git -C "$dev" fetch -q
git -C "$dev" checkout -q -b e origin/main && commit "$dev" e.txt "TK-EEEE1: e" && submit e
# e waits in a group of a red split, which is gated before anything else; held, it leaves the group.
echo "waiting $(git -C "$dev" rev-parse HEAD) group 7.1: batch 7 was red" >"$QUEUE_REPO/queue-state/e"
git -C "$dev" checkout -q -b fix origin/main && tick && git -C "$dev" revert --no-edit "$culprit" >/dev/null && submit fix
git -C "$dev" checkout -q -b fwd origin/main && commit "$dev" f.txt "TK-RRRR1: also note the fix" && submit fwd
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

echo "test-full-loop: ok ($(gated) Full gates)"
