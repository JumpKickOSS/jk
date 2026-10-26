#!/usr/bin/env bash
# Create the GitHub Release of one version, or bring the one that exists up to date.
#
# Usage:
#   scripts/publish-github-release.sh <version> <notes.md> <asset>...
# The tag v<version> must already be pushed. A release for it is created with every asset and the
# notes as its body; a release that exists (a re-run) has every asset uploaded over any
# same-named one, as the GCS rsync replaced the tree, and its notes replaced. A version with a
# qualifier (1.2.3-rc.1) is a pre-release; any other release is marked latest. Everything goes
# through the gh CLI, which reads GH_TOKEN and GITHUB_REPOSITORY.
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
# Expanded as ${arr[@]+"${arr[@]}"}: macOS bash 3.2 reads an empty array under `set -u` as unbound.
REPO_ARGS=()
[[ -n "${GITHUB_REPOSITORY:-}" ]] && REPO_ARGS=(--repo "$GITHUB_REPOSITORY")

if ! prerelease="$(gh release view "$TAG" ${REPO_ARGS[@]+"${REPO_ARGS[@]}"} --json isPrerelease --jq '.isPrerelease' 2>/dev/null)"; then
  mark=(--prerelease)
  [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] && mark=(--latest)
  gh release create "$TAG" ${REPO_ARGS[@]+"${REPO_ARGS[@]}"} --verify-tag --title "jk $VERSION" --notes-file "$NOTES" "${mark[@]}" "$@"
  echo "publish-github-release: $TAG created with $# asset(s)"
  exit 0
fi

gh release upload "$TAG" ${REPO_ARGS[@]+"${REPO_ARGS[@]}"} --clobber "$@"
latest=(--latest)
[[ "$prerelease" == "true" ]] && latest=()
gh release edit "$TAG" ${REPO_ARGS[@]+"${REPO_ARGS[@]}"} --title "jk $VERSION" --notes-file "$NOTES" ${latest[@]+"${latest[@]}"}
echo "publish-github-release: $TAG holds $# asset(s)"
