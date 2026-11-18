#!/usr/bin/env bash
# Every route to a project, built by the release candidate: each template of this checkout (the
# trees under templates/, by path, and each first-party plugin's templates, by catalogue id), generated
# fresh with its defaults, and each project of a jk-examples checkout, copied fresh. Each builds
# twice, the second build must do nothing, and a template must also pass `jk guard` as CI runs it.
#
# Usage: scripts/release-check-routes.sh [--examples <jk-examples checkout>] [--no-templates]
#                                         [--only <substring>] [--skip <substring>]...
#                                         [--args <substring>=<build args>]... [--out <dir>]
#   --examples      also build every project of that checkout (its working tree, untracked files
#                   included, ignored ones not), each copied under the out dir and committed there as
#                   a fresh clone would be; an overlay/ (a ported checkout's, which its setup.sh
#                   places) is not a project of its own and is left out. An example's build takes
#                   the arguments of the first `jk build …` its scenario.toml `check` runs. A group
#                   root (a jk.toml with [workspaces] members, such as the checkout's own root) is
#                   no route: each project under it is one of its own
#   --no-templates  only the examples
#   --only          only the routes whose name contains the substring
#   --skip          leave out the routes whose name contains the substring (repeatable), e.g. an
#                   Android template on a host whose SDK licence is not accepted
#   --args          build the routes whose name contains the substring with these arguments
#                   (repeatable), e.g. "packaging/variants=--variant contentType=demo"
#   --out           where projects, logs and the summary go (default target/release-check-routes)
# Env: JK  the jk to run (default: jk on PATH, which the release candidate's install put there)
#
# Prints one Markdown row per route and writes them to <out>/summary.md; exits 1 when any route
# fails a step. The second build counts as a no-op when its workspace-start event names 0 modules
# to build; `jk explain` before it is kept in the route's log, so a failed no-op shows what jk
# predicted it would run.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JK="${JK:-jk}"
OUT="$ROOT/target/release-check-routes"
EXAMPLES=""
TEMPLATES=1
ONLY=""
SKIP=()
ARGS=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --examples) EXAMPLES="$(cd "$2" && pwd)"; shift 2 ;;
    --no-templates) TEMPLATES=0; shift ;;
    --only) ONLY="$2"; shift 2 ;;
    --skip) SKIP+=("$2"); shift 2 ;;
    --out) OUT="$2"; shift 2 ;;
    --args) ARGS+=("$2"); shift 2 ;;
    -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
SUMMARY="$OUT/summary.md"
{
  echo "Routes built by $("$JK" --version 2>/dev/null | head -1)"
  echo
  echo "| Route | Generate | Build 1 | Build 2 | No-op | Guard |"
  echo "|---|---|---|---|---|---|"
} > "$SUMMARY"
cat "$SUMMARY"
failed=0

wanted() {
  local name="$1" s
  [[ -n "$ONLY" && "$name" != *"$ONLY"* ]] && return 1
  for s in "${SKIP[@]+"${SKIP[@]}"}"; do [[ "$name" == *"$s"* ]] && return 1; done
  return 0
}

# timed <log> <cmd...>: run in the current directory, append its output to <log>, print "ok 12s" or "FAIL 12s".
timed() {
  local log="$1" start end rc
  shift
  start=$(date +%s)
  { echo "\$ $*"; "$@"; } >> "$log" 2>&1
  rc=$?
  end=$(date +%s)
  if [[ $rc -eq 0 ]]; then echo "ok $((end - start))s"; else echo "FAIL $((end - start))s"; fi
}

# build_args <name> <dir>: the --args entry matching <name>, else the first `jk build …` of the
# directory's scenario.toml check list, else nothing.
build_args() {
  local name="$1" dir="$2" a
  for a in "${ARGS[@]+"${ARGS[@]}"}"; do
    [[ "$name" == *"${a%%=*}"* ]] && { echo "${a#*=}"; return; }
  done
  [[ -f "$dir/scenario.toml" ]] && grep -o '"jk build[^"]*"' "$dir/scenario.toml" | head -1 | sed -e 's/^"jk build *//' -e 's/"$//'
}

