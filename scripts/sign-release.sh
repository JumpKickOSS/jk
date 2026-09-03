#!/usr/bin/env bash
# Sign the exact release SHA256SUMS bytes with RSA/SHA-256 PKCS#1 v1.5.
# Usage:
# scripts/sign-release.sh path/to/SHA256SUMS /owner-only/path/to/private-key.pem
# JK_RELEASE_RSA_SIGNING_KEY='<base64 PKCS#8 DER>' scripts/sign-release.sh path/to/SHA256SUMS
# Writes path/to/SHA256SUMS.sig (raw RSA signature bytes, base64, one line).
# The public half is baked into ReleaseVerifier.BUILT_IN_KEY.
set -euo pipefail

SUMS="${1:?usage: sign-release.sh path/to/SHA256SUMS [private-key.pem]}"
if [[ ! -f "$SUMS" ]]; then
  echo "sign-release: not a file: $SUMS" >&2
  exit 2
fi
if ! command -v openssl >/dev/null 2>&1; then
  echo "sign-release: OpenSSL is required" >&2
  exit 2
fi

OUT="${SUMS}.sig"
WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/jk-sign.XXXXXX")"
trap 'rm -rf "$WORKDIR"' EXIT
umask 077

KEY_FILE="${2:-${JK_RELEASE_RSA_SIGNING_KEY_FILE:-}}"
KEY_FORM="PEM"
if [[ -n "$KEY_FILE" ]]; then
  if [[ ! -f "$KEY_FILE" ]]; then
    echo "sign-release: private key is not a file" >&2
    exit 2
  fi
elif [[ -n "${JK_RELEASE_RSA_SIGNING_KEY:-}" ]]; then
  KEY_FILE="$WORKDIR/release-key.der"
  printf '%s' "$JK_RELEASE_RSA_SIGNING_KEY" | openssl base64 -d -A >"$KEY_FILE" 2>/dev/null \
    || { echo "sign-release: JK_RELEASE_RSA_SIGNING_KEY is not valid base64" >&2; exit 2; }
  KEY_FORM="DER"
  chmod 600 "$KEY_FILE"
else
  echo "sign-release: pass a private-key.pem or set JK_RELEASE_RSA_SIGNING_KEY" >&2
  exit 2
fi

openssl dgst -sha256 -keyform "$KEY_FORM" -sign "$KEY_FILE" -out "$WORKDIR/signature.bin" "$SUMS"
openssl base64 -A -in "$WORKDIR/signature.bin" >"$OUT"
printf '\n' >>"$OUT"
echo "sign-release: wrote $OUT"
