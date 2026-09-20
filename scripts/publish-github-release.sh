#!/usr/bin/env bash
# Attach one version's artifacts to the GitHub Release that exists for its tag.
#
# Usage:
#   scripts/publish-github-release.sh <version> <notes.md> <asset>...
# The release for tag v<version> must already exist — publishing it is what starts the release
# workflow, and this script never creates one. Every asset is uploaded (replacing a same-named
# asset from an earlier run, as the GCS rsync replaced the tree), the notes become the body, and
# a release that is not a pre-release is marked latest. Everything goes through the gh CLI,
# which reads GH_TOKEN and GITHUB_REPOSITORY.
set -euo pipefail

USAGE="usage: publish-github-release.sh <version> <notes.md> <asset>..."
VERSION="${1:?$USAGE}"
NOTES="${2:?$USAGE}"
shift 2
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]]; then
  echo "publish-github-release: '$VERSION' is not a version (X.Y.Z with optional dotted or dashed qualifiers)" >&2
  exit 1
fi
[[ $# -gt 0 ]] || { echo "publish-github-release: at least one asset is required" >&2; exit 1; }
[[ -s "$NOTES" ]] || { echo "publish-github-release: notes file $NOTES is missing or empty" >&2; exit 1; }
for asset in "$@"; do
  [[ -f "$asset" ]] || { echo "publish-github-release: asset $asset is not a file" >&2; exit 1; }
done

TAG="v$VERSION"
REPO_ARGS=()
[[ -n "${GITHUB_REPOSITORY:-}" ]] && REPO_ARGS=(--repo "$GITHUB_REPOSITORY")

if ! prerelease="$(gh release view "$TAG" "${REPO_ARGS[@]}" --json isPrerelease --jq '.isPrerelease' 2>/dev/null)"; then
  echo "publish-github-release: no GitHub Release exists for $TAG — publish one for the tag; that is what runs the release" >&2
  exit 1
fi

gh release upload "$TAG" "${REPO_ARGS[@]}" --clobber "$@"
latest=(--latest)
[[ "$prerelease" == "true" ]] && latest=()
gh release edit "$TAG" "${REPO_ARGS[@]}" --title "jk $VERSION" --notes-file "$NOTES" "${latest[@]}"
echo "publish-github-release: $TAG holds $# asset(s)"
