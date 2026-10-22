#!/usr/bin/env bash
# Fixture test for scripts/examples-lock-drift.sh, on a throwaway jk-examples checkout with one scenario: a clean tree
# and a checksum-only jk-lock.toml change pass; any other jk-lock.toml change, and any change or deletion
# of a sample's package-lock.json, fail with the file named.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-lock-drift-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# In-place sed that BSD sed (macOS) and GNU sed both read: the last argument is the file.
sed_i() { sed -i.bak "$@" && rm -f "${*: -1}.bak"; }

sample="05-recipes/sample"
lock="$sample/jk-lock.toml"
npm_lock="$sample/web/package-lock.json"

git -C "$WORK" init -q
git -C "$WORK" config user.email fixture@example.com
git -C "$WORK" config user.name fixture
mkdir -p "$WORK/$sample/web"
cat >"$WORK/$lock" <<'EOF'
version = 1
generated-by = "jk 0.14.0"
generated-by-build = "6d2260154724"
generated-by-build-time = "2026-10-04T21:18:26Z"

[[dependency]]
coordinate = "cc.jumpkick:jk-minified:0.12.0"
checksum = "sha256:1111111111111111111111111111111111111111111111111111111111111111"

[[dependency]]
coordinate = "org.junit.jupiter:junit-jupiter:6.1.3"
checksum = "sha256:2222222222222222222222222222222222222222222222222222222222222222"
EOF
cat >"$WORK/$npm_lock" <<'EOF'
{
  "name": "web",
  "lockfileVersion": 3,
  "packages": {
    "node_modules/vite": { "version": "8.3.0" }
  }
}
EOF
git -C "$WORK" add -A
git -C "$WORK" commit -q -m "scenario"

reset() { git -C "$WORK" checkout -q -- .; }

passes() {
  if ! "$ROOT/scripts/examples-lock-drift.sh" "$WORK" >"$WORK/last.log" 2>&1; then
    echo "test-examples-lock-drift: '$1' was refused:" >&2; cat "$WORK/last.log" >&2; exit 1
  fi
}
refuses_naming() {
  local what="$1" needle="$2"
  if "$ROOT/scripts/examples-lock-drift.sh" "$WORK" >"$WORK/last.log" 2>&1; then
    echo "test-examples-lock-drift: '$what' was accepted" >&2; cat "$WORK/last.log" >&2; exit 1
  fi
  grep -qF "$needle" "$WORK/last.log" \
    || { echo "test-examples-lock-drift: '$what' failed without naming '$needle':" >&2; cat "$WORK/last.log" >&2; exit 1; }
}

passes "a clean tree"

sed_i 's/sha256:1111/sha256:9999/' "$WORK/$lock"
passes "a first-party plugin checksum that moved"
reset

sed_i -e 's/6d2260154724/d43f1559f875/' -e 's/21:18:26Z/22:55:01Z/' -e 's/jk 0.14.0/jk 0.15.0/' "$WORK/$lock"
passes "a relock by another jk build, which restamps the writer lines"
reset

sed_i 's/junit-jupiter:6.1.3/junit-jupiter:6.2.0/' "$WORK/$lock"
refuses_naming "a dependency pin that moved" "junit-jupiter:6.2.0"
reset

sed_i 's/"8.3.0"/"8.4.0"/' "$WORK/$npm_lock"
refuses_naming "an npm lock the job rewrote" "$npm_lock"
reset

rm "$WORK/$npm_lock"
refuses_naming "an npm lock the job deleted" "$npm_lock"
reset

echo "test-examples-lock-drift: ok"
