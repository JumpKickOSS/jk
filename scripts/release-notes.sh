#!/usr/bin/env bash
# The release notes for one version: the hand-written highlights, then every commit since the
# previous release.
#
# Usage:
#   scripts/release-notes.sh <version> [<previous-tag>]
# Prints Markdown on stdout. The highlights are the `### <version>` entry under `## Highlights`
# in docs/contributors/releases.md — the one place a release's story is written, before the tag —
# and a version with no entry is refused: a release whose notes are only a commit list has
# nothing to say to someone deciding whether to update. The commit list is `git log` from the
# previous `v*` tag (the newest one older than <version>, or the one given) to `v<version>`, or
# to HEAD when the tag is not cut yet; merge commits are left out. A compare link closes the
# notes when the repository slug is known (GITHUB_REPOSITORY, else the origin remote).
#
# Env:
#   RELEASE_NOTES_DOC   the highlights document (default: docs/contributors/releases.md)
#   RELEASE_NOTES_REPO  the git checkout to read (default: this one)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:?usage: release-notes.sh <version> [<previous-tag>]}"
PREVIOUS="${2:-}"
DOC="${RELEASE_NOTES_DOC:-$ROOT/docs/contributors/releases.md}"
REPO="${RELEASE_NOTES_REPO:-$ROOT}"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]]; then
  echo "release-notes: '$VERSION' is not a version (X.Y.Z with optional dotted or dashed qualifiers)" >&2
  exit 1
fi
[[ -f "$DOC" ]] || { echo "release-notes: $DOC not found" >&2; exit 1; }

# The `### <version>` block under `## Highlights`: from its heading to the next heading of the
# same or a higher level, blank edges trimmed.
highlights="$(awk -v ver="$VERSION" '
  /^## / { in_section = ($0 == "## Highlights"); if (in_entry) exit }
  in_section && /^### / { if (in_entry) exit; in_entry = ($2 == ver); next }
  in_entry { print }
' "$DOC" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}' | sed '/./,$!d')"
if [[ -z "$highlights" ]]; then
  echo "release-notes: $DOC has no '### $VERSION' entry under '## Highlights' — write the release's highlights before tagging it" >&2
  exit 1
fi

tag="v$VERSION"
if git -C "$REPO" rev-parse -q --verify "refs/tags/$tag" >/dev/null; then
  end="$tag"
else
  end="HEAD"
fi

if [[ -z "$PREVIOUS" ]]; then
  # The newest v* tag older than this version, by version order rather than by date.
  PREVIOUS="$( { git -C "$REPO" tag --list 'v[0-9]*'; echo "$tag"; } | sort -u -V | grep -B1 -x -- "$tag" | head -1)"
  [[ "$PREVIOUS" == "$tag" ]] && PREVIOUS=""
fi
if [[ -n "$PREVIOUS" ]] && ! git -C "$REPO" rev-parse -q --verify "refs/tags/$PREVIOUS" >/dev/null; then
  echo "release-notes: previous tag '$PREVIOUS' does not exist" >&2
  exit 1
fi

slug="${GITHUB_REPOSITORY:-}"
if [[ -z "$slug" ]]; then
  origin="$(git -C "$REPO" remote get-url origin 2>/dev/null || true)"
  if [[ "$origin" =~ github\.com[:/]([^/]+/[^/]+?)(\.git)?$ ]]; then slug="${BASH_REMATCH[1]}"; fi
fi

echo "## Highlights"
echo
echo "$highlights"
echo
if [[ -n "$PREVIOUS" ]]; then
  echo "## Changes since $PREVIOUS"
  range="$PREVIOUS..$end"
else
  echo "## Changes"
  range="$end"
fi
echo
git -C "$REPO" log --no-merges --format='- %s (%h)' "$range"
if [[ -n "$slug" && -n "$PREVIOUS" ]]; then
  echo
  echo "Full diff: https://github.com/$slug/compare/$PREVIOUS...$tag"
fi
