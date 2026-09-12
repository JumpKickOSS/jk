#!/usr/bin/env bash
# Lint every shell script this repository ships or runs: the installers (repo root and the copy
# the CDN serves), scripts/, and the POSIX wrapper template `jk wrapper` writes into projects.
#
# Usage:
#   scripts/shellcheck.sh            # the gate's command, locally and in ci.yml
# Severity is `info`, because the defect class that matters most here — an unquoted `$var` in a
# `[ ]` test (SC2086) — is an info-level finding. A finding that is intentional is silenced at
# its site with `# shellcheck disable=SCnnnn` and a reason, never here.
#
# The binary is used when PATH has one; a container runtime falls back to the koalaman image.
# With neither, the lint is skipped with a notice on a developer machine and fails under CI
# (the CI variable), so a runner missing the tool cannot pass by omission.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TARGETS=(install.sh hosting/public/install.sh clients/cli/src/main/resources/cc/jumpkick/command/toolchain/wrapper/jk.sh)
for script in scripts/*.sh; do
  TARGETS+=("$script")
done

if command -v shellcheck >/dev/null 2>&1; then
  RUNNER=(shellcheck)
elif command -v podman >/dev/null 2>&1; then
  RUNNER=(podman run --rm -v "$ROOT:/mnt:ro,Z" -w /mnt docker.io/koalaman/shellcheck:stable)
elif command -v docker >/dev/null 2>&1; then
  RUNNER=(docker run --rm -v "$ROOT:/mnt:ro" -w /mnt koalaman/shellcheck:stable)
elif [[ -n "${CI:-}" ]]; then
  echo "shellcheck: no shellcheck binary and no container runtime on this CI runner — install one" >&2
  exit 1
else
  echo "shellcheck: not installed — skipping ${#TARGETS[@]} scripts (dnf install ShellCheck, or put the static binary" >&2
  echo "shellcheck: from https://github.com/koalaman/shellcheck/releases on PATH; ci.yml does not skip)" >&2
  exit 0
fi

"${RUNNER[@]}" --severity=info --external-sources "${TARGETS[@]}"
echo "shellcheck: ${#TARGETS[@]} scripts clean"
