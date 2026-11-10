#!/usr/bin/env bash
# Fixtures for scripts/release-notes.sh in a scratch repository: the notes open with the version's
# own highlights entry and no other's, list the non-merge commits since the previous v* tag by
# version order (an explicit previous tag is honoured) folded under a <details>, run to HEAD when
# the tag is not cut yet, close with a compare link when the repository slug is known, cover a
# first release with no previous tag, and refuse a version without a highlights entry, an entry
# that breaks the writing rules, a malformed version and a previous tag that does not exist.
# --whats-new carries the version's entry and the older ones, newest first, and never a newer one.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-release-notes.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
SCRIPT="$ROOT/scripts/release-notes.sh"
REPO="$WORK/repo"
DOC="$WORK/whats-new.md"
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
# What's new

Intro line with ## 1.1.0 in prose that is not a heading.

## 1.2.0

- **Unreleased highlight.** Something a user will notice in the next release.

## 1.1.0

- **The second and third features land together.** One line about both of them.
- **A second bullet with `code`** that wraps onto
  a continuation line, as long entries do.

## 1.0.0

- **The first feature.** It arrived with the first release of all.

## 0.9.0

- this old entry breaks every rule and is history, so nobody checks it

## 2.0.0-bad-short

- **Short.**

## 2.0.0-bad-ticket

- **Fixed a crash in the index.** Closes JK-4XQ2R for everyone who saw it happen.

## 2.0.0-bad-hook

- The index no longer crashes when a file is deleted during a build of the workspace.

## 2.0.0-bad-prose

A paragraph instead of bullets, which the in-app dialog cannot show.

## 2.0.0-bad-many

- **One bullet that says enough to pass.** And a sentence after it.
- **Two bullets that say enough to pass.** And a sentence after it.
- **Three bullets that say enough to pass.** And a sentence after it.
- **Four bullets that say enough to pass.** And a sentence after it.
- **Five bullets that say enough to pass.** And a sentence after it.
- **Six bullets that say enough to pass.** And a sentence after it.
EOF
fails() { echo "test-release-notes: $1" >&2; [[ -f "$WORK/out.md" ]] && cat "$WORK/out.md" >&2; exit 1; }

# The release notes for a cut tag.
"$SCRIPT" 1.1.0 >"$WORK/out.md"
grep -q '^## Highlights$' "$WORK/out.md" || fails "no Highlights heading"
grep -q '^- \*\*The second and third features land together.\*\*' "$WORK/out.md" || fails "the 1.1.0 entry is missing"
grep -q '^  a continuation line' "$WORK/out.md" || fails "the entry is cut short"
grep -q 'The first feature\.' "$WORK/out.md" && fails "the 1.0.0 entry leaked in"
grep -q 'Unreleased highlight' "$WORK/out.md" && fails "the 1.2.0 entry leaked in"
grep -q 'Intro line' "$WORK/out.md" && fails "the section intro leaked in"
grep -q '^<details>$' "$WORK/out.md" || fails "the commit list is not folded"
grep -q '^<summary>Every change since v1.0.0 (3 commits)</summary>$' "$WORK/out.md" || fails "the fold's summary is wrong"
grep -q '^</details>$' "$WORK/out.md" || fails "the fold is not closed"
grep -q '^<summary>Every change since v1.0.0 ' "$WORK/out.md" || fails "the previous tag is not v1.0.0 (v1.10.0 is newer by version, on an older commit)"
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
grep -q '^<summary>Every change since v1.0.0 ' "$WORK/out.md" || fails "the explicit previous tag is not honoured"
grep -q '^- The second feature lands (' "$WORK/out.md" || fails "the explicit range is not honoured"

# A version whose tag is not cut yet runs to HEAD.
"$SCRIPT" 1.2.0 >"$WORK/out.md"
grep -q '^- \*\*Unreleased highlight.\*\*' "$WORK/out.md" || fails "the 1.2.0 entry is missing"
grep -q '^<summary>Every change since v1.1.0 ' "$WORK/out.md" || fails "the previous tag of an uncut 1.2.0 is not v1.1.0"
grep -q '^- Unreleased work (' "$WORK/out.md" || fails "the unreleased commit is missing"
grep -q 'The third feature lands' "$WORK/out.md" && fails "a released commit is listed for the uncut version"

# A first release has no previous tag: every commit, under Changes.
"$SCRIPT" 1.0.0 >"$WORK/out.md"
grep -q '^<summary>Every change (1 commits)</summary>$' "$WORK/out.md" || fails "a first release must fold every commit"
grep -q '^- The first feature lands (' "$WORK/out.md" || fails "the first release's commit is missing"
grep -q 'The second feature lands' "$WORK/out.md" && fails "a commit after the first release is listed"

# --whats-new: this version's entry and the older ones, newest first; never a newer one, never the
# commit list. An unchecked old entry rides along as history.
"$SCRIPT" --whats-new 1.1.0 >"$WORK/out.md"
grep -q "^# What's new in JumpKick$" "$WORK/out.md" || fails "the bundle has no title"
grep -q '^## 1.1.0$' "$WORK/out.md" || fails "the bundle lacks its own version"
grep -q '^## 1.0.0$' "$WORK/out.md" || fails "the bundle lacks an older version"
grep -q '^## 0.9.0$' "$WORK/out.md" || fails "the bundle lacks the oldest version"
grep -q 'Unreleased highlight' "$WORK/out.md" && fails "the bundle carries a newer version"
grep -q '<details>' "$WORK/out.md" && fails "the bundle carries the commit list"
[[ "$(grep -n '^## ' "$WORK/out.md" | head -1)" == *"## 1.1.0" ]] || fails "the bundle does not open with its own version"
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
refuses 9.9.9 "no '## 9.9.9' entry"
refuses 2.0.0-bad-short "40 at least"
refuses 2.0.0-bad-ticket "names a ticket"
refuses 2.0.0-bad-hook "bold hook"
refuses 2.0.0-bad-prose "not a bullet"
refuses 2.0.0-bad-many "6 bullets"
refuses --whats-new 2.0.0-bad-ticket "names a ticket"
refuses v1.1.0 "is not a version"
refuses "1.1.0; rm -rf /" "is not a version"
refuses 1.1.0 v0.0.1 "previous tag 'v0.0.1' does not exist"

echo "test-release-notes: ok"
