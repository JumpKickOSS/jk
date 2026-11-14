#!/usr/bin/env bash
# Every place a minimum jk version is written for this tree, and what jumpkick.build serves, one
# row each, so a release can check that its floors agree before and after it publishes.
#
# Usage: scripts/release-floors.sh
# Columns: what, where, value. Read-only; the two jumpkick.build rows need the network.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT" || exit 2
row() { printf '%-34s %-58s %s\n' "$1" "$2" "${3:-<none>}"; }
first() { sed -n "$1" "$2" 2>/dev/null | head -1; }

row "release version" "jk.toml version" "$(first 's/^version[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' jk.toml)"
row "installer floor" "install.sh RELEASE_FLOOR" "$(first 's/^ *RELEASE_FLOOR="\([^"]*\)".*/\1/p' install.sh)"
row "installer floor" "hosting/public/install.sh RELEASE_FLOOR" "$(first 's/^ *RELEASE_FLOOR="\([^"]*\)".*/\1/p' hosting/public/install.sh)"
# shellcheck disable=SC2016 # $ReleaseFloor is PowerShell text matched literally
row "installer floor" "install.ps1 \$ReleaseFloor" "$(first 's/^\$ReleaseFloor = "\([^"]*\)".*/\1/p' install.ps1)"
# shellcheck disable=SC2016 # as above
row "installer floor" "hosting/public/install.ps1 \$ReleaseFloor" "$(first 's/^\$ReleaseFloor = "\([^"]*\)".*/\1/p' hosting/public/install.ps1)"
row "this tree's lock floor" "jk-lock.toml jk-min" "$(first 's/^jk-min = "\([^"]*\)".*/\1/p' jk-lock.toml)"
row "new locks' floor (format floor)" "LockfileWriter.FORMAT_FLOOR" \
  "$(first 's/.*FORMAT_FLOOR = "\([^"]*\)".*/\1/p' shared/core/src/main/java/cc/jumpkick/lock/LockfileWriter.java)"
while IFS= read -r line; do
  row "guard rule floor" "jk-guards.toml:${line%%:*} requires-jk" "$(sed 's/.*"\([^"]*\)".*/\1/' <<<"${line#*:}")"
done < <(grep -n '^requires-jk' jk-guards.toml)
row "bootstrap pin" ".jk/ci/ci-bootstrap-version" "$(tr -d '[:space:]' <.jk/ci/ci-bootstrap-version)"
row "bootstrap bridge" ".jk/ci/ci-bootstrap-reader" "$( [[ -f .jk/ci/ci-bootstrap-reader ]] && tr -d '[:space:]' <.jk/ci/ci-bootstrap-reader)"
lock_floors="$(grep -rl --include=jk-lock.toml '^jk-min' templates 2>/dev/null | wc -l | tr -d ' ')"
row "template locks" "templates/**/jk-lock.toml (jk new writes the lock)" "$lock_floors files"
row "VS Code client" "clients/vscode/package.json engines" "$(sed -n 's/.*"vscode": *"\([^"]*\)".*/vscode \1; no jk floor/p' clients/vscode/package.json)"
row "IntelliJ client" "clients/intellij plugin.xml" "$(grep -q 'jk-min\|requires-jk' clients/intellij/src/main/resources/META-INF/plugin.xml && echo declares || echo 'no jk floor')"
row "served pointer" "https://jumpkick.build/releases/latest/VERSION" "$(curl -fsSL https://jumpkick.build/releases/latest/VERSION 2>/dev/null | tr -d '[:space:]')"
row "served installer floor" "https://jumpkick.build/install.sh RELEASE_FLOOR" \
  "$(curl -fsSL https://jumpkick.build/install.sh 2>/dev/null | sed -n 's/^ *RELEASE_FLOOR="\([^"]*\)".*/\1/p' | head -1)"
