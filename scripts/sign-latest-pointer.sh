#!/usr/bin/env bash
# Write and sign the latest-release pointer: the one mutable object every installer, wrapper and
# `jk self update` reads before fetching a version directory.
#
# Usage:
#   scripts/sign-latest-pointer.sh <version> <out-dir> [private-key.pem]
#   JK_RELEASE_RSA_SIGNING_KEY='<base64 PKCS#8 DER>' scripts/sign-latest-pointer.sh <version> <out-dir>
# Writes to <out-dir>:
#   LATEST    three LF-terminated lines: `version <version>`, `issued <unix-seconds>`, and
#             `signature <base64>`. The signature is RSA/SHA-256 PKCS#1 v1.5 over the exact bytes
#             of the first two lines, so the object is one file and its publish is one copy.
#   VERSION   the bare version, a redirect-compatible convenience that nothing verifies
# The key is the release key sign-release.sh uses for SHA256SUMS; the public half is baked into
# the installers, the wrappers and ReleaseVerifier. A verifier checks the signature, requires the
# exact three-line form, and refuses a version older than the one it ships with or already runs,
# so a pointer copied from an older release or edited in place installs nothing.
set -euo pipefail

VERSION="${1:?usage: sign-latest-pointer.sh <version> <out-dir> [private-key.pem]}"
OUT="${2:?usage: sign-latest-pointer.sh <version> <out-dir> [private-key.pem]}"
KEY_FILE="${3:-}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][A-Za-z0-9]+)*$ ]]; then
  echo "sign-latest-pointer: '$VERSION' is not a version (X.Y.Z with optional dotted or dashed qualifiers)" >&2
  exit 2
fi
if [[ -z "$KEY_FILE" && -z "${JK_RELEASE_RSA_SIGNING_KEY:-}" && -z "${JK_RELEASE_RSA_SIGNING_KEY_FILE:-}" ]]; then
  echo "sign-latest-pointer: pass a private-key.pem or set JK_RELEASE_RSA_SIGNING_KEY" >&2
  exit 2
fi

mkdir -p "$OUT"
rm -f "$OUT/LATEST.sig"
issued="${JK_POINTER_ISSUED:-$(date +%s)}"
body="$(mktemp "${TMPDIR:-/tmp}/jk-latest.XXXXXX")"
trap 'rm -f "$body" "$body.sig"' EXIT
printf 'version %s\nissued %s\n' "$VERSION" "$issued" >"$body"
if [[ -n "$KEY_FILE" ]]; then
  bash "$ROOT/scripts/sign-release.sh" "$body" "$KEY_FILE" >/dev/null
else
  bash "$ROOT/scripts/sign-release.sh" "$body" >/dev/null
fi
sig="$(tr -d '\r\n' <"$body.sig")"
{
  cat "$body"
  printf 'signature %s\n' "$sig"
} >"$OUT/LATEST"
printf '%s\n' "$VERSION" >"$OUT/VERSION"
echo "sign-latest-pointer: wrote $OUT/LATEST and $OUT/VERSION for $VERSION (issued $issued)"
