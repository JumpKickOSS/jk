#!/usr/bin/env bash
# Fixture test for scripts/reinstall-chain.sh, with a fake jk on a throwaway checkout. Each commit
# names the engine level it needs (.needs) and installs (.provides). HEAD also adds a plugin that
# owns [ide] and a member that opens [ide]. From an install of the first commit the chain bisects
# to the newest buildable commit twice, then installs HEAD without the [ide] member (forcing the
# scratch relock the fake refuses), then the checkout. An install already at HEAD whose checkout
# fails stops at once, naming that.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-reinstall-chain-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

REPO="$WORK/repo"
STATE="$WORK/state"
export JK_HOME="$WORK/home" TMPDIR="$WORK/tmp"
mkdir -p "$REPO" "$STATE" "$JK_HOME/lib/jk-engine" "$WORK/bin" "$TMPDIR"

# The fake jk. build: refuses a tree that needs a newer engine, an unowned table in a listed
# member, or an edited jk.toml under an unforced lock. install: raises the level, adds the tree's
# plugin tables, stamps the engine jar with the commit time and logs what it installed.
cat >"$WORK/bin/jk" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
level="$(cat "$FAKE_STATE/level")"
case "$1" in
  build)
    [ -f .broken ] && { echo "E compile: broken"; exit 1; }
    [ "$(cat .needs)" -le "$level" ] || { echo "E build: this engine is too old"; exit 1; }
    while IFS= read -r member; do
      for table in $(sed -n 's/^\[\([a-z-]*\)\]$/\1/p' "$member/jk.toml"); do
        grep -qx "$table" "$FAKE_STATE/tables" \
          || { echo "E lock: [$table] is not owned by any installed plugin — add it under [plugins]"; exit 1; }
      done
    done < <(sed -n 's/^ *"\([^"]*\)",$/\1/p' jk.toml)
    if ! git diff --quiet -- jk.toml && git diff --quiet -- jk-lock.toml; then
      echo "E lock: jk-lock.toml was written by an older build of the same version"; exit 1
    fi
    ;;
  install)
    cat .provides >"$FAKE_STATE/level"
    for plugin in plugins/*/jk-plugin.toml; do
      [ -f "$plugin" ] && sed -n 's/^table = "\(.*\)"$/\1/p' "$plugin" >>"$FAKE_STATE/tables"
    done
    stamp="$(TZ=UTC git log -1 --format=%cd --date=format-local:%Y-%m-%dT%H:%M:%SZ)"
    python3 - "$JK_HOME/lib/jk-engine/jk-engine.jar" "$stamp" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as jar:
    jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nBuild-Time: " + sys.argv[2] + "\n")
PY
    edited=""; git diff --quiet -- jk.toml || edited=" edited"
    echo "$(git log -1 --format=%s)$edited" >>"$FAKE_STATE/installs"
    ;;
  lock) echo "# forced" >>jk-lock.toml ;;
esac
FAKE
chmod +x "$WORK/bin/jk"
export JK="$WORK/bin/jk" FAKE_STATE="$STATE"
echo 'jar = "jk-engine.jar"' >"$JK_HOME/lib/jk-engine/jk-engine.toml"

git -C "$REPO" init -q
git -C "$REPO" config user.email fixture@example.com
git -C "$REPO" config user.name fixture
mkdir -p "$REPO/a"
echo 'name = "a"' >"$REPO/a/jk.toml"
echo 'version = 1' >"$REPO/jk-lock.toml"
printf 'members = [\n  "a",\n]\n' >"$REPO/jk.toml"
hour=0
commit() {
  echo "$2" >"$REPO/.needs"
  echo "$3" >"$REPO/.provides"
  git -C "$REPO" add -A
  hour=$((hour + 1))
  GIT_COMMITTER_DATE="2026-10-01T0$hour:00:00Z" GIT_AUTHOR_DATE="2026-10-01T0$hour:00:00Z" \
    git -C "$REPO" commit -q -m "$1"
}
commit c1 0 1
commit c2 1 2
commit c3 2 3
mkdir -p "$REPO/plugins/ide" "$REPO/b"
echo 'table = "ide"' >"$REPO/plugins/ide/jk-plugin.toml"
echo 'name = "ide-plugin"' >"$REPO/plugins/ide/jk.toml"
printf 'name = "b"\n\n[ide]\n' >"$REPO/b/jk.toml"
mkdir -p "$REPO/fixtures/old"
printf '[ide]\n' >"$REPO/fixtures/old/jk.toml"
printf 'members = [\n  "a",\n  # the [ide] user\n  "b",\n  "plugins/ide",\n]\n' >"$REPO/jk.toml"
commit c4 3 4

# The install: c1, owning no plugin tables.
echo 1 >"$STATE/level"
: >"$STATE/tables"
: >"$STATE/installs"
git -C "$REPO" checkout -q HEAD~3
(cd "$REPO" && "$JK" install --skip-tests)
git -C "$REPO" checkout -q -
: >"$STATE/installs"

mkdir -p "$REPO/scripts"
cp "$ROOT/scripts/reinstall-chain.sh" "$REPO/scripts/"
if ! "$REPO/scripts/reinstall-chain.sh" >"$WORK/chain.log" 2>&1; then
  echo "test-reinstall-chain: the chain failed:" >&2; cat "$WORK/chain.log" >&2; exit 1
fi
expected="$(printf 'c2\nc3\nc4 edited\nc4\n')"
if [ "$(cat "$STATE/installs")" != "$expected" ]; then
  echo "test-reinstall-chain: installed, in order:" >&2; cat "$STATE/installs" >&2
  echo "expected:" >&2; echo "$expected" >&2; cat "$WORK/chain.log" >&2; exit 1
fi
grep -q "installed in 4 stage(s)" "$WORK/chain.log" \
  || { echo "test-reinstall-chain: no summary:" >&2; cat "$WORK/chain.log" >&2; exit 1; }
[ "$(git -C "$REPO" worktree list | wc -l | tr -d ' ')" = 1 ] \
  || { echo "test-reinstall-chain: the scratch worktree was left behind" >&2; exit 1; }
[ -z "$(git -C "$REPO" status --porcelain -- jk.toml jk-lock.toml)" ] \
  || { echo "test-reinstall-chain: the checkout was edited" >&2; exit 1; }

# Installed from HEAD, a checkout that does not build is its own failure.
touch "$REPO/.broken"
: >"$STATE/installs"
if "$REPO/scripts/reinstall-chain.sh" >"$WORK/broken.log" 2>&1; then
  echo "test-reinstall-chain: a broken checkout passed" >&2; exit 1
fi
grep -q "the installed jk was built from HEAD" "$WORK/broken.log" \
  || { echo "test-reinstall-chain: the broken checkout was not named:" >&2; cat "$WORK/broken.log" >&2; exit 1; }
[ ! -s "$STATE/installs" ] || { echo "test-reinstall-chain: a broken checkout installed something" >&2; exit 1; }

echo "test-reinstall-chain: ok"
