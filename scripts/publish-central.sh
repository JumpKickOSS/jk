#!/usr/bin/env bash
# The plugin SDK (cc.jumpkick:jk-host, then cc.jumpkick:jk-plugin-sdk) to Maven Central through
# the Central Portal, at the version the release carries.
#
# Usage:
#   scripts/publish-central.sh bundle <out-dir>      # signed Portal bundles of the built modules
#   scripts/publish-central.sh upload <bundle-dir>   # each bundle to the Portal, polled to PUBLISHED
#   scripts/publish-central.sh await <version>       # the POMs and metadata answer on repo1
#
# bundle runs `jk publish --central --sign --dry-run` in shared/host and shared/plugin-sdk (the
# `jk build` outputs must exist) and copies each module's target/publish/central-bundle.zip to
# <out-dir>/<artifact>-<version>.zip. upload releases jk-host before jk-plugin-sdk, whose POM
# depends on it; a version the Portal already lists as published is skipped, so a re-run of the
# release job passes. await polls https://repo1.maven.org until both POMs answer 200 and
# jk-plugin-sdk's maven-metadata.xml lists the version: Central syncs to repo1 minutes to an hour
# after PUBLISHED.
#
# Env:
#   JK_VERSION default: the root jk.toml version
#   JK_CENTRAL_GPG_KEY_FILE    bundle: the release GPG secret key (armored)
#   JK_GPG_PASSPHRASE          bundle: its passphrase
#   JK_REPO_CENTRAL_USERNAME   upload: the Portal user token's name
#   JK_REPO_CENTRAL_PASSWORD   upload: the Portal user token's password
#   JK_CENTRAL_PORTAL          upload: default https://central.sonatype.com
#   JK_CENTRAL_VALIDATE_ONLY=1 upload: user-managed deployments, dropped once VALIDATED; nothing
#                              is published (a dry run against the real Portal)
#   JK_CENTRAL_SYNC_TIMEOUT    await: seconds, default 5400
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${JK_VERSION:-}"
if [[ -z "$VERSION" ]]; then
  VERSION="$(sed -nE 's/^version[[:space:]]*=[[:space:]]*"([^"]+)".*/\1/p' "$ROOT/jk.toml" | head -1)"
fi
PORTAL="${JK_CENTRAL_PORTAL:-https://central.sonatype.com}"
PORTAL="${PORTAL%/}"
REPO1="https://repo1.maven.org/maven2/cc/jumpkick"
# Dependency order: the SDK's POM names jk-host.
MODULES=(shared/host:jk-host shared/plugin-sdk:jk-plugin-sdk)

die() {
  echo "publish-central: $*" >&2
  exit 2
}

bearer() {
  [[ -n "${JK_REPO_CENTRAL_USERNAME:-}" && -n "${JK_REPO_CENTRAL_PASSWORD:-}" ]] ||
    die "JK_REPO_CENTRAL_USERNAME and JK_REPO_CENTRAL_PASSWORD name no Portal user token (central.sonatype.com → account → Generate User Token)"
  printf '%s:%s' "$JK_REPO_CENTRAL_USERNAME" "$JK_REPO_CENTRAL_PASSWORD" | base64 | tr -d '\n'
}

# One Portal call; the body on stdout, a non-2xx answer fatal with its body.
portal() {
  local method="$1" path="$2" body code
  shift 2
  body="$(mktemp)"
  code="$(curl -sS -o "$body" -w '%{http_code}' -X "$method" -H "Authorization: Bearer $BEARER" "$@" "$PORTAL$path")" ||
    die "cannot reach $PORTAL$path (curl exit $?)"
  if [[ "$code" != 2* ]]; then
    echo "publish-central: $method $path answered HTTP $code: $(cat "$body")" >&2
    rm -f "$body"
    exit 1
  fi
  cat "$body"
  rm -f "$body"
}

bundle() {
  local out="$1" spec module art zip
  [[ -n "${JK_CENTRAL_GPG_KEY_FILE:-}" && -f "$JK_CENTRAL_GPG_KEY_FILE" ]] ||
    die "JK_CENTRAL_GPG_KEY_FILE names no GPG secret key — Central requires a signature on every file"
  mkdir -p "$out"
  for spec in "${MODULES[@]}"; do
    module="${spec%%:*}"
    art="${spec#*:}"
    echo "=== bundle $art $VERSION ==="
    (cd "$ROOT/$module" && jk publish --central --sign --key-file "$JK_CENTRAL_GPG_KEY_FILE" --dry-run --no-ansi)
    zip="$ROOT/$module/target/publish/central-bundle.zip"
    [[ -f "$zip" ]] || die "jk publish wrote no $zip"
    cp -f "$zip" "$out/$art-$VERSION.zip"
  done
  ls -l "$out"
}

# Polls a deployment until its state is one of $2 (space-separated) or FAILED; prints the state.
await_state() {
  local id="$1" want="$2" deadline state status
  deadline=$((SECONDS + 1800))
  while :; do
    status="$(portal POST "/api/v1/publisher/status?id=$id")"
    state="$(sed -nE 's/.*"deploymentState" *: *"([A-Z_]+)".*/\1/p' <<<"$status")"
    if [[ "$state" == FAILED ]]; then
      echo "publish-central: deployment $id FAILED: $status" >&2
      exit 1
    fi
    [[ " $want " != *" $state "* ]] || { echo "$state"; return; }
    ((SECONDS < deadline)) || die "deployment $id still $state after 30 minutes"
    sleep 10
  done
}

upload() {
  local dir="$1" spec art zip published type id state
  BEARER="$(bearer)"
  type=AUTOMATIC
  [[ -z "${JK_CENTRAL_VALIDATE_ONLY:-}" ]] || type=USER_MANAGED
  for spec in "${MODULES[@]}"; do
    art="${spec#*:}"
    zip="$dir/$art-$VERSION.zip"
    [[ -f "$zip" ]] || die "no bundle $zip — run: scripts/publish-central.sh bundle $dir"
    published="$(portal GET "/api/v1/publisher/published?namespace=cc.jumpkick&name=$art&version=$VERSION")"
    if [[ "$published" == *'"published":true'* ]]; then
      echo "=== $art $VERSION is already on Central ==="
      continue
    fi
    echo "=== upload $art $VERSION ($type) ==="
    id="$(portal POST "/api/v1/publisher/upload?name=$art-$VERSION&publishingType=$type" -F "bundle=@$zip")"
    echo "deployment $id"
    if [[ "$type" == USER_MANAGED ]]; then
      state="$(await_state "$id" VALIDATED)"
      portal DELETE "/api/v1/publisher/deployment/$id" >/dev/null
      echo "$art $VERSION $state; deployment $id dropped (validate only)"
    else
      state="$(await_state "$id" PUBLISHED)"
      echo "$art $VERSION $state"
    fi
  done
}

await_repo1() {
  local deadline spec art pom code meta
  deadline=$((SECONDS + ${JK_CENTRAL_SYNC_TIMEOUT:-5400}))
  for spec in "${MODULES[@]}"; do
    art="${spec#*:}"
    pom="$REPO1/$art/$VERSION/$art-$VERSION.pom"
    until code="$(curl -sS -o /dev/null -w '%{http_code}' "$pom")" && [[ "$code" == 200 ]]; do
      ((SECONDS < deadline)) || die "$pom still answers ${code:-nothing} — Central has not synced $art $VERSION to repo1"
      sleep 30
    done
    echo "repo1: $pom"
  done
  meta="$REPO1/jk-plugin-sdk/maven-metadata.xml"
  until curl -sSf "$meta" 2>/dev/null | grep -q "<version>$VERSION</version>"; do
    ((SECONDS < deadline)) || die "$meta does not list $VERSION yet"
    sleep 30
  done
  echo "repo1: $meta lists $VERSION"
}

case "${1:-}" in
  bundle) bundle "${2:?usage: publish-central.sh bundle <out-dir>}" ;;
  upload) upload "${2:?usage: publish-central.sh upload <bundle-dir>}" ;;
  await)
    VERSION="${2:-$VERSION}"
    await_repo1
    ;;
  *) die "usage: publish-central.sh bundle <out-dir> | upload <bundle-dir> | await [version]" ;;
esac
