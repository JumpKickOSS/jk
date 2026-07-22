#!/usr/bin/env bash
# Sign a release SHA256SUMS with the JumpKick Ed25519 release key (JK-1066).
#
# Usage:
#   JK_RELEASE_SIGNING_KEY=<pkcs8-base64> scripts/sign-release.sh path/to/SHA256SUMS
#
# Writes path/to/SHA256SUMS.sig (raw Ed25519 signature bytes, base64, one line).
# The public half is baked into ReleaseVerifier.BUILT_IN_KEY.
set -euo pipefail

SUMS="${1:?usage: sign-release.sh path/to/SHA256SUMS}"
if [[ ! -f "$SUMS" ]]; then
  echo "sign-release: not a file: $SUMS" >&2
  exit 2
fi
if [[ -z "${JK_RELEASE_SIGNING_KEY:-}" ]]; then
  echo "sign-release: JK_RELEASE_SIGNING_KEY (PKCS#8 base64 Ed25519) is not set" >&2
  exit 2
fi

OUT="${SUMS}.sig"
# Prefer a small Java helper so we match ReleaseVerifier's Ed25519 algorithm name.
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORKDIR="$(mktemp -d "${TMPDIR:-/tmp}/jk-sign.XXXXXX")"
trap 'rm -rf "$WORKDIR"' EXIT

cat >"$WORKDIR/Sign.java" <<'JAVA'
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

public class Sign {
    public static void main(String[] args) throws Exception {
        byte[] pkcs8 = Base64.getDecoder().decode(System.getenv("JK_RELEASE_SIGNING_KEY").trim());
        PrivateKey key = KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
        byte[] body = Files.readAllBytes(Path.of(args[0]));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(body);
        String b64 = Base64.getEncoder().encodeToString(s.sign());
        Files.writeString(Path.of(args[1]), b64 + "\n");
    }
}
JAVA

javac -d "$WORKDIR" "$WORKDIR/Sign.java"
java -cp "$WORKDIR" Sign "$SUMS" "$OUT"
echo "sign-release: wrote $OUT"
