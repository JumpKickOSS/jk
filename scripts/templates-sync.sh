#!/usr/bin/env bash
# Mirror this tree's templates/ into a checkout of the published catalog (JumpKickOSS/jk-templates),
# or with --check report where the two differ. The catalog owns only its README.md and LICENSE; every
# other path is templates/'s, so a template removed here is removed there. The README's catalog table
# is hand-written (its rule-pack column is not template metadata), so the script checks that every
# template has a row and no row names a template that is gone, and leaves the wording to a person.
# Never commits or pushes. Exit 1 on drift under --check, 2 on a usage error.
#
#   scripts/templates-sync.sh [--check] [catalog-dir]     # catalog-dir defaults to ../jk-templates
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SRC="$ROOT/templates"

check=0
if [ "${1:-}" = "--check" ]; then
  check=1
  shift
fi
CATALOG="${1:-$ROOT/../jk-templates}"
if [ ! -d "$CATALOG" ]; then
  echo "templates-sync: no catalog checkout at $CATALOG" >&2
  exit 2
fi
CATALOG="$(cd "$CATALOG" && pwd)"

drift=0
note() {
  drift=1
  echo "$1"
}

# Relative paths of every file under $1, skipping the catalog's own files and git metadata.
files_under() {
  (cd "$1" && find . -type f ! -path './.git/*' ! -path './README.md' ! -path './LICENSE' | sed 's|^\./||' | LC_ALL=C sort)
}

src_list="$(files_under "$SRC")"
cat_list="$(files_under "$CATALOG")"

while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  if [ ! -f "$CATALOG/$rel" ]; then
    note "missing  $rel"
  elif ! cmp -s "$SRC/$rel" "$CATALOG/$rel"; then
    note "changed  $rel"
  else
    continue
  fi
  if [ "$check" -eq 0 ]; then
    mkdir -p "$(dirname "$CATALOG/$rel")"
    cp "$SRC/$rel" "$CATALOG/$rel"
  fi
done <<EOF
$src_list
EOF

while IFS= read -r rel; do
  [ -n "$rel" ] || continue
  if [ ! -f "$SRC/$rel" ]; then
    note "removed  $rel"
    [ "$check" -eq 1 ] || rm -f "$CATALOG/$rel"
  fi
done <<EOF
$cat_list
EOF

if [ "$check" -eq 0 ]; then
  find "$CATALOG" -mindepth 1 -type d -empty ! -path "$CATALOG/.git*" -delete
fi

# The README table names each template once, by the name in its .jk-template.toml.
readme="$CATALOG/README.md"
if [ -f "$readme" ]; then
  names="$(find "$SRC" -name .jk-template.toml -exec sed -n 's/^name *= *"\(.*\)"/\1/p' {} + | LC_ALL=C sort -u)"
  while IFS= read -r name; do
    [ -n "$name" ] || continue
    grep -qF "| \`$name\` |" "$readme" || note "no README row for template \`$name\`"
  done <<EOF
$names
EOF
  bt="$(printf '\140')"
  rows="$(sed -n "s/^| ${bt}\([^${bt}]*\)${bt} |.*/\1/p" "$readme" | LC_ALL=C sort -u)"
  while IFS= read -r row; do
    [ -n "$row" ] || continue
    printf '%s\n' "$names" | grep -qxF "$row" || note "README row for \`$row\`, which no template is named"
  done <<EOF
$rows
EOF
fi

if [ "$drift" -eq 0 ]; then
  echo "templates-sync: $CATALOG matches templates/"
  exit 0
fi
if [ "$check" -eq 1 ]; then
  echo "templates-sync: $CATALOG differs from templates/ — run scripts/templates-sync.sh, then commit and push it" >&2
  exit 1
fi
echo "templates-sync: copied into $CATALOG; README rows above, if any, are yours to write. Review, commit and push it."
