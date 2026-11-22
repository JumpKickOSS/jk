#!/usr/bin/env bash
# Refresh the copies a jk-updates checkout serves on behalf of other sources:
#   libraries.toml  the library registry (JumpKickOSS/jk-libraries)
#   jdks.json       the JetBrains JDK feed
#   pins.json       the jk-templates main revision, and the current stable JumpKick release with the
#                   SHA-256 of its SHA256SUMS, taken from the release's signed latest pointer and
#                   checked against the signature beside SHA256SUMS
# A copy is replaced only by one that parses (and, for the release, verifies with release-key.spki).
# A source that fails keeps the current copy; every source is tried, and the run exits 1 when any
# failed. Never commits.
#
# Usage: scripts/refresh.sh [dir]        # dir defaults to the checkout this script sits in
# Each source can be moved, for tests and mirrors: JK_UPDATES_LIBRARIES_URL, JK_UPDATES_JDKS_URL,
# JK_UPDATES_TEMPLATES_GIT, JK_UPDATES_RELEASES_URL (the directory holding latest/LATEST and the
# version directories), JK_UPDATES_RELEASE_SPKI (the trusted public key, base64 DER).
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
DIR="${1:-$HERE}"
LIBRARIES_URL="${JK_UPDATES_LIBRARIES_URL:-https://raw.githubusercontent.com/JumpKickOSS/jk-libraries/refs/heads/main/libraries.toml}"
JDKS_URL="${JK_UPDATES_JDKS_URL:-https://download.jetbrains.com/jdk/feed/v1/jdks.json}"
TEMPLATES_GIT="${JK_UPDATES_TEMPLATES_GIT:-https://github.com/JumpKickOSS/jk-templates}"
RELEASES_URL="${JK_UPDATES_RELEASES_URL:-https://jumpkick.build/releases}"
SPKI="${JK_UPDATES_RELEASE_SPKI:-$(tr -d '\r\n' <"$HERE/release-key.spki")}"

if [[ ! -d "$DIR" ]]; then
  echo "refresh: not a directory: $DIR" >&2
  exit 2
fi
for tool in curl git python3 openssl; do
  command -v "$tool" >/dev/null 2>&1 || { echo "refresh: $tool is required" >&2; exit 2; }
done

WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-updates-refresh.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
printf '%s' "$SPKI" | openssl base64 -d -A >"$WORK/release-key.der"
openssl pkey -pubin -inform DER -in "$WORK/release-key.der" -out "$WORK/release-key.pem" 2>/dev/null \
  || { echo "refresh: the trusted release key does not load" >&2; exit 2; }

failed=0
miss() {
  echo "refresh: $1; the current copy stays" >&2
  failed=1
}

fetch() { # url out
  curl -fsSL --retry 2 --max-time 120 -o "$2" "$1"
}

# Replace $DIR/$1 with $2 when they differ.
keep() {
  if cmp -s "$2" "$DIR/$1"; then
    echo "refresh: $1 unchanged"
  else
    cp "$2" "$DIR/$1"
    echo "refresh: $1 updated"
  fi
}

# The detached base64 signature at $2 verifies over the bytes of $1 with the release key.
verifies() {
  openssl base64 -d -A -in "$2" -out "$2.bin" 2>/dev/null \
    && openssl dgst -sha256 -verify "$WORK/release-key.pem" -signature "$2.bin" "$1" >/dev/null 2>&1
}

if fetch "$LIBRARIES_URL" "$WORK/libraries.toml" \
    && python3 -c 'import sys, tomllib; tomllib.load(open(sys.argv[1], "rb"))' "$WORK/libraries.toml" 2>/dev/null; then
  keep libraries.toml "$WORK/libraries.toml"
else
  miss "libraries.toml: $LIBRARIES_URL did not serve a TOML registry"
fi

if fetch "$JDKS_URL" "$WORK/jdks.json" \
    && python3 -c 'import json, sys; sys.exit(0 if isinstance(json.load(open(sys.argv[1])).get("jdks"), list) else 1)' \
      "$WORK/jdks.json" 2>/dev/null; then
  keep jdks.json "$WORK/jdks.json"
else
  miss "jdks.json: $JDKS_URL did not serve the JDK feed"
fi

templates=""
commit="$(git ls-remote "$TEMPLATES_GIT" refs/heads/main 2>/dev/null | cut -f1)" || true
if [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  templates="$commit"
else
  miss "templates: no main revision at $TEMPLATES_GIT"
fi

release="" sums_sha=""
if fetch "$RELEASES_URL/latest/LATEST" "$WORK/LATEST"; then
  head -n 2 "$WORK/LATEST" >"$WORK/LATEST.body"
  sed -n 's/^signature //p' "$WORK/LATEST" >"$WORK/LATEST.sig"
  version="$(sed -n 's/^version //p' "$WORK/LATEST.body")"
  if [[ "$(wc -l <"$WORK/LATEST")" -ne 3 || ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] \
      || ! verifies "$WORK/LATEST.body" "$WORK/LATEST.sig"; then
    miss "jk: $RELEASES_URL/latest/LATEST is not a release pointer signed by the release key"
  elif fetch "$RELEASES_URL/$version/SHA256SUMS" "$WORK/SHA256SUMS" \
      && fetch "$RELEASES_URL/$version/SHA256SUMS.sig" "$WORK/SHA256SUMS.sig" \
      && verifies "$WORK/SHA256SUMS" "$WORK/SHA256SUMS.sig"; then
    release="$version"
    sums_sha="$(openssl dgst -sha256 -r "$WORK/SHA256SUMS" | cut -d' ' -f1)"
  else
    miss "jk: $RELEASES_URL/$version/SHA256SUMS is missing or not signed by the release key"
  fi
else
  miss "jk: $RELEASES_URL/latest/LATEST did not answer"
fi

# pins.json keeps the pin of each source that failed.
python3 - "$DIR/pins.json" "$WORK/pins.json" "$templates" "$release" "$sums_sha" <<'PY'
import json, os, sys

held, out, templates, release, sums_sha = sys.argv[1:6]
pins = {}
if os.path.isfile(held):
    with open(held, encoding="utf-8") as f:
        pins = json.load(f)
if templates:
    pins["templates"] = templates
if release:
    pins.setdefault("jk", {})["stable"] = {"version": release, "sha256": sums_sha}
with open(out, "w", encoding="utf-8", newline="\n") as f:
    f.write(json.dumps(pins, indent=2, sort_keys=True) + "\n")
PY
keep pins.json "$WORK/pins.json"

exit "$failed"
