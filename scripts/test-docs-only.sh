#!/usr/bin/env bash
# Network-free fixtures for scripts/gate/docs-only.sh: write adds the docs a member's recordings show
# a suite reading to test-reads.txt, leaves out what the docs suite alone read, keeps a listed doc
# this host's cache never saw read, and drops one git no longer tracks; check names a recorded read
# the list lacks; range tells a change to a listed doc from one to a doc no test reads. Only the
# selections a gate runs count: the default suite, a declared suite, the guard suites; never a run
# of chosen classes.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-docs-only-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
D="$ROOT/scripts/gate/docs-only.sh"
LIST=scripts/gate/test-reads.txt

fail() {
  echo "test-docs-only: $*" >&2
  exit 1
}

sha12() { { sha256sum 2>/dev/null || shasum -a 256; } | cut -c1-12; }

# A workspace of one member, mod/a, with four docs; the list names one no suite here was seen
# reading (SECURITY.md) and one that is gone.
repo="$WORK/repo"
mkdir -p "$repo/mod/a" "$repo/docs" "$repo/scripts/gate"
printf '[workspace]\nmodules = ["mod/a"]\n' >"$repo/jk.toml"
printf 'project-id = "p-test"\n' >"$repo/jk-lock.toml"
for f in README.md SECURITY.md docs/read.md docs/docs-suite.md docs/free.md; do echo "$f" >"$repo/$f"; done
printf '%s\n' '# docs a test reads' SECURITY.md docs/gone.md >"$repo/$LIST"
git -C "$repo" init -q
git -C "$repo" add -A
git -C "$repo" -c user.name=t -c user.email=t@t commit -qm base

# Where jk keeps a member's recordings: one file per selection under projects/m-<tag>/suite-reads.
reads_dir="$WORK/cache/projects/m-$(printf '%s\n%s' p-test mod/a | sha12)/suite-reads"
mkdir -p "$reads_dir"
recorded() { printf '%s\n' "${@:2}" >"$reads_dir/test-inputs-$(printf '%s' "$1" | sha12)"; }
recorded 'suites=test;+tag=;-tag=' 'file ws:docs/read.md' 'file ws:mod/a/X.java'
recorded 'suites=docs;+tag=;-tag=' 'file ws:docs/docs-suite.md'
# A scratch class run alone that read the whole checkout keys a selection no gate runs.
recorded 'suites=test;+tag=;-tag=;class=ScratchProbe' 'file ws:docs/free.md' 'file ws:README.md' 'command git'
run() { (cd "$repo" && JK_CACHE_DIR="$WORK/cache" bash "$D" "$@"); }

[[ "$(run reads)" == docs/read.md ]] || fail "reads: want docs/read.md, got $(run reads)"

# A suite the root declares and its guard suites (`-g`) are a gate's selections too.
cp "$repo/jk.toml" "$WORK/jk.toml"
printf '%s\n' '[test]' 'guard-suites = [' '  "test", # the fast tier' '  "integration",' ']' '' \
  '[test.suites.release] # a tier' 'tags = ["release"]' >>"$repo/jk.toml"
recorded 'suites=test,integration;+tag=;-tag=' 'file ws:README.md'
recorded 'suites=release;+tag=;-tag=' 'file ws:docs/free.md'
[[ "$(run reads | tr '\n' ' ')" == "README.md docs/free.md docs/read.md " ]] ||
  fail "reads of the declared and guard suites: got $(run reads)"
rm "$reads_dir/test-inputs-$(printf '%s' 'suites=test,integration;+tag=;-tag=' | sha12)" \
  "$reads_dir/test-inputs-$(printf '%s' 'suites=release;+tag=;-tag=' | sha12)"
mv "$WORK/jk.toml" "$repo/jk.toml"
run check 2>"$WORK/check.err" && fail "check passed with docs/read.md missing from the list"
grep -q 'a test reads docs/read.md, which test-reads.txt does not list' "$WORK/check.err" ||
  fail "check did not name docs/read.md: $(cat "$WORK/check.err")"

run write
got="$(grep -v '^#' "$repo/$LIST")"
want="$(printf '%s\n' SECURITY.md docs/read.md)"
[[ "$got" == "$want" ]] || fail "write: want SECURITY.md and docs/read.md, got: $got"
head -1 "$repo/$LIST" | grep -q '^# ' || fail "write dropped the header"
run check || fail "check failed after write"

# A second write on the same cache changes nothing.
before="$(cat "$repo/$LIST")"
run write
[[ "$(cat "$repo/$LIST")" == "$before" ]] || fail "a second write changed the list"

# With no project-id, no recordings: write still keeps what the list holds.
mv "$repo/jk-lock.toml" "$WORK/lock"
run write
[[ "$(cat "$repo/$LIST")" == "$before" ]] || fail "write with no recordings changed the list"
mv "$WORK/lock" "$repo/jk-lock.toml"

git -C "$repo" add -A
git -C "$repo" -c user.name=t -c user.email=t@t commit -qm list
base="$(git -C "$repo" rev-parse HEAD)"
echo more >>"$repo/docs/free.md"
git -C "$repo" -c user.name=t -c user.email=t@t commit -qam free
run range "$base" || fail "range: a doc no test reads is not docs-only"
echo more >>"$repo/SECURITY.md"
git -C "$repo" -c user.name=t -c user.email=t@t commit -qam security
run range "$base" 2>"$WORK/range.err" && fail "range: a change to SECURITY.md passed as docs-only"
grep -q 'a test reads SECURITY.md' "$WORK/range.err" || fail "range did not name SECURITY.md: $(cat "$WORK/range.err")"

echo "test-docs-only: ok"
