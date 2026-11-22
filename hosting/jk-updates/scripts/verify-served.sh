#!/usr/bin/env bash
# Check the manifest a URL serves as an engine would take it: updates.json and updates.json.sig
# verify with the release key, every file it lists is served beside it with the SHA-256 it gives,
# and its published date is the one expected, so a deploy that left an older manifest in place,
# or one a cache still holds, fails here.
#
# Usage: scripts/verify-served.sh <manifest-url> [published]
#   scripts/verify-served.sh https://jumpkick.build/updates.json "$(jq -r .published updates.json)"
# Without published it only verifies, and prints what it found. A manifest older than published is
# asked again, up to JK_UPDATES_VERIFY_TRIES times (default 10) JK_UPDATES_VERIFY_WAIT seconds apart
# (default 15), for a cache to let go of it. The trusted key is release-key.spki, or
# JK_UPDATES_RELEASE_SPKI (base64 DER).
set -euo pipefail

URL="${1:?usage: verify-served.sh <manifest-url> [published]}"
EXPECTED="${2:-}"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
SPKI="${JK_UPDATES_RELEASE_SPKI:-$(tr -d '\r\n' <"$HERE/release-key.spki")}"
TRIES="${JK_UPDATES_VERIFY_TRIES:-10}"
WAIT="${JK_UPDATES_VERIFY_WAIT:-15}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-updates-verify.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
printf '%s' "$SPKI" | openssl base64 -d -A >"$WORK/release-key.der"
openssl pkey -pubin -inform DER -in "$WORK/release-key.der" -out "$WORK/release-key.pem" 2>/dev/null \
  || { echo "verify-served: the trusted release key does not load" >&2; exit 2; }

fetch() { # url out
  curl -fsSL --max-time 60 -H 'Cache-Control: no-cache' -o "$2" "$1"
}

for ((try = 1; ; try++)); do
  fetch "$URL" "$WORK/updates.json" || { echo "verify-served: $URL did not answer" >&2; exit 1; }
  fetch "$URL.sig" "$WORK/updates.json.sig" || { echo "verify-served: $URL.sig did not answer" >&2; exit 1; }
  if ! openssl base64 -d -A -in "$WORK/updates.json.sig" -out "$WORK/signature.bin" 2>/dev/null \
      || ! openssl dgst -sha256 -verify "$WORK/release-key.pem" -signature "$WORK/signature.bin" \
        "$WORK/updates.json" >/dev/null 2>&1; then
    echo "verify-served: $URL is not signed by the release key" >&2
    exit 1
  fi
  set +e
  python3 - "$WORK/updates.json" "$EXPECTED" <<'PY'
import datetime, json, sys

with open(sys.argv[1], encoding="utf-8") as f:
    manifest = json.load(f)
expected = sys.argv[2]


def instant(text):
    return datetime.datetime.fromisoformat(text.replace("Z", "+00:00"))


served = manifest["published"]
if expected and instant(served) < instant(expected):
    print(f"verify-served: serves published {served}, expected {expected}", file=sys.stderr)
    sys.exit(3)
if expected and instant(served) > instant(expected):
    print(f"verify-served: serves published {served}, newer than {expected}", file=sys.stderr)
for kind, entry in manifest.get("data", {}).items():
    if "file" in entry:
        print(f"{kind}\t{entry['file']}\t{entry['sha256']}")
PY
  status=$?
  set -e
  [[ $status -eq 3 && $try -lt $TRIES ]] && { sleep "$WAIT"; continue; }
  [[ $status -eq 0 ]] || exit 1
  break
done >"$WORK/files"

base="${URL%/*}"
while IFS=$'\t' read -r kind file sha; do
  fetch "$base/$file" "$WORK/file" || { echo "verify-served: $kind: $base/$file did not answer" >&2; exit 1; }
  got="$(openssl dgst -sha256 -r "$WORK/file" | cut -d' ' -f1)"
  [[ "$got" == "$sha" ]] || { echo "verify-served: $kind: $base/$file does not match the manifest" >&2; exit 1; }
done <"$WORK/files"

published="$(python3 -c 'import json, sys; print(json.load(open(sys.argv[1]))["published"])' "$WORK/updates.json")"
echo "verify-served: $URL published $published, signed, $(wc -l <"$WORK/files" | tr -d ' ') files match"