# check <name> <dir> <log> <guard?> <generate-cell>: the two builds and, for a template, the guard.
check() {
  local name="$1" dir="$2" log="$3" guard="$4" gen="$5" b1 b2 noop="-" g="-" modules args
  if [[ "$gen" == FAIL* ]]; then
    b1="-"; b2="-"
  else
    cd "$dir" || return
    read -r -a args <<< "$(build_args "$name" "$dir")"
    b1="$(timed "$log" "$JK" build "${args[@]+"${args[@]}"}")"
    { echo "\$ $JK explain ${args[*]+"${args[*]}"}"; "$JK" explain "${args[@]+"${args[@]}"}"; } >> "$log" 2>&1
    b2="$(timed "$log" "$JK" build "${args[@]+"${args[@]}"}" -O jsonl)"
    modules="$(grep -o '"type":"workspace-start","modules":[0-9]*' "$log" | tail -1 | grep -o '[0-9]*$')"
    if [[ "$modules" == "0" ]]; then noop="yes"; else noop="NO (${modules:-?} modules)"; fi
    if [[ "$guard" == 1 ]]; then g="$(CI=1 timed "$log" "$JK" guard)"; fi
    cd "$OUT" || return
  fi
  local row="| $name | $gen | $b1 | $b2 | $noop | $g |"
  echo "$row" | tee -a "$SUMMARY"
  if [[ "$row" == *FAIL* || "$noop" == NO* ]]; then
    failed=1
    echo "  log: $log"
  fi
}

# new_project <name> <dir> <lang> <template-ref>: generate with the template's defaults, never asking.
new_project() {
  local name="$1" dir="$2" lang="$3" ref="$4" log="$5"
  rm -rf "$dir"
  mkdir -p "$(dirname "$dir")"
  (cd "$(dirname "$dir")" && timed "$log" "$JK" new "$dir" -W demo -U com.example -l "$lang" -t "$ref" -N) < /dev/null
}

if [[ "$TEMPLATES" == 1 ]]; then
  mkdir -p "$OUT/templates" "$OUT/logs"
  # templates/<lang>/<name>.g8 and templates/<lang>/<framework>/<name>.g8, by path.
  while IFS= read -r tree; do
    rel="${tree#"$ROOT/templates/"}"
    id="${rel%.g8}"
    lang="${id%%/*}"
    wanted "template $id" || continue
    slug="${id//\//-}"
    log="$OUT/logs/template-$slug.log"; : > "$log"
    gen="$(new_project "template $id" "$OUT/templates/$slug" "$lang" "$tree" "$log")"
    check "template $id" "$OUT/templates/$slug" "$log" 1 "$gen"
  done < <(find "$ROOT/templates" -maxdepth 3 -type d -name '*.g8' | sort)
  # plugins/<plugin>/src/main/resources/templates/<lang>/<framework>/<name>.g8, by catalogue id.
  while IFS= read -r tree; do
    rel="${tree#*/src/main/resources/templates/}"
    id="${rel%.g8}"
    lang="${id%%/*}"
    ref="${id#*/}"
    wanted "template $id" || continue
    slug="${id//\//-}"
    log="$OUT/logs/template-$slug.log"; : > "$log"
    gen="$(new_project "template $id" "$OUT/templates/$slug" "$lang" "$ref" "$log")"
    check "template $id" "$OUT/templates/$slug" "$log" 1 "$gen"
  done < <(find "$ROOT"/plugins/*/src/main/resources/templates -mindepth 3 -maxdepth 3 -type d -name '*.g8' 2>/dev/null | sort)
fi

if [[ -n "$EXAMPLES" ]]; then
  mkdir -p "$OUT/examples" "$OUT/logs"
  # A project root is a jk.toml with no jk.toml above it inside the checkout but a group root's.
  group_root() { grep -qE '^\[workspaces\]' "$1/jk.toml"; }
  while IFS= read -r manifest; do
    dir="$(dirname "$manifest")"
    rel="${dir#"$EXAMPLES"/}"
    group_root "$dir" && continue
    up="$(dirname "$dir")"
    nested=0
    while [[ "$up" == "$EXAMPLES"/* ]]; do
      [[ -f "$up/jk.toml" ]] && ! group_root "$up" && nested=1 && break
      up="$(dirname "$up")"
    done
    [[ "$nested" == 1 || "$rel" == bench/* || "$rel" == */overlay ]] && continue
    wanted "example $rel" || continue
    slug="${rel//\//-}"
    log="$OUT/logs/example-$slug.log"; : > "$log"
    rm -rf "$OUT/examples/$slug"
    mkdir -p "$OUT/examples/$slug"
    (cd "$dir" && git ls-files -co --exclude-standard -z . | tar --null -T - -cf - | tar -xf - -C "$OUT/examples/$slug")
    (cd "$OUT/examples/$slug" && git init -q && git add -A \
      && git -c user.name=release-check -c user.email=release-check@localhost commit -qm "fresh copy") >> "$log" 2>&1
    check "example $rel" "$OUT/examples/$slug" "$log" 0 "copied"
  done < <(cd "$EXAMPLES" && git ls-files -co --exclude-standard '*jk.toml' | grep -E '(^|/)jk\.toml$' | sed "s|^|$EXAMPLES/|" | sort)
fi

echo
echo "summary: $SUMMARY"
exit "$failed"
