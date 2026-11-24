#!/usr/bin/env bash
# Focused product smoke for macOS/Windows nightly: the engine answers, then a fresh
# project builds. Thin client on PATH (or JK_BIN). Not a native-image build.
set -euo pipefail

jk_bin="${JK_BIN:-jk}"
if ! command -v "$jk_bin" >/dev/null 2>&1; then
  echo "jk not on PATH (JK_BIN=$jk_bin)" >&2
  exit 1
fi

"$jk_bin" engine status --no-ansi

workdir="${TMPDIR:-/tmp}/jk-os-smoke-$$"
mkdir -p "$workdir"
# The engine may still write the project's target/ after the build returns, so removing it waits for
# that, up to SMOKE_CLEANUP_WAIT seconds; a directory left behind is a warning, never the verdict.
cleanup() {
  local rc=$? i=0
  until rm -rf "$workdir" 2>/dev/null || [ "$i" -ge "${SMOKE_CLEANUP_WAIT:-60}" ]; do
    i=$((i + 1))
    sleep 1
  done
  [ ! -e "$workdir" ] || echo "warning: could not remove $workdir" >&2
  exit "$rc"
}
trap cleanup EXIT
cd "$workdir"
"$jk_bin" new smoke-app --lang java --no-ansi
cd smoke-app
"$jk_bin" build --no-ansi
