#!/usr/bin/env bash
# The hygiene every workflow under .github/workflows/ is held to, on every pull request:
#
#   1. Every `uses:` names an action by a full 40-hex commit sha, with the release tag it stands
#      for in a trailing comment (`uses: owner/action@<sha> # v4.4.0`), so the pin can be read
#      and Dependabot can move it. A `docker://` image carries an `@sha256:` digest. A `./` path
#      is the checkout's own action and needs no pin. A tag or a branch is a name someone else
#      can move under a job that holds the signing key.
#   2. Every workflow opens with a top-level `permissions:` block whose scopes are all `read`
#      (`contents: read` at least, or `read-all`), so the GITHUB_TOKEN of a job that says
#      nothing can write nothing. A job that writes says so under its own `permissions:`,
#      naming the scope. `write-all` is refused at either level.
#   3. actionlint over the same files (syntax, expressions, shellcheck of `run:` scripts) when a
#      binary is on PATH. Under CI a missing binary fails the check, so a runner cannot pass by
#      omission; on a developer machine it is skipped with a notice.
#
# Usage:
#   scripts/check-workflows.sh                 # every .github/workflows/*.yml
#   scripts/check-workflows.sh a.yml b.yml     # these files (the fixture test)
# Every finding is printed as file:line: message; the exit status is 1 when there is one.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"

if [[ $# -gt 0 ]]; then
  FILES=("$@")
else
  FILES=("$ROOT"/.github/workflows/*.yml)
fi

findings=0
finding() { # file line message
  echo "$1:$2: $3" >&2
  findings=$((findings + 1))
}

SHA_PIN='^[^@]+@[0-9a-f]{40}$'
DIGEST_PIN='^docker://[^@]+@sha256:[0-9a-f]{64}$'
TAG_COMMENT='^#[[:space:]]*v?[0-9]+(\.[0-9]+)*'

check_uses() {
  local file="$1" n=0 line value comment lines
  mapfile -t lines <"$file"
  for line in "${lines[@]}"; do
    n=$((n + 1))
    [[ "$line" =~ ^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*(.*)$ ]] || continue
    value="${BASH_REMATCH[2]}"
    comment=""
    if [[ "$value" == *"#"* ]]; then
      comment="${value#*#}"
      comment="#${comment}"
      value="${value%%#*}"
    fi
    value="${value%"${value##*[![:space:]]}"}"
    value="${value#\"}"; value="${value%\"}"
    value="${value#\'}"; value="${value%\'}"
    if [[ "$value" == ./* ]]; then
      continue
    elif [[ "$value" == docker://* ]]; then
      [[ "$value" =~ $DIGEST_PIN ]] \
        || finding "$file" "$n" "uses: $value — a docker:// image must carry an @sha256: digest"
    elif [[ ! "$value" =~ $SHA_PIN ]]; then
      finding "$file" "$n" "uses: $value — pin the action to a full commit sha (uses: owner/action@<40 hex> # vX.Y.Z)"
    elif [[ ! "$comment" =~ $TAG_COMMENT ]]; then
      finding "$file" "$n" "uses: $value — say which release the sha is, in a trailing comment (# vX.Y.Z)"
    fi
  done
}

check_permissions() {
  local file="$1" n=0 line top_seen=0 in_top=0 in_jobs=0 value key lines
  mapfile -t lines <"$file"
  for line in "${lines[@]}"; do
    n=$((n + 1))
    [[ "$line" =~ ^[[:space:]]*# ]] && continue
    if [[ "$line" =~ ^jobs: ]]; then
      in_jobs=1
      in_top=0
    fi
    if [[ "$line" =~ ^permissions:[[:space:]]*(.*)$ ]]; then
      top_seen=1
      value="${BASH_REMATCH[1]%%#*}"
      value="${value%"${value##*[![:space:]]}"}"
      case "$value" in
        "") in_top=1 ;;
        "read-all"|"{}") ;;
        *) finding "$file" "$n" "permissions: $value — the top-level token is read-only: a block of read scopes, read-all, or {}" ;;
      esac
      continue
    fi
    if [[ $in_top -eq 1 ]]; then
      if [[ "$line" =~ ^[[:space:]]+([a-z-]+):[[:space:]]*([a-z]+) ]]; then
        key="${BASH_REMATCH[1]}"
        value="${BASH_REMATCH[2]}"
        [[ "$value" == "read" || "$value" == "none" ]] \
          || finding "$file" "$n" "permissions: $key: $value — the top-level block grants read only; a job that writes elevates under its own permissions:"
        continue
      fi
      [[ -z "${line// }" ]] || in_top=0
    fi
    if [[ $in_jobs -eq 1 && "$line" =~ ^[[:space:]]+permissions:[[:space:]]*(.*)$ ]]; then
      value="${BASH_REMATCH[1]%%#*}"
      [[ "$value" == *write-all* ]] && finding "$file" "$n" "permissions: write-all — name the scopes the job writes"
    fi
    if [[ $in_jobs -eq 1 && "$line" =~ ^[[:space:]]+([a-z-]+):[[:space:]]*write-all ]]; then
      finding "$file" "$n" "${BASH_REMATCH[1]}: write-all — name the scopes the job writes"
    fi
  done
  [[ $top_seen -eq 1 ]] \
    || finding "$file" 1 "no top-level permissions: block — every workflow opens with 'permissions:' and 'contents: read'"
}

for f in "${FILES[@]}"; do
  [[ -f "$f" ]] || { echo "check-workflows: $f is not a file" >&2; exit 2; }
  check_uses "$f"
  check_permissions "$f"
done

if [[ $findings -gt 0 ]]; then
  echo "check-workflows: $findings finding(s) in ${#FILES[@]} workflow(s)" >&2
  exit 1
fi
echo "check-workflows: ${#FILES[@]} workflow(s): every action pinned to a commit, every token read-only by default"

if command -v actionlint >/dev/null 2>&1; then
  actionlint "${FILES[@]}"
  echo "check-workflows: actionlint clean"
elif [[ -n "${CI:-}" ]]; then
  echo "check-workflows: no actionlint binary on this CI runner — the workflow installs one, pinned" >&2
  exit 1
else
  echo "check-workflows: actionlint not installed — skipping (https://github.com/rhysd/actionlint/releases; CI does not skip)" >&2
fi
