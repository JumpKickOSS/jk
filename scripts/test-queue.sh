#!/usr/bin/env bash
# Fixtures for the merge queue under scripts/queue/, in scratch repositories with a stand-in `jk`
# and a stand-in gate. Submitting refuses a branch whose tip has not passed the Branch tier; a
# batch applies every waiting branch onto origin/main once (a branch cut from another one does not
# carry its commits twice), ejects the branch that conflicts, gates the batch and fast-forwards
# origin/main to it; a red batch is split, apart from the branches that changed the module its failed
# class is in when there is one and in halves when there is not, so the good branches land and the
# bad one is ejected with the failing class, which its ticket is told along with the failure; a
# branch that leaves the queue any other way is recorded as dropped; when origin/main moves during a
# gate the queue does not force its push, and lands the batch on the new origin/main next time.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-queue.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
Q="$ROOT/scripts/queue"

fail() {
  echo "test-queue: $*" >&2
  exit 1
}

# The stand-in jk passes every Branch-tier step. The stand-in gate is red for a tip carrying a
# red.txt, naming its failed class as in the module red.txt holds, and with the failure beside the
# log; when MOVE_ORIGIN is set it pushes to origin/main itself before it says GREEN, and when
# DROP_REF is set it deletes that branch's submission. The stand-in ka logs each comment.
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
[ -z "${DROP_REF:-}" ] || git --git-dir="$DROP_IN" update-ref -d "refs/queue/$DROP_REF"
red="$(git ls-tree -r --name-only HEAD | grep -E '(^|/)red\.txt$' | head -1)"
if [ -n "$red" ]; then
  { echo "FAIL cc.example.RedTest"; echo "FAILED cc.example:$(cat "$red") cc.example.RedTest"; echo "RED"; } >"$L"
  printf '%s\n' "cc.example.RedTest#fails() (cc.example:$(cat "$red"))" "    at cc.example.RedTest.fails(RedTest.java:7)" \
    >"$GATE_LOG_DIR/g$n-failures.txt"
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
# A workspace of two members, jk-a and jk-b, and the script that maps a change onto them.
mkdir -p "$WORK/seed/mod/a" "$WORK/seed/mod/b" "$WORK/seed/scripts/gate"
printf '%s\n' '[workspace]' 'modules = ["mod/a", "mod/b"]' >"$WORK/seed/jk.toml"
echo 'name = "jk-a"' >"$WORK/seed/mod/a/jk.toml"
echo 'name = "jk-b"' >"$WORK/seed/mod/b/jk.toml"
cp "$ROOT/scripts/gate/changed-modules.sh" "$WORK/seed/scripts/gate/"
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
status c | grep -q 'tickets told: none' || fail "c's ejection does not say no ticket was told: $(status c)"

# --- a red batch whose failed class names no branch's module is split in halves ---
git -C "$dev" fetch -q
branch d origin/main docs/d.txt d
branch e origin/main docs/red.txt nomodule
for x in d e; do submit "$x" || fail "submitting $x failed"; done
queue || fail "the red batch did not run"
status d | grep -q '^d: waiting .* group [0-9.]*: .* split in halves' || fail "d was not split out of the red batch: $(status d)"
for _ in 1 2; do queue || fail "the split batches did not run"; done
holds docs/d.txt || fail "d did not land after the split"
if holds docs/red.txt; then fail "the red branch landed"; fi
status e | grep -q 'FAIL cc.example.RedTest' || fail "e's ejection does not name the failing class: $(status e)"
grep -q 'RED-SPLIT in halves' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the split"

# --- one bad branch of three: the rest lands, the bad one alone is ejected and its ticket told ---
git -C "$dev" fetch -q
branch g origin/main mod/a/A.java g
branch w/QA-BAD01 origin/main mod/b/red.txt jk-b
branch i origin/main docs/i.txt i
for x in g w/QA-BAD01 i; do submit "$x" || fail "submitting $x failed"; done
batches="$(wc -l <"$WORK/queue.git/queue-batches.log")"
queue || fail "the red batch of three did not run"
status w/QA-BAD01 | grep -q 'waiting .* group [0-9.]*: .* split by the modules' ||
  fail "the branch that changed the failing module was not split out: $(status w/QA-BAD01)"
# j, submitted after the split, waits for the groups; its submission is lost while it waits, and
# the queue records that rather than forget it.
branch j origin/main docs/j.txt j
submit j || fail "submitting j failed"
queue DROP_REF=j || fail "the rest of the red batch did not run"
holds mod/a/A.java || fail "g did not land with the rest: $(status g)"
holds docs/i.txt || fail "i did not land with the rest: $(status i)"
if holds docs/j.txt; then fail "a branch submitted after the split was gated with the rest"; fi
status j | grep -q '^j: dropped ' || fail "a lost branch is not recorded as dropped: $(status j || true)"
grep -q 'DROPPED j@' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the drop"
queue || fail "the suspect alone did not run"
if holds mod/b/red.txt; then fail "the bad branch landed"; fi
[[ "$(grep -c '^batch [0-9]* [0-9T:-]*Z ' <(tail -n +$((batches + 1)) "$WORK/queue.git/queue-batches.log"))" -eq 3 ]] ||
  fail "one bad branch of three took other than three batches: $(tail -4 "$WORK/queue.git/queue-batches.log")"
status w/QA-BAD01 | grep -q '^w/QA-BAD01: ejected ' || fail "the bad branch was not ejected: $(status w/QA-BAD01)"
status w/QA-BAD01 | grep -qx 'tickets told: QA-BAD01' || fail "the ticket was not told: $(status w/QA-BAD01)"
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
status f | grep -q '^f: waiting .*moved' || fail "f is not waiting again: $(status f)"
grep -q ' MOVED f@' "$WORK/queue.git/queue-batches.log" || fail "the batch log does not record the move"
queue || fail "the batch after the move did not run"
holds docs/f.txt || fail "f did not land after origin/main moved"
git -C "$WORK/origin.git" merge-base --is-ancestor "$moved" main || fail "the landing dropped the commit that moved origin/main"

# --- nothing waits: no batch ---
lines="$(wc -l <"$WORK/queue.git/queue-batches.log")"
queue || fail "an empty queue failed"
[[ "$(wc -l <"$WORK/queue.git/queue-batches.log")" -eq "$lines" ]] || fail "an empty queue ran a batch"

echo "test-queue: ok ($lines batches)"
