#!/usr/bin/env bash
# Network-free fixtures for scripts/gate/class-records.sh: a member's records sit where jk keeps
# them (its real path's digest under the cache); export copies them; import adds another
# checkout's rows without their keys, keeps every row jk wrote here and the headers, replaces a row
# an earlier import added only with a newer one, and adds nothing twice; classes names each class
# of an integration launch or of a run's only launch (named "") once, and who recorded it: jk here
# when it has a row of its own, whatever the Full tier shipped beside it.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-class-records-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
CR="$ROOT/scripts/gate/class-records.sh"

fail() {
  echo "test-class-records: $*" >&2
  exit 1
}

# checkout <dir>: a workspace of one member, mod/a.
checkout() {
  mkdir -p "$1/mod/a"
  printf '[workspace]\nmodules = [\n  "mod/a", # the one member\n]\n' >"$1/jk.toml"
  git -C "$1" init -q
}
checkout "$WORK/full"
checkout "$WORK/queue"
H='{"kind":"suite","module":"mod/a","ranAt":1,"launches":["test:integration"]}'
row() { # <launch> <class> <ranAt> [<classKey>]
  printf '{"launch":"%s","container":["a","%s"],"outcome":"passed","tests":1,"ranAt":%s%s,"reads":["mod/a/x.txt"],"jvm":{"props":[]}}\n' \
    "$1" "$2" "$3" "${4:+,\"classKey\":\"$4\"}"
}

# Where jk keeps the records: the first 16 hex digits of the sha256 of the member's real path.
real="$(cd "$WORK/full/mod/a" && pwd -P)"
if command -v sha256sum >/dev/null; then sum=(sha256sum); else sum=(shasum -a 256); fi
digest="$(printf %s "$real" | "${sum[@]}" | cut -c1-16)"
want="$WORK/cache-full/projects/$digest/suite-reads/test-classes.ndjson"
got="$(cd "$WORK/full" && JK_CACHE_DIR="$WORK/cache-full" bash "$CR" path mod/a)"
[[ "$got" == "$want" ]] || fail "path: want $want, got $got"
got="$(cd "$WORK/full" && env -u JK_CACHE_DIR JK_HOME="$WORK/home" bash "$CR" path mod/a)"
[[ "$got" == "$WORK/home/cache/projects/$digest/suite-reads/test-classes.ndjson" ]] || fail "path under JK_HOME: $got"

# The Full tier's records: every class, each with its key.
mkdir -p "$(dirname "$want")"
{
  echo "$H"
  row test:integration ATest 500 kA
  row test:integration BTest 500 kB
  row test:test CTest 500 kC
  row test:integration DTest 500 kD
  row test:integration ETest 100 kE
  row test:integration FTest 500 kF
} >"$want"
(cd "$WORK/full" && JK_CACHE_DIR="$WORK/cache-full" bash "$CR" export "$WORK/ship")
cmp -s "$want" "$WORK/ship/mod/a/test-classes.ndjson" || fail "export did not copy mod/a's records"

# The queue's own records: ATest ran here (keyed), DTest and ETest an earlier import added.
q="$(cd "$WORK/queue" && JK_CACHE_DIR="$WORK/cache-queue" bash "$CR" path mod/a)"
mkdir -p "$(dirname "$q")"
{
  echo "$H"
  row test:integration ATest 200 own
  row test:integration DTest 300
  row test:integration ETest 300
  row "" FTest 300 own
} >"$q"
imp() { (cd "$WORK/queue" && JK_CACHE_DIR="$WORK/cache-queue" bash "$CR" import "$WORK/ship" mod/a); }
imp

[[ "$(head -1 "$q")" == "$H" ]] || fail "the header did not stay first: $(head -1 "$q")"
[[ "$(jq -sr '[.[] | select(.kind)] | length' "$q")" == 1 ]] || fail "the shipped header was added: $(cat "$q")"
of() { jq -cr --arg c "$1" 'select(.container[1]? == $c)' "$q"; }
[[ "$(of ATest | jq -r '.classKey + " " + (.ranAt | tostring)')" == "own 200" ]] || fail "a row jk wrote here changed: $(of ATest)"
[[ "$(of BTest | jq -r '.classKey // "none"')" == none ]] || fail "a shipped row kept its key: $(of BTest)"
[[ "$(of BTest | jq -r '.reads[0]')" == mod/a/x.txt ]] || fail "the shipped row lost its reads: $(of BTest)"
[[ "$(of CTest | jq -r '.launch')" == test:test ]] || fail "a row of another launch was not added: $(of CTest)"
[[ "$(of DTest | jq -r '.ranAt')" == 500 ]] || fail "an imported row was not replaced by a newer one: $(of DTest)"
[[ "$(of ETest | jq -r '.ranAt')" == 300 ]] || fail "an imported row was replaced by an older one: $(of ETest)"
[[ "$(of DTest | jq -r '.classKey // "none"')" == none ]] || fail "a replacing row kept its key: $(of DTest)"

# Importing again adds nothing.
before="$(cat "$q")"
imp
[[ "$(cat "$q")" == "$before" ]] || fail "a second import changed the records: $(diff <(echo "$before") "$q")"

got="$(cd "$WORK/queue" && JK_CACHE_DIR="$WORK/cache-queue" bash "$CR" classes mod/a | tr '\n' ';')"
[[ "$got" == "a.ATest here;a.BTest shipped;a.DTest shipped;a.ETest shipped;a.FTest here;" ]] || fail "classes: $got"

# A member the Full tier shipped nothing for, and one with no records here: left alone, no error.
rm -rf "$WORK/ship/mod/a"
imp
[[ "$(cat "$q")" == "$before" ]] || fail "an import of nothing changed the records"
rm -f "$q"
imp
[[ ! -e "$q" ]] || fail "an import of nothing wrote a record file"
[[ -z "$(cd "$WORK/queue" && JK_CACHE_DIR="$WORK/cache-queue" bash "$CR" classes mod/a)" ]] || fail "classes of no records"

# A member with no records here gets the shipped rows alone.
(cd "$WORK/full" && JK_CACHE_DIR="$WORK/cache-full" bash "$CR" export "$WORK/ship")
imp
[[ "$(jq -sr 'map(.container[1]) | join(",")' "$q")" == "ATest,BTest,CTest,DTest,ETest,FTest" ]] || fail "rows into no records: $(cat "$q")"

# Bad arguments are refused.
if (cd "$WORK/queue" && bash "$CR" import "$WORK/ship") 2>/dev/null; then fail "import with no member was accepted"; fi
if (cd "$WORK/queue" && bash "$CR" nonsense x) 2>/dev/null; then fail "an unknown command was accepted"; fi

echo "test-class-records: ok"
