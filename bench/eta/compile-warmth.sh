#!/usr/bin/env bash
# What a compile costs on a cold compiler worker against a warm one (JK-D3785). For each project: stop
# the engine, so the next build starts a fresh engine and compiler worker; force a build that compiles
# everything and runs no tests (the cold run); then force the same build again while the worker still
# lingers (the warm run). The same compiles run in both, so the difference is the worker's warm-up.
# Projects are copied to scratch first, as coldset.sh does; a path given with --in-place (jk's own
# corpus worktree) is built where it is.
#
#   bench/eta/compile-warmth.sh --dist DIST [--label NAME] [--in-place DIR] PROJECT...
#
# The manifest pairs each project's cold and warm runs; compile_warmth.py reads it.
set -euo pipefail
DIST=""
LABEL="warmth-$(date +%Y%m%d-%H%M)"
IN_PLACE=()
PROJECTS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dist) DIST="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    --in-place) IN_PLACE+=("$2"); shift 2 ;;
    -*) echo "unknown argument: $1" >&2; exit 2 ;;
    *) PROJECTS+=("$1"); shift ;;
  esac
done
[[ -n "$DIST" ]] || { echo "usage: compile-warmth.sh --dist DIST PROJECT..." >&2; exit 2; }
DIST="$(cd "$DIST" && pwd)"
LOGS="$HOME/src/scratch/eta-corpus/$LABEL"
H="$HOME/src/scratch/eta-home-$LABEL"
COPIES="$HOME/src/scratch/eta-copies-$LABEL"
mkdir -p "$LOGS"
MANIFEST="$LOGS/manifest.tsv"
echo "# run_dir	project	phase	exit" > "$MANIFEST"
export JK_HOME="$H" JK_SELF_UPDATE=never
J="$H/bin/jk"
[[ -x "$J" ]] && "$J" engine stop --now >/dev/null 2>&1 || true
rm -rf "$H" "$COPIES"
mkdir -p "$H" "$COPIES"
printf '[http]\nport = 0\n' > "$H/config.toml"
bash "$(dirname "$0")/../../install.sh" "$DIST/jk" > "$LOGS/install.log" 2>&1
trap '"$J" engine stop --now >/dev/null 2>&1 || true' EXIT

last_run() {
  local dir="$1" proj
  proj="$(ls -d "$JK_HOME"/state/builds/projects/*/ | while read -r d; do
    if grep -q "$dir" "$d/identity.toml" 2>/dev/null; then echo "$d"; fi; done | head -1 || true)"
  echo "${proj}runs/$(cat "${proj}run-number.txt" 2>/dev/null | tr -d '[:space:]')"
}

one() {
  local name="$1" dir="$2" phase rc
  # A first, unforced build resolves and syncs, so neither measured run pays for downloads.
  (cd "$dir" && "$J" build --skip-tests) > "$LOGS/$name-prime.log" 2>&1 || true
  for phase in cold warm; do
    [[ "$phase" == cold ]] && "$J" engine stop --now >/dev/null 2>&1 || true
    echo "== $name $phase ($(date +%T))" | tee -a "$LOGS/progress.txt"
    set +e
    (cd "$dir" && "$J" build -F --skip-tests) > "$LOGS/$name-$phase.log" 2>&1
    rc=$?
    set -e
    echo "   exit=$rc $(date +%T)" | tee -a "$LOGS/progress.txt"
    printf '%s\t%s\t%s\t%s\n' "$(last_run "$dir")" "$name" "$phase" "$rc" >> "$MANIFEST"
  done
}

for src in "${PROJECTS[@]}"; do
  src="$(cd "$src" && pwd)"
  name="$(basename "$(dirname "$src")")-$(basename "$src")"
  copy="$COPIES/$name"
  mkdir -p "$copy"
  (cd "$src" && tar --exclude=target --exclude=node_modules --exclude=.jk --exclude=.playwright-mcp \
    --exclude=.angular --exclude=.next --exclude=.svelte-kit --exclude=dist -cf - .) | (cd "$copy" && tar -xf -)
  (cd "$copy" && git init -q && git add -A && git -c user.email=eta@localhost -c user.name=eta commit -qm warmth)
  one "$name" "$copy"
done
for dir in "${IN_PLACE[@]}"; do
  dir="$(cd "$dir" && pwd)"
  one "$(basename "$dir")" "$dir"
done
echo DONE | tee -a "$LOGS/progress.txt"
