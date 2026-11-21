#!/usr/bin/env bash
# Fixtures for the merge queue under scripts/queue/, in scratch repositories with a stand-in `jk`
# and a stand-in gate. Submitting refuses a branch whose tip has not passed the Branch tier; a
# batch applies every waiting branch onto origin/main once (a branch cut from another one does not
# carry its commits twice), ejects the branch that conflicts, gates the batch and fast-forwards
# origin/main to it; a red batch is split and each branch gated alone, so the good one lands and the
# bad one is ejected with the failing class; when origin/main moves during a gate the queue does
# not force its push, and lands the batch on the new origin/main next time.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-queue.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
Q="$ROOT/scripts/queue"

fail() {
  echo "test-queue: $*" >&2
  exit 1
}

# The stand-in jk passes every Branch-tier step; the stand-in gate is red for a tip carrying
# red.txt and, when MOVE_ORIGIN is set, pushes to origin/main itself before it says GREEN.
mkdir -p "$WORK/bin"
printf '%s\n' '#!/usr/bin/env bash' 'exit 0' >"$WORK/bin/jk"
chmod +x "$WORK/bin/jk"
export PATH="$WORK/bin:$PATH"
# macOS has no flock: there the loop's lock is a stand-in that runs the batch.
if ! command -v flock >/dev/null 2>&1; then
  printf '%s\n' '#!/usr/bin/env bash' 'shift 3' 'exec "$@"' >"$WORK/bin/flock"
  chmod +x "$WORK/bin/flock"
fi
cat >"$WORK/gate.sh" <<'GATE'
#!/usr/bin/env bash
n="$1" tip="$2"
L="$GATE_LOG_DIR/integ-gate-$n.log"
cd "$GATE_CHECKOUT" && git checkout -q --detach "$tip"
if git ls-tree -r --name-only HEAD | grep -qx red.txt; then
  { echo "FAIL cc.example.RedTest"; echo "RED"; } >"$L"
else
  if [ -n "${MOVE_ORIGIN:-}" ]; then
    git -C "$MOVE_ORIGIN" fetch -q origin && git -C "$MOVE_ORIGIN" reset -q --hard origin/main
    git -C "$MOVE_ORIGIN" commit -q --allow-empty -m "written past the queue"
    git -C "$MOVE_ORIGIN" push -q origin HEAD:main
  fi
  echo "GREEN $(git rev-parse HEAD)" >"$L"
fi
echo "GATE$n DONE" >>"$L"
GATE

export GIT_AUTHOR_NAME=dev GIT_AUTHOR_EMAIL=dev@example.invalid
export GIT_COMMITTER_NAME=dev GIT_COMMITTER_EMAIL=dev@example.invalid
when=1700000000
commit() { # <repo> <file> <content>: one commit, a second later than the one before
  when=$((when + 1))
  echo "$3" >"$1/$2"
  git -C "$1" add "$2"
  GIT_COMMITTER_DATE="@$when +0000" GIT_AUTHOR_DATE="@$when +0000" git -C "$1" commit -qm "$2: $3"
}

git init -q --bare -b main "$WORK/origin.git"
git clone -q "$WORK/origin.git" "$WORK/seed" 2>/dev/null
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
queue() {
  env QUEUE_REPO="$WORK/queue.git" QUEUE_CHECKOUT="$WORK/co" QUEUE_GATE="$WORK/gate.sh" \
    QUEUE_LOG_DIR="$WORK/logs" GATE_LOCK="$WORK/gate.lock" "$@" bash "$Q/loop.sh" --once
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
status c | grep -q '^c: ejected .*conflicts' || fail "c does not show as ejected: $(status c)"
status c | grep -q 'conflicts: base.txt' || fail "c's ejection does not name the file: $(status c)"

# --- a red batch is split: the good branch lands, the bad one is ejected with the failing class ---
git -C "$dev" fetch -q
branch d origin/main d.txt d
branch e origin/main red.txt e
for x in d e; do submit "$x" || fail "submitting $x failed"; done
queue || fail "the red batch did not run"
status d | grep -q '^d: waiting .* alone' || fail "d was not split out of the red batch: $(status d)"
for _ in 1 2; do queue || fail "the split batches did not run"; done
holds d.txt || fail "d did not land after the split"
if holds red.txt; then fail "the red branch landed"; fi
status e | grep -q 'FAIL cc.example.RedTest' || fail "e's ejection does not name the failing class: $(status e)"
grep -c RED-SPLIT "$WORK/queue.git/queue-batches.log" >/dev/null || fail "the batch log does not record the split"

# --- origin/main moves during the gate: no forced push; the batch lands on the new main ---
git -C "$dev" fetch -q
branch f origin/main f.txt f
submit f || fail "submitting f failed"
queue MOVE_ORIGIN="$WORK/other" 2>/dev/null || fail "the batch with a moving origin did not run"
moved="$(git -C "$WORK/other" rev-parse HEAD)"
[[ "$(origin_main)" == "$moved" ]] || fail "the queue overwrote a commit it did not gate"
status f | grep -q '^f: waiting .*moved' || fail "f is not waiting again: $(status f)"
grep -q ' MOVED f@' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the move"
queue || fail "the batch after the move did not run"
holds f.txt || fail "f did not land after origin/main moved"
git -C "$WORK/origin.git" merge-base --is-ancestor "$moved" main || fail "the landing dropped the commit that moved origin/main"

# --- nothing waits: no batch ---
lines="$(wc -l <"$WORK/queue.git/queue-batches.log")"
queue || fail "an empty queue failed"
[[ "$(wc -l <"$WORK/queue.git/queue-batches.log")" -eq "$lines" ]] || fail "an empty queue ran a batch"

echo "test-queue: ok ($lines batches)"
