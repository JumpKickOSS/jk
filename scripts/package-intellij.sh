#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Package the JumpKick IntelliJ plugin. Downloads IntelliJ SDK once.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXT="$ROOT/clients/intellij"
cd "$EXT"
if ! command -v java >/dev/null 2>&1; then
  echo "java is required" >&2
  exit 1
fi
# The plugin is a standalone Gradle build with its own wrapper: the IntelliJ Platform Gradle
# plugin is how JetBrains ships plugins, and the product tree carries no other Gradle.
"$EXT/gradlew" buildPlugin --no-daemon
ls -la "$EXT"/build/distributions/*.zip 2>/dev/null || ls -la "$EXT"/build/distributions/
echo "Install: IntelliJ → Settings → Plugins → ⚙ → Install Plugin from Disk…"
