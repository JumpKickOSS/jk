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
# # After jk install:
# scripts/publish-maven-repo.sh
# # Stage only (release.yml does this on the build runner, uploads on the publish runner):
# JK_MAVEN_STAGE_ONLY=1 JK_MAVEN_STAGE_DIR=target/release/repo scripts/publish-maven-repo.sh
#
# Env:
# JK_VERSION default: from JkVersion.java
# JK_STORE_DIR / JK_HOME — artifact store (see JkDirs)
# JK_MAVEN_BUCKET default: jumpkick
# JK_MAVEN_PREFIX default: repo
# JK_MAVEN_STAGE_ONLY=1 — write the layout to JK_MAVEN_STAGE_DIR and stop before gsutil
# JK_MAVEN_STAGE_DIR default: a temp dir (removed on exit unless stage-only)
# CLOUDSDK_AUTH_CREDENTIAL_FILE_OVERRIDE SA key for CI
# JK_PUBLISH_CENTRAL=1 — after the upload, publish the plugin SDK (jk-host, then jk-plugin-sdk)
#   to Maven Central with `jk publish --central`; needs JK_CENTRAL_GPG_KEY_FILE (the release GPG
#   secret key; JK_GPG_PASSPHRASE for its passphrase) and the `central` credential from
#   `jk repo login central`. JK_CENTRAL_PUBLISHING_TYPE=automatic releases without the Portal
#   click; JK_CENTRAL_DRY_RUN=1 writes the bundles and uploads nothing. Never under stage-only.
#
# The upload is additive: older versions already in the bucket stay, so a runner that holds one
# version in its store publishes that version without deleting the rest. Each maven-metadata.xml
# is additive too: the bucket's current version list is fetched and merged with the staged one, so
# a fresh checkout that holds one version does not shorten the list consumers use for ranges, and
# <latest>/<release> name the merged maximum. An artifact the store does not hold at JK_VERSION
# is staged (its jars are additive) but its metadata is left as the bucket has it.
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
  echo "publish-maven-repo: missing $LOCAL — run jk install first" >&2
  exit 2
fi

STAGE_ONLY="${JK_MAVEN_STAGE_ONLY:-}"
# Fetched metadata lives in its own temp dir, gone on exit; a stage the caller named or asked to keep stays.
FETCHED="$(mktemp -d "${TMPDIR:-/tmp}/jk-maven-meta.XXXXXX")"
SCRATCH_STAGE=""
if [[ -n "${JK_MAVEN_STAGE_DIR:-}" ]]; then
  STAGE="$(mkdir -p "$JK_MAVEN_STAGE_DIR" && cd "$JK_MAVEN_STAGE_DIR" && pwd)"
else
  STAGE="$(mktemp -d "${TMPDIR:-/tmp}/jk-maven-repo.XXXXXX")"
  [[ -n "$STAGE_ONLY" ]] || SCRATCH_STAGE="$STAGE"
fi
cleanup() {
  rm -rf "$FETCHED"
  [[ -z "$SCRATCH_STAGE" ]] || rm -rf "$SCRATCH_STAGE"
}
trap cleanup EXIT

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

# The `<dependencies>` of a POM as `groupId|artifactId|version|scope` rows — the declared
# dependencies only, never the `<dependencyManagement>` pins.
declared_dependencies() {
  awk '
    /<dependencyManagement>/ { managed = 1 }
    /<\/dependencyManagement>/ { managed = 0 }
    /<dependency>/ { g = ""; a = ""; v = ""; sc = "" }
    /<groupId>/ { if (match($0, /<groupId>[^<]*<\/groupId>/)) g = substr($0, RSTART + 9, RLENGTH - 19) }
    /<artifactId>/ { if (match($0, /<artifactId>[^<]*<\/artifactId>/)) a = substr($0, RSTART + 12, RLENGTH - 25) }
    /<version>/ { if (match($0, /<version>[^<]*<\/version>/)) v = substr($0, RSTART + 9, RLENGTH - 19) }
    /<scope>/ { if (match($0, /<scope>[^<]*<\/scope>/)) sc = substr($0, RSTART + 7, RLENGTH - 15) }
    /<\/dependency>/ { if (!managed && a != "") print g "|" a "|" v "|" sc }
  ' "$1"
}

