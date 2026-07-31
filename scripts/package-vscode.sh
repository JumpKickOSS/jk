#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Package the JumpKick VS Code extension as a VSIX.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXT="$ROOT/clients/vscode"
cd "$EXT"
if ! command -v npm >/dev/null 2>&1; then
  echo "npm is required to package the VS Code extension" >&2
  exit 1
fi
npm install --no-fund --no-audit
npm run package
ls -la "$EXT"/*.vsix
echo "Install in VS Code: Extensions → … → Install from VSIX…"
