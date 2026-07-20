#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Package the JumpKick IntelliJ plugin (ticket-1054). Downloads IntelliJ SDK once.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXT="$ROOT/clients/intellij"
cd "$EXT"
if ! command -v java >/dev/null 2>&1; then
  echo "java is required" >&2
  exit 1
fi
# Standalone Gradle wrapper via root if present
if [[ -x "$ROOT/gradlew" ]]; then
  "$ROOT/gradlew" -p "$EXT" buildPlugin --no-daemon
else
  echo "repo root ./gradlew required" >&2
  exit 1
fi
ls -la "$EXT"/build/distributions/*.zip 2>/dev/null || ls -la "$EXT"/build/distributions/
echo "Install: IntelliJ → Settings → Plugins → ⚙ → Install Plugin from Disk…"
