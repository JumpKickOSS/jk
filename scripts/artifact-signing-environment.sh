#!/usr/bin/env bash
# Set up the GitHub environment the Windows release build uses for Azure Artifact Signing
# (Authenticode on jk.exe), and load its OIDC secrets.
#
# Usage:
#   scripts/artifact-signing-environment.sh create              # environment, tags v* only
#   scripts/artifact-signing-environment.sh secret <NAME> [file]  # one secret, from a file or stdin
#   scripts/artifact-signing-environment.sh status              # which secrets are set
#
# sign-windows.yml's windows job and release.yml's build-windows job name the `artifact-signing`
# environment. The Entra app's federated credential subject is
# repo:<owner>/<repo>:environment:artifact-signing (OIDC; no client secret). Needs gh logged in
# with admin rights on the repository.
set -euo pipefail

ENVIRONMENT=artifact-signing
SECRETS=(
  AZURE_CLIENT_ID
  AZURE_TENANT_ID
  AZURE_SUBSCRIPTION_ID
)
USAGE="usage: artifact-signing-environment.sh create | secret <NAME> [file] | status"

die() {
  echo "artifact-signing-environment: $*" >&2
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
  known "$1" || die "$1 is not a secret the signing jobs read (${SECRETS[*]})" 64
  gh secret set "$1" --env "$ENVIRONMENT" --repo "$(repo)" --body - >/dev/null
  echo "artifact-signing-environment: $1 set in the $ENVIRONMENT environment"
}

create() {
  local slug
  slug="$(repo)"
  # No required reviewer: Authenticode runs mid-build on a release tag or a v0.0.0-signtest.<n> dry-run tag.
  # Tags only: a branch push cannot reach the Azure IDs.
  printf '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}' |
    gh api -X PUT "repos/$slug/environments/$ENVIRONMENT" --input - >/dev/null
  if ! gh api "repos/$slug/environments/$ENVIRONMENT/deployment-branch-policies" \
    --jq '.branch_policies[] | select(.name == "v*" and .type == "tag") | .id' | grep -q .; then
    gh api -X POST "repos/$slug/environments/$ENVIRONMENT/deployment-branch-policies" \
      -f name='v*' -f type=tag >/dev/null
  fi
  echo "artifact-signing-environment: $slug environment $ENVIRONMENT: tags v* only"
}

status() {
  local set name
  set="$(gh secret list --env "$ENVIRONMENT" --repo "$(repo)" --json name --jq '.[].name')"
  for name in "${SECRETS[@]}"; do
    if grep -qx "$name" <<<"$set"; then echo "set      $name"; else echo "MISSING  $name"; fi
  done
}

case "${1:-}" in
  create) create ;;
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