# A plugin worker publishes the dependencies it declares, and every first-party one it names has
# to be in this stage at that version: a launch rebuilds the worker classpath from this POM and
# fetches each coordinate from the repository, so a worker whose POM declares nothing, or names a
# `cc.jumpkick` artifact the repository does not serve, cannot start once installed. A worker is
# a `cc.jumpkick:jk-<name>` artifact whose source module is plugins/<name> in this checkout — the
# group matters: `cc.jumpkick.guards:android` is a rule pack that shares a name with the
# jk-android worker's module and declares nothing, rightly.
while IFS= read -r -d '' pom; do
  ver_dir="$(dirname "$pom")"
  art_dir="$(dirname "$ver_dir")"
  art="$(basename "$art_dir")"
  ver="$(basename "$ver_dir")"
  [[ "$art_dir" == "$STAGE/cc/jumpkick/$art" && "$art" == jk-* && -f "$ROOT/plugins/${art#jk-}/jk.toml" ]] || continue
  # A stub is a POM that declares nothing at all. A self-contained worker (its runtime closure
  # packed into the jar) rightly declares only test- or provided-scope dependencies: the build's
  # POM, with nothing for a launch to fetch.
  if [[ -z "$(declared_dependencies "$pom")" ]]; then
    echo "publish-maven-repo: $art:$ver declares no dependencies in $pom — a worker POM names what the worker runs on; publish the POM the build wrote, not a stub" >&2
    exit 2
  fi
  declared="$(declared_dependencies "$pom" | grep -v '|test$' | grep -v '|provided$' || true)"
  if [[ -z "$declared" ]]; then
    echo "checked $art:$ver (self-contained: nothing to fetch at launch)"
    continue
  fi
  while IFS='|' read -r g a v _; do
    [[ "$g" == cc.jumpkick* ]] || continue
    dep_jar="$STAGE/${g//.//}/$a/$v/$a-$v.jar"
    if [[ ! -f "$dep_jar" ]]; then
      echo "publish-maven-repo: $art:$ver depends on $g:$a:$v, which this stage does not hold ($dep_jar) — the published worker could not start; install the closure (jk install) before publishing" >&2
      exit 2
    fi
  done <<<"$declared"
  echo "checked $art:$ver (declares $(wc -l <<<"$declared" | tr -d ' ') dependencies)"
done < <(find "$STAGE/cc/jumpkick" -type f -name "*.pom" -print0)

# The repository's current maven-metadata.xml for an artifact directory (relative to the repo
# root), written to $2; "absent" on stdout when the repository has none (HTTP 404). Any other
# failure is fatal: a version list rebuilt without the repository's answer is the drop this merge
# prevents. The read goes over the public origin, so it needs no credentials and behaves the same
# on the staging runner and the publishing one; only the upload needs gsutil.
fetch_metadata() {
  local rel="$1" out="$2" url code
  url="https://storage.googleapis.com/${BUCKET}/${PREFIX}/$rel/maven-metadata.xml"
  code="$(curl -sS -o "$out" -w '%{http_code}' "$url")" || {
    echo "publish-maven-repo: cannot read $url (curl exit $?)" >&2
    exit 2
  }
  case "$code" in
    200) ;;
    404) echo absent ;;
    *) echo "publish-maven-repo: cannot read $url: HTTP $code" >&2; exit 2 ;;
  esac
}

# maven-metadata.xml per artifact published at $VERSION: an artifact directory is one whose
# children are version directories holding a jar, at any depth under cc/jumpkick (the guard packs
# are one deeper). Its version list is the union of what the repository already lists and what is
# staged; <latest> is the maximum, <release> the maximum that is not a snapshot.
while IFS= read -r -d '' pom; do
  echo "$(dirname "$(dirname "$pom")")"
