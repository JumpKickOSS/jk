#!/usr/bin/env bash
# Refuse a release signing key whose public half is not the key the installers trust: a signature
# made with any other key is one every installer and `jk self update` rejects.
# Usage: scripts/release-key-matches.sh <private-key-file> <PEM|DER>
set -euo pipefail

KEY_FILE="${1:?usage: release-key-matches.sh <private-key-file> <PEM|DER>}"
KEY_FORM="${2:?usage: release-key-matches.sh <private-key-file> <PEM|DER>}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

trusted="$(sed -n 's/^ *RELEASE_RSA_SPKI="\([A-Za-z0-9+/=]*\)".*/\1/p' "$ROOT/install.sh" | head -1)"
if [[ -z "$trusted" ]]; then
  echo "release-key-matches: no RELEASE_RSA_SPKI in install.sh" >&2
  exit 2
fi
signing="$(openssl pkey -inform "$KEY_FORM" -in "$KEY_FILE" -pubout -outform DER 2>/dev/null | openssl base64 -A)"
if [[ "$signing" != "$trusted" ]]; then
  echo "release-key-matches: the signing key is not the release key the installers trust" \
    "(install.sh RELEASE_RSA_SPKI); refusing to sign" >&2
  exit 1
fi
