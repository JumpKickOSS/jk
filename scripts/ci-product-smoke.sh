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
trap 'rm -rf "$workdir"' EXIT
cd "$workdir"
"$jk_bin" new smoke-app --lang java --no-ansi
cd smoke-app
"$jk_bin" build --no-ansi
