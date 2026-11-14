#!/usr/bin/env bash
# Fixture test for scripts/ci-bootstrap.sh, network-free: a throwaway checkout whose
# .jk/ci/ci-bootstrap-reader names a bridge commit, a curl on PATH that serves an installer writing a
# fake jk, and that fake jk logging every command. The bridge must be relocked, built and installed
# as a snapshot (never `jk install`): it carries the version of the tree it bootstraps, and a bridge
# installed as that release would hold the shelf slots the tree's own install refuses to replace.
# Without a reader, the pinned release alone is installed and nothing is built.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(cd "$(mktemp -d "${TMPDIR:-/tmp}/jk-ci-bootstrap-test.XXXXXX")" && pwd)"
trap 'rm -rf "$WORK"' EXIT

fail() {
  echo "test-ci-bootstrap: $1" >&2
  [[ -f "$WORK/out.log" ]] && cat "$WORK/out.log" >&2
  exit 1
}

REPO="$WORK/repo"
mkdir -p "$REPO/scripts" "$REPO/.jk/ci" "$WORK/bin" "$WORK/tmp"
cp "$ROOT/scripts/ci-bootstrap.sh" "$REPO/scripts/"
printf '0.1.0\n' >"$REPO/.jk/ci/ci-bootstrap-version"
printf 'version = "0.2.0"\n' >"$REPO/jk.toml"
git -C "$REPO" init -q -b main
git -C "$REPO" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false add -A
git -C "$REPO" -c user.name=t -c user.email=t@localhost -c commit.gpgsign=false commit -q -m bridge
bridge_sha="$(git -C "$REPO" rev-parse HEAD)"

# The installer the fake curl serves: writes a jk that logs "<cwd basename> <args>".
cat >"$WORK/install.sh" <<'SH'
#!/usr/bin/env bash
mkdir -p "$JK_HOME/bin"
cat >"$JK_HOME/bin/jk" <<'JK'
#!/usr/bin/env bash
printf '%s %s\n' "$(basename "$PWD")" "$*" >>"$FIXTURE_JK_LOG"
exit 0
JK
chmod +x "$JK_HOME/bin/jk"
printf 'installed %s\n' "${JK_VERSION:-latest}" >>"$FIXTURE_JK_LOG"
SH
cat >"$WORK/bin/curl" <<SH
#!/usr/bin/env bash
cat "$WORK/install.sh"
SH
chmod +x "$WORK/bin/curl"

run_bootstrap() {
  : >"$WORK/jk.log"
  env PATH="$WORK/bin:$PATH" JK_HOME="$WORK/home" RUNNER_TEMP="$WORK/tmp" FIXTURE_JK_LOG="$WORK/jk.log" \
    bash "$REPO/scripts/ci-bootstrap.sh" >"$WORK/out.log" 2>&1
}

# No reader: the pinned release only.
run_bootstrap || fail "the bootstrap without a bridge failed"
grep -qx "installed 0.1.0" "$WORK/jk.log" || fail "the pinned release 0.1.0 was not installed"
if grep -q ' build\| install\| snapshot' "$WORK/jk.log"; then fail "a bootstrap without a bridge built something"; fi
echo "ok   no bridge: the pinned release alone"

# With a reader: the bridge is relocked, built and installed as a snapshot, twice.
printf '%s\n' "$bridge_sha" >"$REPO/.jk/ci/ci-bootstrap-reader"
run_bootstrap || fail "the bootstrap with a bridge failed"
grep -q '^jk-bootstrap-bridge lock -F' "$WORK/jk.log" || fail "the bridge was not relocked"
grep -q '^jk-bootstrap-bridge build --skip-tests' "$WORK/jk.log" || fail "the bridge was not built"
[[ "$(grep -c '^jk-bootstrap-bridge snapshot --skip-tests' "$WORK/jk.log")" == 2 ]] \
  || fail "the bridge was not installed as a snapshot in two passes"
if grep -q '^jk-bootstrap-bridge install' "$WORK/jk.log"; then
  fail "the bridge was installed as a release: it would collide with the tree it bootstraps"
fi
echo "ok   bridge: relocked, built, installed as a snapshot"

git -C "$REPO" worktree remove --force "$WORK/tmp/jk-bootstrap-bridge" >/dev/null 2>&1 || true
echo "test-ci-bootstrap: ok"
