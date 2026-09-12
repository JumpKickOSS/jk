#!/usr/bin/env bash
# Publish first-party plugin jars to the official JumpKick Maven layout on GCS.
#
# Layout (Maven standard under the repo root):
# gs://jumpkick/repo/cc/jumpkick/<artifact>/<version>/<artifact>-<version>.jar
# gs://jumpkick/repo/cc/jumpkick/<artifact>/<version>/<artifact>-<version>.jar.sha256
# gs://jumpkick/repo/cc/jumpkick/<artifact>/<version>/<artifact>-<version>.pom
#
# Public HTTPS:
# https://storage.googleapis.com/jumpkick/repo/...
# https://jumpkick.build/repo/... (Firebase redirect once DNS is live)
#
# Usage:
# # After ./gradlew installLocal (or jk install):
# scripts/publish-maven-repo.sh
# # Stage only (release.yml does this on the build runner, uploads on the publish runner):
# JK_MAVEN_STAGE_ONLY=1 JK_MAVEN_STAGE_DIR=out/repo scripts/publish-maven-repo.sh
#
# Env:
# JK_VERSION default: from JkVersion.java
# JK_STORE_DIR / JK_HOME — artifact store (see JkDirs)
# JK_MAVEN_BUCKET default: jumpkick
# JK_MAVEN_PREFIX default: repo
# JK_MAVEN_STAGE_ONLY=1 — write the layout to JK_MAVEN_STAGE_DIR and stop before gsutil
# JK_MAVEN_STAGE_DIR default: a temp dir (removed on exit unless stage-only)
# CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE SA key for CI
#
# The upload is additive: older versions already in the bucket stay, so a runner that holds one
# version in its store publishes that version without deleting the rest.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${JK_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(grep -E 'VERSION = "' "$ROOT/shared/jk-api/src/main/java/cc/jumpkick/model/JkVersion.java" | head -1 | sed -E 's/.*"([^"]+)".*/\1/')"
fi

STORE="${JK_STORE_DIR:-${JK_HOME:-$HOME/.jk}/store}"
BUCKET="${JK_MAVEN_BUCKET:-jumpkick}"
PREFIX="${JK_MAVEN_PREFIX:-repo}"
LOCAL="$STORE/repos/jk-local/cc/jumpkick"

if [[ ! -d "$LOCAL" ]]; then
  echo "publish-maven-repo: missing $LOCAL — run ./gradlew installLocal or jk install first" >&2
  exit 2
fi

STAGE_ONLY="${JK_MAVEN_STAGE_ONLY:-}"
if [[ -n "${JK_MAVEN_STAGE_DIR:-}" ]]; then
  STAGE="$(mkdir -p "$JK_MAVEN_STAGE_DIR" && cd "$JK_MAVEN_STAGE_DIR" && pwd)"
else
  STAGE="$(mktemp -d "${TMPDIR:-/tmp}/jk-maven-repo.XXXXXX")"
  [[ -n "$STAGE_ONLY" ]] || trap 'rm -rf "$STAGE"' EXIT
fi

sha256_of() {
  if command -v shasum >/dev/null; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    sha256sum "$1" | awk '{print $1}'
  fi
}

# Stage every installed first-party artifact (all versions present): jar + pom + checksums.
# The path under cc/jumpkick is kept as it is, so a nested group (cc.jumpkick.guards → guards/<pack>)
# lands where Maven resolution looks for it, not flattened to cc/jumpkick/<pack>.
count=0
while IFS= read -r -d '' jar; do
  # .../cc/jumpkick/<group path...>/<artifact>/<ver>/<artifact>-<ver>.jar
  ver_dir="$(dirname "$jar")"
  ver="$(basename "$ver_dir")"
  art_dir="$(dirname "$ver_dir")"
  art="$(basename "$art_dir")"
  rel="${ver_dir#"$LOCAL"/}"
  pom="$ver_dir/$art-$ver.pom"
  if [[ ! -f "$pom" ]]; then
    echo "publish-maven-repo: missing POM for $art:$ver ($pom) — install writes jar+pom; will not invent one" >&2
    exit 2
  fi
  dest="$STAGE/cc/jumpkick/$rel"
  mkdir -p "$dest"
  cp -f "$jar" "$dest/"
  cp -f "$pom" "$dest/"
  if [[ -f "${jar}.sha256" ]]; then
    cp -f "${jar}.sha256" "$dest/"
  else
    sha256_of "$jar" >"$dest/$(basename "$jar").sha256"
  fi
  if [[ -f "${pom}.sha256" ]]; then
    cp -f "${pom}.sha256" "$dest/"
  else
    sha256_of "$pom" >"$dest/$(basename "$pom").sha256"
  fi
  count=$((count + 1))
  echo "staged $art:$ver"
done < <(find "$LOCAL" -type f -name "*.jar" -print0)

if [[ "$count" -eq 0 ]]; then
  echo "publish-maven-repo: no jars under $LOCAL" >&2
  exit 2
fi

# maven-metadata.xml per artifact (versions list): an artifact directory is one whose children are
# version directories holding a jar, at any depth under cc/jumpkick (the guard packs are one deeper).
while IFS= read -r -d '' pom; do
  echo "$(dirname "$(dirname "$pom")")"
done < <(find "$STAGE/cc/jumpkick" -type f -name "*.pom" -print0) | sort -u | while IFS= read -r meta_dir; do
  art="$(basename "$meta_dir")"
  group_rel="$(dirname "${meta_dir#"$STAGE"/}")"          # cc/jumpkick or cc/jumpkick/guards
  group_id="${group_rel//\//.}"
  versions="$(find "$meta_dir" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; | sort -V | while read -r v; do echo "    <version>$v</version>"; done)"
  cat >"$meta_dir/maven-metadata.xml" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>$group_id</groupId>
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
done

if [[ -n "$STAGE_ONLY" ]]; then
  echo "=== staged $count artifacts under $STAGE (JK_MAVEN_STAGE_ONLY set; not uploading) ==="
  exit 0
fi

echo "=== upload gs://${BUCKET}/${PREFIX}/ ($count artifacts, additive) ==="
gsutil -m -o "GSUtil:parallel_process_count=1" rsync -r "$STAGE/" "gs://${BUCKET}/${PREFIX}/"
echo "Public base: https://storage.googleapis.com/${BUCKET}/${PREFIX}/"
echo "Canonical:   https://jumpkick.build/repo/"
echo "Example:     https://storage.googleapis.com/${BUCKET}/${PREFIX}/cc/jumpkick/jk-test-runner/${VERSION}/jk-test-runner-${VERSION}.jar"
