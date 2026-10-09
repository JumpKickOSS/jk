#!/usr/bin/env bash
# Build the linux-aarch64 client of a release on an Apple-silicon Mac and attach it to the release's
# GitHub draft. The release matrix has no arm64 Linux runner, so this is that row: an Apple
# `container machine` (an arm64 Linux guest that mounts the Mac's home) builds the tagged commit the
# way a matrix row does, and the publish job takes the two archives from the release as the
# linux-aarch64 tree. Publishing a release that lacks them is refused at the flatten.
#
# Usage: scripts/release-linux-aarch64.sh <version> [--no-upload]
#   --no-upload   build and check, leave the archives under target/release-linux-aarch64/<version>/
# Env:
#   JK_CONTAINER_MACHINE  the container machine to build in (default: the default machine); it needs
#                         gcc and zlib headers for the native link, and this checkout under its
#                         home mount
#
# The machine's own jk and checkouts are untouched: the build clones the tag and installs the
# pinned bootstrap into ~/jk-release/<version>/ inside it. The draft (`gh release create v<version>
# --draft`) must exist, and gh on this Mac must be able to write releases.
set -euo pipefail

USAGE="usage: release-linux-aarch64.sh <version> [--no-upload]"
VER="${1:?$USAGE}"
UPLOAD=1
case "${2:-}" in
  "") ;;
  --no-upload) UPLOAD=0 ;;
  *) echo "$USAGE" >&2; exit 64 ;;
esac
[[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]] || { echo "release-linux-aarch64: '$VER' is not a version" >&2; exit 64; }
TAG="v$VER"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/target/release-linux-aarch64/$VER"

command -v container >/dev/null 2>&1 || { echo "release-linux-aarch64: no 'container' CLI; this runs on an Apple-silicon Mac" >&2; exit 1; }
MACHINE=()
[[ -n "${JK_CONTAINER_MACHINE:-}" ]] && MACHINE=(-n "$JK_CONTAINER_MACHINE")

# The tag this checkout holds must be the one the release names, or the archive is of another commit.
git -C "$ROOT" fetch -q origin "refs/tags/$TAG:refs/tags/$TAG" 2>/dev/null || { echo "release-linux-aarch64: origin has no tag $TAG; push it first" >&2; exit 1; }
local_commit="$(git -C "$ROOT" rev-parse "$TAG^{commit}")"
remote_commit="$(git -C "$ROOT" ls-remote origin "refs/tags/$TAG^{}" | awk '{print $1}')"
if [[ "$local_commit" != "$remote_commit" ]]; then
  echo "release-linux-aarch64: $TAG is $local_commit here and ${remote_commit:-absent} on origin" >&2
  exit 1
fi
if [[ "$UPLOAD" == 1 ]]; then
  draft="$(gh release view "$TAG" --json isDraft --jq .isDraft 2>/dev/null)" || {
    echo "release-linux-aarch64: no GitHub Release for $TAG — create the draft first (gh release create $TAG --draft)" >&2
    exit 1
  }
  if [[ "$draft" != "true" ]]; then
    echo "release-linux-aarch64: $TAG is already published, and its workflow has run without this client" >&2
    exit 1
  fi
fi

rm -rf "$OUT"
mkdir -p "$OUT"
echo "release-linux-aarch64: building $TAG ($local_commit) in the container machine"
container machine run -i "${MACHINE[@]}" -- bash -s -- "$VER" "$ROOT" "$OUT" <<'EOF'
set -euo pipefail
VER="$1"; REPO="$2"; OUT="$3"
[[ "$(uname -m)" == aarch64 ]] || { echo "the container machine is $(uname -m), not aarch64" >&2; exit 1; }
[[ -d "$REPO/.git" ]] || { echo "$REPO is not visible in the container machine: its home mount must include this checkout" >&2; exit 1; }
W="$HOME/jk-release/$VER"
rm -rf "$W"
mkdir -p "$W"
git -c advice.detachedHead=false clone -q --depth 1 --branch "v$VER" "file://$REPO" "$W/src" 2>/dev/null
cd "$W/src"
export JK_HOME="$W/home" CI=1 NO_COLOR=1
export PATH="$JK_HOME/bin:$PATH"
log="$W/build.log"
# The JVM client bootstraps whatever the pin is; the dist's native client is linked either way.
# Chained with &&: `set -e` does not reach into a list that ends in ||.
packaged_by() { jk --version --no-ansi | head -1 | grep -q " $VER\$"; }
JK_CLIENT=jvm bash scripts/ci-bootstrap.sh >"$log" 2>&1 \
  && bash scripts/ci-takeover.sh --install-only --yes >>"$log" 2>&1 \
  && { packaged_by || { echo "the dist must be built by jk $VER, the tree's own" >>"$log"; false; }; } \
  && jk build --skip-tests --yes --no-ansi >>"$log" 2>&1 \
  && JK_VERSION="$VER" bash scripts/assemble-release-dir.sh >>"$log" 2>&1 \
  || { tail -40 "$log" >&2; echo "the build failed; the whole log is $log in the container machine" >&2; exit 1; }
built="$(target/dist/jk --version --no-ansi | head -1)"
[[ "$built" == "jk $VER" ]] || { echo "the built client says '$built', not 'jk $VER'" >&2; exit 1; }
cp "target/release/$VER/jk-linux-aarch64-$VER.gz" "target/release/$VER/jk-linux-aarch64-$VER.xz" "$OUT/"
jk engine stop --now >/dev/null 2>&1 || true
EOF

assets=("$OUT/jk-linux-aarch64-$VER.gz" "$OUT/jk-linux-aarch64-$VER.xz")
for a in "${assets[@]}"; do [[ -s "$a" ]] || { echo "release-linux-aarch64: $a was not produced" >&2; exit 1; }; done
(cd "$OUT" && shasum -a 256 -- *)
if [[ "$UPLOAD" == 0 ]]; then
  echo "release-linux-aarch64: built under $OUT (not uploaded)"
  exit 0
fi
gh release upload "$TAG" --clobber "${assets[@]}"
echo "release-linux-aarch64: attached to the $TAG draft; publish it to run the release"
