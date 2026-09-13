#!/usr/bin/env bash
# Network-free fixtures for scripts/curated-integration.sh: the registry becomes one `jk test`
# per module naming every class exactly; a malformed line and an empty registry are refused by
# name; a red module does not stop the next one and the lane exits non-zero.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-curated-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
LANE="$ROOT/scripts/curated-integration.sh"

fail() {
  echo "test-curated-integration: $*" >&2
  exit 1
}

# ---- the registry becomes one command per module, classes named exactly -------------------------
cat >"$WORK/registry.txt" <<'EOF'
# a comment line and a blank line are skipped

clients/cli    | cc.jumpkick.cli.engine.EngineClientTest  | wire      | success,failure | The client half of the JSONL socket, handshake to stop.
server/engine  | cc.jumpkick.engine.EngineServerTest      | wire      | success,failure | The server half of the same socket, stale socket included.
clients/cli    | cc.jumpkick.command.pipeline.BuildCommandTest | workspace | success,failure | The build verb over a real workspace, compile error included.
EOF
printed="$(CURATED_REGISTRY="$WORK/registry.txt" JK=jk-under-test "$LANE" --print)"
expected="jk-under-test test --profile integration --no-ansi -m clients/cli --class cc.jumpkick.cli.engine.EngineClientTest --class cc.jumpkick.command.pipeline.BuildCommandTest
jk-under-test test --profile integration --no-ansi -m server/engine --class cc.jumpkick.engine.EngineServerTest"
[[ "$printed" == "$expected" ]] || {
  printf '%s\n' "$printed" >&2
  fail "the printed commands differ from the expected ones"
}

# ---- a malformed line is refused, at its line number -------------------------------------------
cat >"$WORK/bad.txt" <<'EOF'
clients/cli | cc.jumpkick.cli.engine.EngineClientTest | wire | success
EOF
if CURATED_REGISTRY="$WORK/bad.txt" "$LANE" --print >"$WORK/bad.log" 2>&1; then
  fail "a four-field line was accepted"
fi
grep -q 'bad.txt:1: 4 fields, expected 5' "$WORK/bad.log" || {
  cat "$WORK/bad.log" >&2
  fail "the four-field refusal does not name the line"
}

# ---- an empty registry is refused --------------------------------------------------------------
printf '# nothing here\n' >"$WORK/empty.txt"
if CURATED_REGISTRY="$WORK/empty.txt" "$LANE" --print >"$WORK/empty.log" 2>&1; then
  fail "an empty registry was accepted"
fi
grep -q 'names no classes' "$WORK/empty.log" || {
  cat "$WORK/empty.log" >&2
  fail "the empty-registry refusal is not the expected one"
}

# ---- a red module does not stop the next one, and the lane is red ------------------------------
mkdir -p "$WORK/bin"
cat >"$WORK/bin/jk" <<'EOF'
#!/usr/bin/env bash
# Records every invocation; the server/engine run is red.
printf '%s\n' "$*" >>"$FAKE_JK_LOG"
[[ "$*" == *"-m server/engine"* ]] && exit 1
exit 0
EOF
chmod +x "$WORK/bin/jk"
if FAKE_JK_LOG="$WORK/calls.log" CURATED_REGISTRY="$WORK/registry.txt" JK="$WORK/bin/jk" "$LANE" >"$WORK/run.log" 2>&1; then
  fail "a red module left the lane green"
fi
[[ "$(wc -l <"$WORK/calls.log")" -eq 2 ]] || {
  cat "$WORK/calls.log" >&2
  fail "the lane did not run every module"
}
grep -q 'server/engine is red' "$WORK/run.log" || fail "the red module is not named"
grep -q '1 module(s) red' "$WORK/run.log" || fail "the red count is not reported"

echo "test-curated-integration: ok"
