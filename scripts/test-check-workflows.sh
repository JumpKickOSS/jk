#!/usr/bin/env bash
# Fixtures for scripts/check-workflows.sh: a workflow with every action pinned to a commit and a
# read-only token passes; a floating tag, a sha without its tag comment, a docker image without a
# digest, a missing or writing top-level permissions block and a write-all job are each refused by
# name. Then the repository's own workflows must pass.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-check-workflows.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
CHECK="$ROOT/scripts/check-workflows.sh"
SHA="11d5960a326750d5838078e36cf38b85af677262"
DIGEST="sha256:0000000000000000000000000000000000000000000000000000000000000000"

# One valid workflow around the given permissions block and step lines, so actionlint (when
# present) judges the fixture as a workflow and the checker's verdict is about its rules alone.
workflow() { # file permissions-block steps...
  local file="$1" perms="$2"
  shift 2
  {
    echo "name: fixture"
    echo "on: push"
    [[ -n "$perms" ]] && printf '%s\n' "$perms"
    echo "jobs:"
    echo "  one:"
    echo "    runs-on: ubuntu-latest"
    echo "    steps:"
    for step in "$@"; do printf '%s\n' "$step"; done
  } >"$file"
}

passes() { # file
  if ! "$CHECK" "$1" >"$WORK/last.log" 2>&1; then
    cat "$WORK/last.log" >&2
    echo "test-check-workflows: $(basename "$1") was refused" >&2
    exit 1
  fi
}
refuses() { # file expected-text
  if "$CHECK" "$1" >"$WORK/last.log" 2>&1; then
    echo "test-check-workflows: $(basename "$1") was accepted ($2)" >&2
    exit 1
  fi
  grep -q -- "$2" "$WORK/last.log" || {
    cat "$WORK/last.log" >&2
    echo "test-check-workflows: $(basename "$1") refused without naming '$2'" >&2
    exit 1
  }
}

READ=$'permissions:\n  contents: read'
CHECKOUT="      - uses: actions/checkout@$SHA # v4.4.0"

workflow "$WORK/ok.yml" "$READ" "$CHECKOUT" \
  "      - uses: ./.github/actions/local" \
  "      - uses: docker://ghcr.io/owner/image@$DIGEST" \
  "      - uses: 'owner/quoted@$SHA' # 1.2.3" \
  "      - run: echo hi"
passes "$WORK/ok.yml"

workflow "$WORK/ok-read-all.yml" "permissions: read-all" "$CHECKOUT"
passes "$WORK/ok-read-all.yml"

workflow "$WORK/ok-job-elevates.yml" "$READ" "$CHECKOUT"
sed -i 's/^    runs-on: ubuntu-latest$/    runs-on: ubuntu-latest\n    permissions:\n      contents: write\n      id-token: write/' "$WORK/ok-job-elevates.yml"
passes "$WORK/ok-job-elevates.yml"

workflow "$WORK/floating-tag.yml" "$READ" "      - uses: actions/checkout@v4"
refuses "$WORK/floating-tag.yml" "pin the action to a full commit sha"

workflow "$WORK/branch-ref.yml" "$READ" "      - uses: actions/checkout@main"
refuses "$WORK/branch-ref.yml" "pin the action to a full commit sha"

workflow "$WORK/short-sha.yml" "$READ" "      - uses: actions/checkout@11d5960 # v4.4.0"
refuses "$WORK/short-sha.yml" "pin the action to a full commit sha"

workflow "$WORK/no-tag-comment.yml" "$READ" "      - uses: actions/checkout@$SHA"
refuses "$WORK/no-tag-comment.yml" "trailing comment"

workflow "$WORK/docker-tag.yml" "$READ" "      - uses: docker://ghcr.io/owner/image:latest"
refuses "$WORK/docker-tag.yml" "@sha256: digest"

workflow "$WORK/no-permissions.yml" "" "$CHECKOUT"
refuses "$WORK/no-permissions.yml" "no top-level permissions"

workflow "$WORK/top-write.yml" $'permissions:\n  contents: write' "$CHECKOUT"
refuses "$WORK/top-write.yml" "grants read only"

workflow "$WORK/top-write-all.yml" "permissions: write-all" "$CHECKOUT"
refuses "$WORK/top-write-all.yml" "read-only"

workflow "$WORK/job-write-all.yml" "$READ" "$CHECKOUT"
sed -i 's/^    runs-on: ubuntu-latest$/    runs-on: ubuntu-latest\n    permissions: write-all/' "$WORK/job-write-all.yml"
refuses "$WORK/job-write-all.yml" "write-all"

# Two findings in one file are both reported, and the count is theirs.
workflow "$WORK/two.yml" "" "      - uses: actions/checkout@v4"
refuses "$WORK/two.yml" "2 finding(s)"

# The repository's own workflows hold to the rules the fixtures describe.
"$CHECK" >"$WORK/tree.log" 2>&1 || { cat "$WORK/tree.log" >&2; echo "test-check-workflows: the tree's workflows were refused" >&2; exit 1; }

echo "test-check-workflows: ok"
