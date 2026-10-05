#!/usr/bin/env bash
# Install, into $JK_HOME, the jk that CI builds this tree with: the hosted release that
# .jk/ci-bootstrap-version pins, or, while .jk/ci-bootstrap-reader names a bridge commit, that
# commit built by the pinned release (docs/contributors/self-host.md, "The bootstrap chain").
#
# Usage: scripts/ci-bootstrap.sh
#   JK_HOME     the job's isolated home (required)
#   JK_CLIENT   passed through to the installer (`jvm` where the release serves no native client)
# The bridge is a checkout of its own beside the runner's temp dir. The pinned release relocks it in
# the format that release reads (`jk lock --force`: the bridge is a throwaway checkout, never
# committed) and builds it; the bridge's own jk then takes the home over, and every later step of
# the job builds this tree with it. Each workflow calls this script; no workflow spells a version.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${JK_HOME:?JK_HOME must name the isolated home of this job}"

version="$(tr -d '[:space:]' < "$ROOT/.jk/ci-bootstrap-version")"
echo "bootstrapping jk $version from https://jumpkick.build"
case "$(uname -s)" in
  MINGW* | MSYS* | CYGWIN*)
    JK_VERSION="$version" pwsh -NoProfile -Command 'Invoke-RestMethod https://jumpkick.build/install.ps1 | Invoke-Expression'
    ;;
  *)
    curl -fsSL https://jumpkick.build/install.sh | JK_VERSION="$version" bash
    ;;
esac
export PATH="$JK_HOME/bin:$PATH"
if [[ -n "${GITHUB_PATH:-}" ]]; then echo "$JK_HOME/bin" >> "$GITHUB_PATH"; fi

reader_file="$ROOT/.jk/ci-bootstrap-reader"
if [[ ! -f "$reader_file" ]]; then
  jk --version
  exit 0
fi

sha="$(tr -d '[:space:]' < "$reader_file")"
bridge="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/jk-bootstrap-bridge"
echo "building the bridge $sha with jk $version"
# A CI checkout is shallow; GitHub serves any reachable commit by its sha.
if ! git -C "$ROOT" cat-file -e "$sha^{commit}" 2>/dev/null; then
  git -C "$ROOT" fetch --no-tags --depth=1 origin "$sha"
fi
rm -rf "$bridge"
git -C "$ROOT" worktree add --detach "$bridge" "$sha"

if [[ "$(uname -s)" == MINGW* || "$(uname -s)" == MSYS* ]]; then
  # The release's engine keeps PATH and drops INCLUDE and LIB; with neither, native-image locates
  # Visual Studio 2022 itself (the same reset the workflows apply before their own native build).
  unset INCLUDE LIB LIBPATH EXTERNAL_INCLUDE
  PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -v -i 'Microsoft Visual Studio\|Windows Kits' | paste -sd:)"
  export PATH
  jk engine stop --now --no-ansi || true
fi

(
  cd "$bridge"
  jk lock --force --no-ansi
  jk build --skip-tests --no-ansi
  # The release's client stops after packaging the shelf; the bridge's own client, which the first
  # pass installs, completes the second.
  jk install --skip-tests --no-ansi || echo "first install pass incomplete; the bridge's client completes it"
  jk install --skip-tests --no-ansi
)
jk --version
jk engine status --no-ansi
