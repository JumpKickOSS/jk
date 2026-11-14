#!/usr/bin/env bash
# One platform's release build, by hand, the way release.yml's build job runs it: a clean clone of
# the release tag, a home of its own, the pinned bootstrap, the tree's takeover, a dist built by the
# tree's own jk, then the platform's release directory. Run it from a checkout of the tag on each
# machine (docs/contributors/releases.md, "Cutting a release by hand").
#
# Usage:
#   scripts/release-lane.sh <work-dir>
# The version is the checkout's jk.toml version, and HEAD must be its tag v<version>. The platform is
# the host's. Leaves:
#   <work-dir>/out/release-<version>-<os>-<arch>/   assemble-release-dir.sh's tree, unsigned
#   <work-dir>/out/repo/                             linux-x86_64 only: the repository staged for repo/
#   <work-dir>/lane.log                              everything the lane printed
# linux-x86_64 is the canonical lane: its jars, its jk-repo-<version>.sha256 and its staged repo/ are
# the ones published, so it stages the repository first and lists the stage in jk-repo
# (JK_REPO_STAGE_DIR), then checks every staged jar and POM against that file.
#
# Env: JK_CLIENT (default native), TMPDIR (the bridge checkout goes under it; keep it off a RAM disk).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
W="${1:?usage: release-lane.sh <work-dir>}"
VER="$(bash "$ROOT/scripts/release-version.sh")"
TAG="v$VER"

case "$(uname -s)" in
  Linux) os=linux ;;
  Darwin) os=macos ;;
  MINGW* | MSYS* | CYGWIN*) os=windows ;;
  *) echo "release-lane: unsupported OS $(uname -s)" >&2; exit 2 ;;
esac
case "$(uname -m)" in
  x86_64 | amd64) arch=x86_64 ;;
  aarch64 | arm64) arch=aarch64 ;;
  *) echo "release-lane: unsupported architecture $(uname -m)" >&2; exit 2 ;;
esac
LABEL="$os-$arch"

if [[ "$(git -C "$ROOT" rev-parse HEAD)" != "$(git -C "$ROOT" rev-parse -q --verify "$TAG^{commit}" 2>/dev/null || true)" ]]; then
  echo "release-lane: HEAD is not $TAG; check out the release tag (git fetch --tags && git checkout $TAG)" >&2
  exit 2
fi

# The Build-Commit of a jar's manifest: unzip where there is one, else Python, else PowerShell (Git Bash).
build_commit() {
  local jar="$1"
  {
    if command -v unzip >/dev/null 2>&1; then
      unzip -p "$jar" META-INF/MANIFEST.MF
    elif command -v python3 >/dev/null 2>&1; then
      python3 -c 'import sys, zipfile; sys.stdout.write(zipfile.ZipFile(sys.argv[1]).read("META-INF/MANIFEST.MF").decode())' "$jar"
    else
      powershell.exe -NoProfile -Command "Add-Type -A System.IO.Compression.FileSystem; \$z=[IO.Compression.ZipFile]::OpenRead('$(cygpath -w "$jar")'); (New-Object IO.StreamReader(\$z.GetEntry('META-INF/MANIFEST.MF').Open())).ReadToEnd(); \$z.Dispose()"
    fi
  } | tr -d '\r' | sed -n 's/^Build-Commit: //p'
}

rm -rf "$W"
mkdir -p "$W/out"
W="$(cd "$W" && pwd)"
log="$W/lane.log"
git -c advice.detachedHead=false clone -q --branch "$TAG" "$ROOT" "$W/src"
cd "$W/src"
export JK_HOME="$W/home" CI=1 NO_COLOR=1 JK_NONINTERACTIVE=1 JK_SELF_UPDATE=never JK_CLIENT="${JK_CLIENT:-native}"
export PATH="$JK_HOME/bin:$PATH"
out="$W/out/release-$VER-$LABEL"
{
  echo "== $LABEL $VER from $(git rev-parse HEAD)"
  echo "== bootstrap"; bash scripts/ci-bootstrap.sh
  echo "== takeover"; bash scripts/ci-takeover.sh --install-only --yes
  echo "== dist"; jk build --skip-tests --yes --no-ansi
  ls -la target/dist target/dist/lib
  stage_env=()
  if [[ "$LABEL" == linux-x86_64 ]]; then
    echo "== repo"; JK_MAVEN_STAGE_ONLY=1 JK_MAVEN_STAGE_DIR="$W/out/repo" bash scripts/publish-maven-repo.sh
    stage_env=(JK_REPO_STAGE_DIR="$W/out/repo")
  fi
  echo "== assemble"; env DIST_DIR=target/dist ${stage_env[@]+"${stage_env[@]}"} bash scripts/assemble-release-dir.sh "$out"
  # The bridge also reports the release's version, so `jk --version` cannot prove the tree's own jk
  # packaged the dist; the commit stamp in the engine jar can.
  commit="$(build_commit "$out/jk-engine-$VER.jar")"
  [[ "$commit" == "$(git rev-parse HEAD)" ]] \
    || { echo "the engine jar's Build-Commit is '$commit', not $(git rev-parse HEAD): the dist was not packaged by this tree's jk"; exit 1; }
  if [[ "$LABEL" == linux-x86_64 ]]; then
    echo "== repo vs jk-repo"
    sums="$out/jk-repo-$VER.sha256"
    unlisted="$(cd "$W/out/repo" && find . -type f \( -name "*-$VER.jar" -o -name "*-$VER.pom" \) | sed 's|^\./||' | LC_ALL=C sort \
      | LC_ALL=C comm -23 - <(awk '{print $2}' "$sums" | LC_ALL=C sort))"
    [[ -z "$unlisted" ]] || { echo "staged but not listed in $sums:"; echo "$unlisted"; exit 1; }
    (cd "$W/out/repo" && sha256sum --check --strict --quiet --ignore-missing "$sums")
  fi
  jk engine stop --now --no-ansi || true
  echo "== lane $LABEL done: $out"
} >"$log" 2>&1 || { tail -60 "$log" >&2; echo "release-lane: $LABEL failed; log: $log" >&2; exit 1; }
tail -2 "$log"
