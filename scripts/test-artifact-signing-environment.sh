#!/usr/bin/env bash
# Fixtures for scripts/artifact-signing-environment.sh against a stub gh: the environment is
# created with a tags-only policy and no reviewer; a secret goes in only under a name
# build-windows reads; status names what is missing.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d "${TMPDIR:-/var/tmp}/jk-artifact-signing-env.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
LOG="$WORK/gh.log"
BODY="$WORK/body"
SET="$WORK/set-names"

mkdir -p "$WORK/bin"
SCRIPT="$ROOT/scripts/artifact-signing-environment.sh"

cat >"$WORK/bin/gh" <<'EOF'
#!/usr/bin/env bash
printf '%s\n' "gh $*" >>"$GH_LOG"
case "$*" in
  "secret set "*) cat >"$GH_BODY" ;;
  "secret list "*) cat "$GH_SET" ;;
  "api -X PUT "*) cat >"$GH_BODY" ;;
  "api -X POST "*) : ;;
  "api repos/"*"deployment-branch-policies") echo '{"branch_policies":[]}' ;;
esac
exit 0
EOF
chmod +x "$WORK/bin/gh"
export PATH="$WORK/bin:$PATH" GH_LOG="$LOG" GH_BODY="$BODY" GH_SET="$SET" GITHUB_REPOSITORY="owner/repo"

reset() { : >"$LOG"; : >"$BODY"; }
logged() {
  grep -q -- "$1" "$LOG" || { cat "$LOG" >&2; echo "test-artifact-signing-environment: gh was not asked '$1' ($2)" >&2; exit 1; }
}
refuses() {
  local expected="$1"; shift
  if "$SCRIPT" "$@" >/dev/null 2>"$WORK/err.txt" </dev/null; then
    echo "test-artifact-signing-environment: '$*' was accepted ($expected)" >&2
    exit 1
  fi
  grep -q -- "$expected" "$WORK/err.txt" || {
    cat "$WORK/err.txt" >&2
    echo "test-artifact-signing-environment: '$*' refused without saying '$expected'" >&2
    exit 1
  }
}

reset
"$SCRIPT" create >/dev/null
logged "environments/artifact-signing" "create the environment"
grep -q '"custom_branch_policies":true' "$BODY" || {
  cat "$BODY" >&2
  echo "test-artifact-signing-environment: no tags-only policy" >&2
  exit 1
}
grep -q reviewers "$BODY" && {
  cat "$BODY" >&2
  echo "test-artifact-signing-environment: unexpected reviewer on artifact-signing" >&2
  exit 1
}

reset
printf 'app-id-value' | "$SCRIPT" secret AZURE_CLIENT_ID >/dev/null
logged "secret set AZURE_CLIENT_ID" "set a known secret"
[[ "$(cat "$BODY")" == "app-id-value" ]] || {
  echo "test-artifact-signing-environment: the secret value did not arrive on stdin" >&2
  exit 1
}

refuses "not a secret" secret JK_RELEASE_RSA_SIGNING_KEY

printf '%s\n' 'AZURE_CLIENT_ID' 'AZURE_TENANT_ID' >"$SET"
"$SCRIPT" status >"$WORK/out.txt"
grep -q '^set      AZURE_CLIENT_ID$' "$WORK/out.txt" || {
  cat "$WORK/out.txt" >&2
  echo "test-artifact-signing-environment: a set secret is not reported" >&2
  exit 1
}
grep -q '^MISSING  AZURE_SUBSCRIPTION_ID$' "$WORK/out.txt" || {
  cat "$WORK/out.txt" >&2
  echo "test-artifact-signing-environment: a missing secret is not reported" >&2
  exit 1
}

echo "test-artifact-signing-environment: ok"
