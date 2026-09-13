#!/usr/bin/env bash
# Fixtures for scripts/release-notes.sh in a scratch repository: the notes open with the version's
# own highlights entry and no other's, list the non-merge commits since the previous v* tag by
# version order (an explicit previous tag is honoured), run to HEAD when the tag is not cut yet,
# close with a compare link when the repository slug is known, cover a first release with no
# previous tag, and refuse a version without a highlights entry, a malformed version and a previous
# tag that does not exist.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-release-notes.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
SCRIPT="$ROOT/scripts/release-notes.sh"
REPO="$WORK/repo"
DOC="$WORK/releases.md"
export RELEASE_NOTES_REPO="$REPO" RELEASE_NOTES_DOC="$DOC" GIT_CONFIG_GLOBAL=/dev/null GIT_CONFIG_NOSYSTEM=1
unset GITHUB_REPOSITORY

g() { git -C "$REPO" -c user.name=fixture -c user.email=fixture@example.invalid -c commit.gpgsign=false -c init.defaultBranch=main "$@"; }
commit() { echo "$1" >>"$REPO/log.txt"; g add log.txt; g commit -q -m "$1"; }

mkdir -p "$REPO"
g init -q
commit "The first feature lands"
g tag v1.0.0
commit "The second feature lands"
g checkout -q -b topic
commit "A topic commit"
g checkout -q main
g merge -q --no-ff -m "Merge branch topic" topic
commit "The third feature lands"
g tag v1.1.0
# A tag newer by version on an older commit: the previous release is the newest tag below this
# version in version order, never the nearest tag in history.
g tag v1.10.0 v1.0.0
commit "Unreleased work"

cat >"$DOC" <<'EOF'
# Releases

## Versioning

Some text with ### 1.1.0 in prose that is not a heading.

## Highlights

Intro line under the section heading.

### 1.2.0

- Unreleased highlight.

### 1.1.0

- The second and third features, in one line.
- A second bullet with `code`.

### 1.0.0

- The first feature.

## Signing

### 1.1.0

Not a highlights entry: a different section's subsection.
EOF

fails() { echo "test-release-notes: $1" >&2; [[ -f "$WORK/out.md" ]] && cat "$WORK/out.md" >&2; exit 1; }

# The release notes for a cut tag.
"$SCRIPT" 1.1.0 >"$WORK/out.md"
grep -q '^## Highlights$' "$WORK/out.md" || fails "no Highlights heading"
grep -q '^- The second and third features, in one line.$' "$WORK/out.md" || fails "the 1.1.0 entry is missing"
grep -q '^- A second bullet with ' "$WORK/out.md" || fails "the entry is cut short"
grep -q 'The first feature\.' "$WORK/out.md" && fails "the 1.0.0 entry leaked in"
grep -q 'Unreleased highlight' "$WORK/out.md" && fails "the 1.2.0 entry leaked in"
grep -q 'Intro line' "$WORK/out.md" && fails "the section intro leaked in"
grep -q "Not a highlights entry" "$WORK/out.md" && fails "another section's ### 1.1.0 leaked in"
grep -q '^## Changes since v1.0.0$' "$WORK/out.md" || fails "the previous tag is not v1.0.0 (v1.10.0 is newer by version, on an older commit)"
grep -q '^- The second feature lands (' "$WORK/out.md" || fails "the second feature is missing from the commit list"
grep -q '^- The third feature lands (' "$WORK/out.md" || fails "the third feature is missing from the commit list"
grep -q '^- A topic commit (' "$WORK/out.md" || fails "a commit reached through a merge is missing"
grep -q 'Merge branch' "$WORK/out.md" && fails "a merge commit is listed"
grep -q 'The first feature lands' "$WORK/out.md" && fails "a commit before the previous tag is listed"
grep -q 'Unreleased work' "$WORK/out.md" && fails "a commit after the tag is listed"
grep -q 'Full diff' "$WORK/out.md" && fails "a compare link with no known slug"

# The slug from the environment closes the notes with a compare link.
GITHUB_REPOSITORY=owner/repo "$SCRIPT" 1.1.0 >"$WORK/out.md"
grep -q '^Full diff: https://github.com/owner/repo/compare/v1.0.0...v1.1.0$' "$WORK/out.md" || fails "no compare link for the slug"

# An explicit previous tag.
"$SCRIPT" 1.2.0 v1.0.0 >"$WORK/out.md"
grep -q '^## Changes since v1.0.0$' "$WORK/out.md" || fails "the explicit previous tag is not honoured"
grep -q '^- The second feature lands (' "$WORK/out.md" || fails "the explicit range is not honoured"

# A version whose tag is not cut yet runs to HEAD.
"$SCRIPT" 1.2.0 >"$WORK/out.md"
grep -q '^- Unreleased highlight.$' "$WORK/out.md" || fails "the 1.2.0 entry is missing"
grep -q '^## Changes since v1.1.0$' "$WORK/out.md" || fails "the previous tag of an uncut 1.2.0 is not v1.1.0"
grep -q '^- Unreleased work (' "$WORK/out.md" || fails "the unreleased commit is missing"
grep -q 'The third feature lands' "$WORK/out.md" && fails "a released commit is listed for the uncut version"

# A first release has no previous tag: every commit, under Changes.
"$SCRIPT" 1.0.0 >"$WORK/out.md"
grep -q '^## Changes$' "$WORK/out.md" || fails "a first release must head its list Changes"
grep -q '^- The first feature lands (' "$WORK/out.md" || fails "the first release's commit is missing"
grep -q 'The second feature lands' "$WORK/out.md" && fails "a commit after the first release is listed"
rm -f "$WORK/out.md"

refuses() { # args... ; last argument is the expected text
  local expected="${*: -1}"
  set -- "${@:1:$#-1}"
  if "$SCRIPT" "$@" >/dev/null 2>"$WORK/err.txt"; then
    echo "test-release-notes: '$*' was accepted ($expected)" >&2
    exit 1
  fi
  grep -q -- "$expected" "$WORK/err.txt" || { cat "$WORK/err.txt" >&2; echo "test-release-notes: '$*' refused without saying '$expected'" >&2; exit 1; }
}
refuses 9.9.9 "no '### 9.9.9' entry under '## Highlights'"
refuses v1.1.0 "is not a version"
refuses "1.1.0; rm -rf /" "is not a version"
refuses 1.1.0 v0.0.1 "previous tag 'v0.0.1' does not exist"

echo "test-release-notes: ok"
