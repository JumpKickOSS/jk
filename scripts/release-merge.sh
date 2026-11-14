#!/usr/bin/env bash
# One release from the platform lanes built by hand on several machines (scripts/release-lane.sh):
# check what each lane copied over, flatten them into one version directory with one SHA256SUMS,
# and write the SBOM and the release notes beside it. Signs nothing; release-publish.sh does.
#
# Usage:
#   scripts/release-merge.sh <staging> <out>
# <staging> holds each lane's release-<version>-<os>-<arch>/ directory and linux-x86_64's repo/.
# Run from a checkout of the release tag (its jk.toml names the version). Writes:
#   <out>/<version>/            the merged, unsigned tree (scripts/flatten-release.sh)
#   <out>/repo/                 linux-x86_64's staged repository, for repo/
#   <out>/sbom/jk-<version>.cdx.json
#   <out>/RELEASE_NOTES.md
# JK_RELEASE_PLATFORMS defaults to the four platforms jumpkick.build serves.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGING="${1:?usage: release-merge.sh <staging> <out>}"
OUT="${2:?usage: release-merge.sh <staging> <out>}"
VER="$(bash "$ROOT/scripts/release-version.sh")"
export JK_RELEASE_PLATFORMS="${JK_RELEASE_PLATFORMS:-linux-x86_64 linux-aarch64 macos-aarch64 windows-x86_64}"

sums_check() { if command -v sha256sum >/dev/null 2>&1; then sha256sum --check --strict --quiet "$@"; else shasum -a 256 --check --strict --quiet "$@"; fi; }

[[ -d "$STAGING" ]] || { echo "release-merge: no staging directory $STAGING" >&2; exit 2; }
[[ ! -e "$OUT/$VER" ]] || { echo "release-merge: $OUT/$VER exists; merge into a fresh directory" >&2; exit 2; }
[[ -d "$STAGING/repo/cc" ]] || { echo "release-merge: no $STAGING/repo (linux-x86_64's staged repository)" >&2; exit 2; }

# Each lane's SHA256SUMS proves the copy arrived whole. Git Bash's shasum marks binary mode with
# ' *name'; normalize it to coreutils' two spaces before checking.
for tree in "$STAGING"/release-"$VER"-*/; do
  sed -i.bak 's/^\([0-9a-f]\{64\}\) \*/\1  /' "$tree/SHA256SUMS" && rm -f "$tree/SHA256SUMS.bak"
  (cd "$tree" && sums_check SHA256SUMS) \
    || { echo "release-merge: $tree does not match its SHA256SUMS: copy it again" >&2; exit 1; }
  echo "release-merge: $(basename "$tree") arrived whole"
done

# flatten-release.sh reads every file under its staging directory, so it gets the lane trees alone.
mkdir -p "$OUT"
lanes="$OUT/.lanes"
rm -rf "$lanes" && mkdir -p "$lanes"
cp -R "$STAGING"/release-"$VER"-* "$lanes/"
bash "$ROOT/scripts/flatten-release.sh" "$lanes" "$OUT/$VER" "$VER"
rm -rf "$lanes"
grep -q "  jk-repo-$VER.sha256\$" "$OUT/$VER/SHA256SUMS" \
  || { echo "release-merge: SHA256SUMS does not list jk-repo-$VER.sha256" >&2; exit 1; }

rm -rf "$OUT/repo"
cp -R "$STAGING/repo" "$OUT/repo"
unlisted="$(cd "$OUT/repo" && find . -type f \( -name "*-$VER.jar" -o -name "*-$VER.pom" \) | sed 's|^\./||' | LC_ALL=C sort \
  | LC_ALL=C comm -23 - <(awk '{print $2}' "$OUT/$VER/jk-repo-$VER.sha256" | LC_ALL=C sort))"
[[ -z "$unlisted" ]] || { echo "release-merge: staged for repo/ but not in jk-repo-$VER.sha256:"; echo "$unlisted"; exit 1; } >&2
(cd "$OUT/repo" && sums_check --ignore-missing "$OUT/$VER/jk-repo-$VER.sha256")

bash "$ROOT/scripts/release-sbom.sh" "$OUT/$VER/jk-engine-$VER.jar" "$OUT/sbom/jk-$VER.cdx.json"
bash "$ROOT/scripts/release-notes.sh" "$VER" >"$OUT/RELEASE_NOTES.md"
echo "release-merge: $OUT/$VER ($(wc -l <"$OUT/$VER/SHA256SUMS") files in SHA256SUMS), repo/, sbom/, RELEASE_NOTES.md"
