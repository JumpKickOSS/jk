#!/usr/bin/env bash
# Publish first-party plugin jars to the official JumpKick Maven layout on GCS.
#
# Layout (Maven standard under the repo root):
# gs://jumpkick/repo/cc/jumpkick/<artifact>/<version>/<artifact>-<version>.jar
# gs://jumpkick/repo/cc/jumpkick/<artifact>/<version>/<artifact>-<version>.jar.sha256
#
# Public HTTPS:
# https://storage.googleapis.com/jumpkick/repo/...
# https://jumpkick.build/repo/... (Firebase redirect once DNS is live)
#
# Usage:
# # After ./gradlew installLocal (or dist installLocal):
# scripts/publish-maven-repo.sh
#
# Env:
# JK_VERSION default: from JkVersion.java
# JK_CACHE_DIR default: ~/.cache/jk
# JK_MAVEN_BUCKET default: jumpkick
# JK_MAVEN_PREFIX default: repo
# CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE SA key for CI
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${JK_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E 'VERSION = "' "$ROOT/shared/jk-api/src/main/java/cc/jumpkick/model/JkVersion.java" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi
CACHE="${JK_CACHE_DIR:-${JK_HOME:-$HOME/.jk}/cache}"
BUCKET="${JK_MAVEN_BUCKET:-jumpkick}"
PREFIX="${JK_MAVEN_PREFIX:-repo}"
LOCAL="$CACHE/repos/local/cc/jumpkick"

if [[ ! -d "$LOCAL" ]]; then
  echo "publish-maven-repo: missing $LOCAL — run ./gradlew installLocal first" >&2
  exit 2
fi

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/jk-maven-repo.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

# Stage every installed first-party artifact for this version (jars + sha256 sidecars).
count=0
while IFS= read -r -d '' jar; do
 # .../cc/jumpkick/<artifact>/<ver>/<artifact>-<ver>.jar
  ver_dir="$(dirname "$jar")"
  ver="$(basename "$ver_dir")"
  art_dir="$(dirname "$ver_dir")"
  art="$(basename "$art_dir")"
  if [[ "$ver" != "$VERSION" ]]; then
    continue
  fi
  dest="$STAGE/cc/jumpkick/$art/$ver"
  mkdir -p "$dest"
  cp -f "$jar" "$dest/"
  # JK-1351: thin workers ship a flat coordinate closure so cold stores can provision them.
  if [[ -f "${jar}.deps" ]]; then
    cp -f "${jar}.deps" "$dest/"
  fi
  if [[ -f "${jar}.sha256" ]]; then
    cp -f "${jar}.sha256" "$dest/"
  else
    if command -v shasum >/dev/null; then
      shasum -a 256 "$jar" | awk '{print $1}' >"$dest/$(basename "$jar").sha256"
    else
      sha256sum "$jar" | awk '{print $1}' >"$dest/$(basename "$jar").sha256"
    fi
  fi
 # Minimal POM so Maven-compatible clients can resolve the module.
  cat >"$dest/$art-$ver.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>cc.jumpkick</groupId>
  <artifactId>$art</artifactId>
  <version>$ver</version>
  <packaging>jar</packaging>
  <name>$art</name>
  <description>JumpKick first-party plugin/worker ($art)</description>
</project>
EOF
  count=$((count + 1))
  echo "staged $art:$ver"
done < <(find "$LOCAL" -type f -name "*-${VERSION}.jar" -print0)

if [[ "$count" -eq 0 ]]; then
  echo "publish-maven-repo: no jars for version $VERSION under $LOCAL" >&2
  exit 2
fi

# maven-metadata.xml per artifact (versions list)
while IFS= read -r -d '' art_dir; do
  art="$(basename "$art_dir")"
  meta_dir="$STAGE/cc/jumpkick/$art"
  mkdir -p "$meta_dir"
  versions="$(find "$meta_dir" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort -V | while read -r v; do echo "    <version>$v</version>"; done)"
  cat >"$meta_dir/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>cc.jumpkick</groupId>
  <artifactId>$art</artifactId>
  <versioning>
    <latest>$VERSION</latest>
    <release>$VERSION</release>
    <versions>
$versions
    </versions>
    <lastUpdated>$(date -u +%Y%m%d%H%M%S)</lastUpdated>
  </versioning>
</metadata>
EOF
done < <(find "$STAGE/cc/jumpkick" -mindepth 1 -maxdepth 1 -type d -print0)

echo "=== upload gs://${BUCKET}/${PREFIX}/ ($count artifacts @ $VERSION) ==="
gsutil -m -o "GSUtil:parallel_process_count=1" rsync -r -d "$STAGE/" "gs://${BUCKET}/${PREFIX}/"
echo "Public base: https://storage.googleapis.com/${BUCKET}/${PREFIX}/"
echo "Canonical:   https://jumpkick.build/repo/"
echo "Example:     https://storage.googleapis.com/${BUCKET}/${PREFIX}/cc/jumpkick/jk-test-runner/${VERSION}/jk-test-runner-${VERSION}.jar"
