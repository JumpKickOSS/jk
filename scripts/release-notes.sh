#!/usr/bin/env bash
# The release notes for one version: the hand-written highlights, then every commit since the
# previous release.
#
# Usage:
#   scripts/release-notes.sh <version> [<previous-tag>]
#   scripts/release-notes.sh --whats-new <version>
# Prints Markdown on stdout. The highlights are the `## <version>` entry in docs/user/whats-new.md,
# the one place a release's story is written, before the tag. A version with no entry is refused:
# a release whose notes are only a commit list has nothing to say to someone deciding whether to
# update. So is an entry that breaks the writing rules (docs/contributors/releases.md#writing-an-entry):
# one to five bullets, each opening with a bold hook, 40 to 320 characters, no ticket ids.
#
# The default form is the GitHub Release body: the highlights, then the commit list from the
# previous `v*` tag (the newest one older than <version>, or the one given) to `v<version>`, or to
# HEAD when the tag is not cut yet, folded under a <details>; merge commits are left out. A compare
# link closes the notes when the repository slug is known (GITHUB_REPOSITORY, else the origin
# remote).
#
# --whats-new prints the file the release tree carries as jk-whats-new-<version>.md: this version's
# entry and the entries of the versions before it, newest first, up to five. A self-update downloads
# it beside the engine jar and shows the entries newer than the version it replaced.
#
# Env:
#   RELEASE_NOTES_DOC   the highlights document (default: docs/user/whats-new.md)
#   RELEASE_NOTES_REPO  the git checkout to read (default: this one)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
MODE="github"
if [[ "${1:-}" == "--whats-new" ]]; then
  MODE="whats-new"
  shift
fi
VERSION="${1:?usage: release-notes.sh [--whats-new] <version> [<previous-tag>]}"
PREVIOUS="${2:-}"
DOC="${RELEASE_NOTES_DOC:-$ROOT/docs/user/whats-new.md}"
REPO="${RELEASE_NOTES_REPO:-$ROOT}"

# How many entries the bundled file carries: updates are compulsory, so nobody is far behind.
WHATS_NEW_ENTRIES=5

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]]; then
  echo "release-notes: '$VERSION' is not a version (X.Y.Z with optional dotted or dashed qualifiers)" >&2
  exit 1
fi
[[ -f "$DOC" ]] || { echo "release-notes: $DOC not found" >&2; exit 1; }

# The `## <version>` entry: from its heading to the next `## ` heading, blank edges trimmed.
entry() {
  awk -v ver="$1" '
    /^## / { if (in_entry) exit; in_entry = ($2 == ver); next }
    in_entry { print }
  ' "$DOC" | sed -e :a -e '/^[[:space:]]*$/{$d;N;ba' -e '}' | sed '/./,$!d'
}

highlights="$(entry "$VERSION")"
if [[ -z "$highlights" ]]; then
  echo "release-notes: $DOC has no '## $VERSION' entry — write the release's highlights before tagging it" >&2
  exit 1
fi

# The writing rules, checked on this version's entry only: older entries are history.
lint() {
  awk -v ver="$VERSION" '
    function check(   len, text) {
      if (bullet == "") return
      text = bullet
      len = length(text)
      if (text !~ /^- \*\*[^*]+\*\*/) bad("does not open with a bold hook (- **Hook.** …)")
      if (len < 40) bad("is " len " characters; say what the user gets (40 at least)")
      if (len > 320) bad("is " len " characters; keep it to the hook and a sentence or two (320 at most)")
      if (text ~ /JK-[0-9A-Z][0-9A-Z][0-9A-Z][0-9A-Z]/ || text ~ /JK-[0-9]+/) bad("names a ticket; users cannot look tickets up")
      bullet = ""
    }
    function bad(why) { printf "release-notes: %s bullet %d %s:\n  %s\n", ver, n, why, substr(bullet, 1, 120) > "/dev/stderr"; errors++ }
    /^- / { check(); n++; bullet = $0; next }
    /^[[:space:]]+[^[:space:]]/ && bullet != "" { sub(/^[[:space:]]+/, " "); bullet = bullet $0; next }
    /^[[:space:]]*$/ { check(); next }
    { check(); printf "release-notes: %s has a line that is not a bullet: %s\n", ver, substr($0, 1, 120) > "/dev/stderr"; errors++ }
    END {
      check()
      if (n < 1 || n > 5) { printf "release-notes: %s has %d bullets; pick one to five\n", ver, n > "/dev/stderr"; errors++ }
      exit errors > 0
    }
  ' <<<"$highlights"
}
if ! lint; then
  echo "release-notes: the $VERSION entry in $DOC breaks the writing rules (docs/contributors/releases.md#writing-an-entry)" >&2
  exit 1
fi

if [[ "$MODE" == "whats-new" ]]; then
  # Every `## <version>` heading at or below this version, by version order, newest first.
  # A read loop, not mapfile: macOS runs this under bash 3.2.
  versions=()
  while IFS= read -r v; do versions+=("$v"); done < <(awk '/^## [0-9]/ { print $2 }' "$DOC" \
    | { cat; echo "$VERSION"; } | sort -u -V -r | awk -v ver="$VERSION" '$0 == ver { on = 1 } on' \
    | head -n "$WHATS_NEW_ENTRIES")
  echo "# What's new in JumpKick"
  for v in "${versions[@]}"; do
    echo
    echo "## $v"
    echo
    entry "$v"
  done
  exit 0
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
  if [[ "$origin" =~ github\.com[:/]([^/]+/[^/]+)$ ]]; then slug="${BASH_REMATCH[1]%.git}"; fi
fi

if [[ -n "$PREVIOUS" ]]; then
  range="$PREVIOUS..$end"
  title="Every change since $PREVIOUS"
else
  range="$end"
  title="Every change"
fi
count="$(git -C "$REPO" rev-list --no-merges --count "$range")"

echo "## Highlights"
echo
echo "$highlights"
echo
echo "<details>"
echo "<summary>$title ($count commits)</summary>"
echo
git -C "$REPO" log --no-merges --format='- %s (%h)' "$range"
echo
echo "</details>"
if [[ -n "$slug" && -n "$PREVIOUS" ]]; then
  echo
  echo "Full diff: https://github.com/$slug/compare/$PREVIOUS...$tag"
fi
