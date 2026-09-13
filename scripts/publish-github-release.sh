#!/usr/bin/env bash
# The GitHub Release for one version, beside the GCS tree the installers read.
#
# Usage:
#   scripts/publish-github-release.sh draft   <version> <notes.md> <asset>...
#   scripts/publish-github-release.sh publish <version>
# `draft` creates the release for tag v<version> as a draft — the tag must already exist
# (--verify-tag): this script never cuts one — with the notes as its body and every asset attached,
# or, when a draft for the tag already exists (a re-run), replaces its assets and notes. A release
# that is already published is refused: bytes users may have downloaded are never rewritten from
# CI. `publish` turns the draft into the release and marks it latest; it runs after the version
# tree and the pointer are in GCS, so the release never announces artifacts an installer cannot
# fetch yet. Everything goes through the gh CLI, which reads GH_TOKEN and GITHUB_REPOSITORY.
set -euo pipefail

MODE="${1:?usage: publish-github-release.sh draft <version> <notes.md> <asset>... | publish <version>}"
VERSION="${2:?usage: publish-github-release.sh draft <version> <notes.md> <asset>... | publish <version>}"
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]]; then
  echo "publish-github-release: '$VERSION' is not a version (X.Y.Z with optional dotted or dashed qualifiers)" >&2
  exit 1
fi
TAG="v$VERSION"
REPO_ARGS=()
[[ -n "${GITHUB_REPOSITORY:-}" ]] && REPO_ARGS=(--repo "$GITHUB_REPOSITORY")

# "absent", "draft" or "published" — what the forge holds for the tag right now.
state() {
  local is_draft
  if ! is_draft="$(gh release view "$TAG" "${REPO_ARGS[@]}" --json isDraft --jq '.isDraft' 2>/dev/null)"; then
    echo absent
  elif [[ "$is_draft" == "true" ]]; then
    echo draft
  else
    echo published
  fi
}

case "$MODE" in
  draft)
    NOTES="${3:?draft needs <notes.md>}"
    shift 3
    [[ $# -gt 0 ]] || { echo "publish-github-release: draft needs at least one asset" >&2; exit 1; }
    [[ -s "$NOTES" ]] || { echo "publish-github-release: notes file $NOTES is missing or empty" >&2; exit 1; }
    for asset in "$@"; do
      [[ -f "$asset" ]] || { echo "publish-github-release: asset $asset is not a file" >&2; exit 1; }
    done
    case "$(state)" in
      published)
        echo "publish-github-release: $TAG is already published — a released tree is never rewritten from CI" >&2
        exit 1
        ;;
      draft)
        echo "publish-github-release: $TAG is a draft already; replacing its assets and notes"
        gh release upload "$TAG" "${REPO_ARGS[@]}" --clobber "$@"
        gh release edit "$TAG" "${REPO_ARGS[@]}" --title "jk $VERSION" --notes-file "$NOTES"
        ;;
      absent)
        gh release create "$TAG" "${REPO_ARGS[@]}" --draft --verify-tag --title "jk $VERSION" --notes-file "$NOTES" "$@"
        ;;
    esac
    echo "publish-github-release: draft $TAG holds $# asset(s)"
    ;;
  publish)
    case "$(state)" in
      absent)
        echo "publish-github-release: no release for $TAG to publish — run draft first" >&2
        exit 1
        ;;
      published)
        echo "publish-github-release: $TAG is already published"
        ;;
      draft)
        gh release edit "$TAG" "${REPO_ARGS[@]}" --draft=false --latest
        echo "publish-github-release: $TAG published"
        ;;
    esac
    ;;
  *)
    echo "publish-github-release: mode must be draft or publish, not '$MODE'" >&2
    exit 1
    ;;
esac
