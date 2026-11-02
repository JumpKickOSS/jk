#!/usr/bin/env bash
# Set up the GitHub environment the release publishes from, and load its secrets.
#
# Usage:
#   scripts/release-environment.sh create [reviewer-login]   # the environment, its reviewer, tags only
#   scripts/release-environment.sh signing-key <key.pem>     # the release signing key, as a secret
#   scripts/release-environment.sh secret <NAME> [file]      # one secret, from a file or stdin
#   scripts/release-environment.sh status                    # which secrets are set, which are missing
# release.yml's publish job names the `release` environment, so it alone sees these secrets, and
# only after a required reviewer approves the run. Every value goes from its file or stdin straight
# into `gh secret set`: nothing here prints, logs or keeps one. Needs gh logged in with admin rights
# on the repository (GITHUB_REPOSITORY, or the repository of the current checkout).
set -euo pipefail

ENVIRONMENT=release
# The secrets release.yml reads; `secret` refuses any other name.
SECRETS=(
  JK_RELEASE_RSA_SIGNING_KEY
  JK_RELEASE_GCS_BUCKET
  JK_RELEASE_GCS_SA_JSON
  JK_CENTRAL_GPG_KEY
  JK_CENTRAL_GPG_PASSPHRASE
  JK_REPO_CENTRAL_USERNAME
  JK_REPO_CENTRAL_PASSWORD
)
USAGE="usage: release-environment.sh create [reviewer-login] | signing-key <key.pem> | secret <NAME> [file] | status"
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

die() {
  echo "release-environment: $*" >&2
  exit "${2:-1}"
}

repo() {
  if [[ -n "${GITHUB_REPOSITORY:-}" ]]; then
    printf '%s' "$GITHUB_REPOSITORY"
  else
    gh repo view --json nameWithOwner --jq .nameWithOwner
  fi
}

known() { # NAME
  local name
  for name in "${SECRETS[@]}"; do [[ "$name" == "$1" ]] && return 0; done
  return 1
}

set_secret() { # NAME, value on stdin
  known "$1" || die "$1 is not a secret release.yml reads (${SECRETS[*]})" 64
  gh secret set "$1" --env "$ENVIRONMENT" --repo "$(repo)" --body - >/dev/null
  echo "release-environment: $1 set in the $ENVIRONMENT environment"
}

create() {
  local slug reviewer id
  slug="$(repo)"
  reviewer="${1:-$(gh api user --jq .login)}"
  id="$(gh api "users/$reviewer" --jq .id)"
  # Tags only: a branch push can never reach the secrets. The maintainer who cuts a release is
  # often the only reviewer, so a reviewer may approve the run they started.
  printf '{"reviewers":[{"type":"User","id":%s}],"prevent_self_review":false,"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' "$id" |
    gh api -X PUT "repos/$slug/environments/$ENVIRONMENT" --input - >/dev/null
  if ! gh api "repos/$slug/environments/$ENVIRONMENT/deployment-branch-policies" --jq '.branch_policies[] | select(.name == "v*" and .type == "tag") | .id' | grep -q .; then
    gh api -X POST "repos/$slug/environments/$ENVIRONMENT/deployment-branch-policies" -f name='v*' -f type=tag >/dev/null
  fi
  echo "release-environment: $slug environment $ENVIRONMENT: reviewer $reviewer, tags v* only"
}

signing_key() { # key.pem
  local pem="${1:?$USAGE}"
  [[ -f "$pem" ]] || die "$pem is not a file" 2
  "$HERE/release-key-matches.sh" "$pem" PEM || die "refusing to load a key the installers do not trust"
  # The form sign-release.sh reads from the environment: base64 of the PKCS#8 DER key.
  openssl pkcs8 -topk8 -nocrypt -in "$pem" -outform DER | openssl base64 -A | set_secret JK_RELEASE_RSA_SIGNING_KEY
}

status() {
  local set name
  set="$(gh secret list --env "$ENVIRONMENT" --repo "$(repo)" --json name --jq '.[].name')"
  for name in "${SECRETS[@]}"; do
    if grep -qx "$name" <<<"$set"; then echo "set      $name"; else echo "MISSING  $name"; fi
  done
}

case "${1:-}" in
  create) create "${2:-}" ;;
  signing-key) signing_key "${2:-}" ;;
  secret)
    name="${2:?$USAGE}"
    if [[ -n "${3:-}" ]]; then
      [[ -f "$3" ]] || die "$3 is not a file" 2
      set_secret "$name" <"$3"
    else
      set_secret "$name"
    fi
    ;;
  status) status ;;
  *) die "$USAGE" 64 ;;
esac
