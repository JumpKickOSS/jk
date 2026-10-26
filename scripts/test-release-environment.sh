#!/usr/bin/env bash
# Fixtures for scripts/release-environment.sh against a stub gh that records every call and the
# value piped into `gh secret set`: the environment is created with its reviewer and a tags-only
# policy; a secret goes in from stdin or a file and only under a name release.yml reads; the signing
# key is converted to the form sign-release.sh reads and a key the installers do not trust is refused
# before gh is asked anything; status names what is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/jk-release-env.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
LOG="$WORK/gh.log"
BODY="$WORK/body"
SET="$WORK/set-names"

# A tree of its own: the trusted key is whatever the fixture install.sh names.
mkdir -p "$WORK/tree/scripts" "$WORK/bin"
cp "$ROOT/scripts/release-environment.sh" "$ROOT/scripts/release-key-matches.sh" "$WORK/tree/scripts/"
SCRIPT="$WORK/tree/scripts/release-environment.sh"
openssl genrsa -out "$WORK/trusted.pem" 2048 2>/dev/null
openssl genrsa -out "$WORK/other.pem" 2048 2>/dev/null
spki="$(openssl pkey -in "$WORK/trusted.pem" -pubout -outform DER | openssl base64 -A)"
printf 'RELEASE_RSA_SPKI="%s"\n' "$spki" >"$WORK/tree/install.sh"

cat >"$WORK/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s
' "gh $*" >>"$GH_LOG"
case "$*" in
  "api user "*) echo maintainer ;;
  "api users/maintainer "*) echo 42 ;;
  "secret set "*) cat >"$GH_BODY" ;;
  "secret list "*) cat "$GH_SET" ;;
  "api -X PUT "*) cat >"$GH_BODY" ;;
esac
exit 0
EOF
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH" GH_LOG="$LOG" GH_BODY="$BODY" GH_SET="$SET" GITHUB_REPOSITORY="owner/repo"

reset() { : >"$LOG"; : >"$BODY"; }
logged() {
  grep -q -- "$1" "$LOG" || { cat "$LOG" >&2; echo "test-release-environment: gh was not asked '$1' ($2)" >&2; exit 1; }
}
not_logged() {
  if grep -q -- "$1" "$LOG"; then cat "$LOG" >&2; echo "test-release-environment: gh was asked '$1' ($2)" >&2; exit 1; fi
}
refuses() { # expected-text args...
  local expected="$1"; shift
  if "$SCRIPT" "$@" >/dev/null 2>"$WORK/err.txt" </dev/null; then
    echo "test-release-environment: '$*' was accepted ($expected)" >&2
    exit 1
  fi
  grep -q -- "$expected" "$WORK/err.txt" || { cat "$WORK/err.txt" >&2; echo "test-release-environment: '$*' refused without saying '$expected'" >&2; exit 1; }
}

# create: the environment with the caller as its reviewer, then the tags-only policy.
reset
"$SCRIPT" create >/dev/null
logged "gh api -X PUT repos/owner/repo/environments/release --input -" "the environment"
grep -q '"reviewers":\[{"type":"User","id":42}\]' "$BODY" || { cat "$BODY" >&2; echo "test-release-environment: no reviewer in the environment" >&2; exit 1; }
logged "gh api -X POST repos/owner/repo/environments/release/deployment-branch-policies -f name=v\* -f type=tag" "tags only"

# secret: the value goes in from stdin, never an argument, and only under a name release.yml reads.
reset
printf 'bucket-name' | "$SCRIPT" secret JK_RELEASE_GCS_BUCKET >"$WORK/out.txt"
logged "gh secret set JK_RELEASE_GCS_BUCKET --env release --repo owner/repo --body -" "the secret"
[[ "$(cat "$BODY")" == "bucket-name" ]] || { echo "test-release-environment: the secret value did not arrive on stdin" >&2; exit 1; }
not_logged "bucket-name" "the value is never an argument"
grep -q "bucket-name" "$WORK/out.txt" && { echo "test-release-environment: the value was printed" >&2; exit 1; }
printf 'from-file' >"$WORK/value"
reset
"$SCRIPT" secret JK_CENTRAL_GPG_PASSPHRASE "$WORK/value" >/dev/null
[[ "$(cat "$BODY")" == "from-file" ]] || { echo "test-release-environment: the file's value did not arrive" >&2; exit 1; }
reset
refuses "is not a secret release.yml reads" secret NOT_A_SECRET
[[ ! -s "$LOG" ]] || { cat "$LOG" >&2; echo "test-release-environment: an unknown name reached gh" >&2; exit 1; }

# signing-key: the trusted key arrives as base64 PKCS#8 DER; any other key is refused before gh runs.
reset
"$SCRIPT" signing-key "$WORK/trusted.pem" >/dev/null
logged "gh secret set JK_RELEASE_RSA_SIGNING_KEY --env release --repo owner/repo --body -" "the signing key"
printf '%s' "$(cat "$BODY")" | openssl base64 -d -A >"$WORK/key.der"
[[ "$(openssl pkey -inform DER -in "$WORK/key.der" -pubout -outform DER | openssl base64 -A)" == "$spki" ]] ||
  { echo "test-release-environment: the loaded key is not the trusted one" >&2; exit 1; }
reset
refuses "do not trust" signing-key "$WORK/other.pem"
[[ ! -s "$LOG" ]] || { cat "$LOG" >&2; echo "test-release-environment: an untrusted key reached gh" >&2; exit 1; }

# status: every expected name is reported as set or missing.
printf 'JK_RELEASE_RSA_SIGNING_KEY\nJK_RELEASE_GCS_BUCKET\n' >"$SET"
"$SCRIPT" status >"$WORK/out.txt"
grep -q '^set      JK_RELEASE_RSA_SIGNING_KEY$' "$WORK/out.txt" || { cat "$WORK/out.txt" >&2; echo "test-release-environment: a set secret is not reported" >&2; exit 1; }
grep -q '^MISSING  JK_CENTRAL_GPG_KEY$' "$WORK/out.txt" || { cat "$WORK/out.txt" >&2; echo "test-release-environment: a missing secret is not reported" >&2; exit 1; }

echo "test-release-environment: ok"
