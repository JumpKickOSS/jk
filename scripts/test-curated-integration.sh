#!/usr/bin/env bash
# Network-free fixtures for scripts/curated-integration.sh: the registry becomes one `jk test
# --exclusive` over every module, `-c` so a red module does not stop the next, naming every class
# exactly and qualified with its module; `--with` adds a module whose whole suite runs in the same
# call and leaves its own classes unnamed; a malformed line, an empty registry and an unknown
# argument are refused by name; a red run makes the lane exit non-zero.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-curated-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
LANE="$ROOT/scripts/curated-integration.sh"

fail() {
  echo "test-curated-integration: $*" >&2
  exit 1
}

# ---- the registry becomes one command over every module, classes named exactly in theirs -------
cat >"$WORK/registry.txt" <<'EOF'
# a comment line, a blank line and a directive (the guard reads it) are skipped

integration-floor = 90
clients/cli    | cc.jumpkick.cli.engine.EngineClientTest  | wire      | success,failure | The client half of the JSONL socket, handshake to stop.
server/engine  | cc.jumpkick.engine.EngineServerTest      | wire      | success,failure | The server half of the same socket, stale socket included.
clients/cli    | cc.jumpkick.command.pipeline.BuildCommandTest | workspace | success,failure | The build verb over a real workspace, compile error included.
EOF
printed="$(CURATED_REGISTRY="$WORK/registry.txt" JK=jk-under-test "$LANE" --print)"
expected="jk-under-test test -s integration -c -X --no-ansi -m clients/cli --class clients/cli:cc.jumpkick.cli.engine.EngineClientTest --class clients/cli:cc.jumpkick.command.pipeline.BuildCommandTest -m server/engine --class server/engine:cc.jumpkick.engine.EngineServerTest"
[[ "$printed" == "$expected" ]] || {
  printf '%s\n' "$printed" >&2
  fail "the printed command differs from the expected one"
}

# ---- --with runs a module's whole suite beside the others' classes --------------------------------
printed="$(CURATED_REGISTRY="$WORK/registry.txt" JK=jk-under-test "$LANE" --with clients/cli --with e2e/cli --print)"
[[ "$printed" == "jk-under-test test -s integration -c -X --no-ansi -m clients/cli -m e2e/cli -m server/engine --class server/engine:cc.jumpkick.engine.EngineServerTest" ]] || {
  printf '%s\n' "$printed" >&2
  fail "--with clients/cli --with e2e/cli did not run their whole suites beside server/engine's classes"
}
printed="$(CURATED_REGISTRY="$WORK/registry.txt" JK=jk-under-test "$LANE" --print --with clients/cli --with server/engine)"
[[ "$printed" == "jk-under-test test -s integration -c -X --no-ansi -m clients/cli -m server/engine" ]] ||
  fail "with every module's whole suite run the lane printed: $printed"
if CURATED_REGISTRY="$WORK/registry.txt" "$LANE" --print --bogus >"$WORK/arg.log" 2>&1; then
  fail "an unknown argument was accepted"
fi
grep -q 'unknown argument --bogus' "$WORK/arg.log" || fail "the unknown argument is not named: $(cat "$WORK/arg.log")"

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

# ---- one run over every module, red when jk is, green in every module it names ---------------
mkdir -p "$WORK/bin"
cat >"$WORK/bin/jk" <<'EOF'
#!/usr/bin/env bash
# Records every invocation; a run over server/engine is red.
printf '%s\n' "$*" >>"$FAKE_JK_LOG"
[[ "$*" == *"-m server/engine"* ]] && exit 1
exit 0
EOF
chmod +x "$WORK/bin/jk"
if FAKE_JK_LOG="$WORK/calls.log" CURATED_REGISTRY="$WORK/registry.txt" JK="$WORK/bin/jk" "$LANE" >"$WORK/run.log" 2>&1; then
  fail "a red run left the lane green"
fi
[[ "$(cat "$WORK/calls.log")" == "${expected#jk-under-test }" ]] || {
  cat "$WORK/calls.log" >&2
  fail "the lane did not run its one command over every module"
}
grep -q 'red in clients/cli server/engine' "$WORK/run.log" || fail "the red run's modules are not named: $(cat "$WORK/run.log")"
: >"$WORK/calls.log"
cat >"$WORK/bin/jk" <<'EOF'
#!/usr/bin/env bash
# Records every invocation; every run is green.
printf '%s\n' "$*" >>"$FAKE_JK_LOG"
EOF
FAKE_JK_LOG="$WORK/calls.log" CURATED_REGISTRY="$WORK/registry.txt" JK="$WORK/bin/jk" "$LANE" --with e2e/cli >"$WORK/run.log" 2>&1 ||
  fail "a green run left the lane red: $(cat "$WORK/run.log")"
grep -q 'green in e2e/cli clients/cli server/engine$' "$WORK/run.log" || fail "the green run is not reported: $(cat "$WORK/run.log")"
[[ "$(cat "$WORK/calls.log")" == "test -s integration -c -X --no-ansi -m e2e/cli "* ]] ||
  fail "the lane with --with did not run one command: $(cat "$WORK/calls.log")"

echo "test-curated-integration: ok"