done < <(find "$STAGE/cc/jumpkick" -type f -name "*.pom" -print0) | sort -u | while IFS= read -r meta_dir; do
  art="$(basename "$meta_dir")"
  art_rel="${meta_dir#"$STAGE"/}"                          # cc/jumpkick/<art> or cc/jumpkick/guards/<art>
  group_rel="$(dirname "$art_rel")"                        # cc/jumpkick or cc/jumpkick/guards
  group_id="${group_rel//\//.}"
  if [[ ! -d "$meta_dir/$VERSION" ]]; then
    echo "metadata $group_id:$art unchanged (not staged at $VERSION)"
    continue
  fi
  existing="$FETCHED/${art_rel//\//_}.xml"
  fetched="$(fetch_metadata "$art_rel" "$existing")"
  known=""
  if [[ "$fetched" != absent ]]; then
    known="$(grep -oE '<version>[^<]+</version>' "$existing" | sed -E 's|</?version>||g' || true)"
  fi
  versions="$( { printf '%s\n' "$known"; find "$meta_dir" -mindepth 1 -maxdepth 1 -type d -exec basename {} \; ; } \
    | grep -v '^$' | sort -u | sort -V)"                    # dotted numeric order: 0.13.10 after 0.13.3
  latest="$(tail -n 1 <<<"$versions")"
  release="$(grep -v -- '-SNAPSHOT$' <<<"$versions" | tail -n 1 || true)"
  release="${release:-$latest}"
  {
    echo '<?xml version="1.0" encoding="UTF-8"?>'
    echo '<metadata>'
    echo "  <groupId>$group_id</groupId>"
    echo "  <artifactId>$art</artifactId>"
    echo '  <versioning>'
    echo "    <latest>$latest</latest>"
    echo "    <release>$release</release>"
    echo '    <versions>'
    while IFS= read -r v; do echo "      <version>$v</version>"; done <<<"$versions"
    echo '    </versions>'
    echo "    <lastUpdated>$(date -u +%Y%m%d%H%M%S)</lastUpdated>"
    echo '  </versioning>'
    echo '</metadata>'
  } >"$meta_dir/maven-metadata.xml"
  echo "metadata $group_id:$art -> latest $latest ($(wc -l <<<"$versions" | tr -d ' ') versions, $( [[ "$fetched" == absent ]] && echo new || echo merged))"
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

# The plugin SDK to Maven Central, after repo/ holds the same bytes: jk-host first, because the
# SDK's POM depends on it and a consumer resolving the SDK from Central fetches both there. Each
# publish is a signed Portal bundle of the module's jar, POM, sources and javadoc jars polled to
# the Portal's verdict; a refusal names its fix (a missing [publish] table, a missing jar).
if [[ -n "${JK_PUBLISH_CENTRAL:-}" ]]; then
  if [[ -z "${JK_CENTRAL_GPG_KEY_FILE:-}" ]]; then
    echo "publish-maven-repo: JK_PUBLISH_CENTRAL is set but JK_CENTRAL_GPG_KEY_FILE names no GPG secret key — Central requires a signature on every file" >&2
    exit 2
  fi
  central_args=(--central --sign --key-file "$JK_CENTRAL_GPG_KEY_FILE" --publishing-type "${JK_CENTRAL_PUBLISHING_TYPE:-user-managed}")
  [[ -z "${JK_CENTRAL_DRY_RUN:-}" ]] || central_args+=(--dry-run)
  for module in shared/host shared/plugin-sdk; do
    echo "=== Maven Central: $module (${JK_CENTRAL_PUBLISHING_TYPE:-user-managed}${JK_CENTRAL_DRY_RUN:+, dry run}) ==="
    (cd "$ROOT/$module" && jk publish "${central_args[@]}")
  done
fi
