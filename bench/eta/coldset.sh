#!/usr/bin/env bash
# The ETA cold set: first builds of several unrelated projects in one fresh home, in order, the way a
# machine meets new projects. Each project is copied to scratch first (never built in place), so a
# tree with work in progress is never touched. Every run is spot-checked as it finishes
# (score.py --spot), and features.py fits and judges the effort features from the result.
#
#   bench/eta/coldset.sh --dist <checkout>/target/dist [--home DIR] [--logs DIR] [--label NAME] PROJECT...
#
# --dist   the engine under test: a `jk build` dist, installed into --home
# --home   the private JK_HOME (default ~/src/scratch/eta-home-<label>); wiped first, so each label
#          keeps its own runs for features.py to read
# --logs   where the logs and manifest go (default ~/src/scratch/eta-corpus/<label>)
# PROJECT  a project directory to copy and build cold, in the order given
set -euo pipefail
HOME_DIR=""
DIST=""
LABEL="cold-$(date +%Y%m%d-%H%M)"
LOGS=""
PROJECTS=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dist) DIST="$2"; shift 2 ;;
    --home) HOME_DIR="$2"; shift 2 ;;
    --logs) LOGS="$2"; shift 2 ;;
    --label) LABEL="$2"; shift 2 ;;
    -*) echo "unknown argument: $1" >&2; exit 2 ;;
    *) PROJECTS+=("$1"); shift ;;
  esac
done
[[ -n "$DIST" && ${#PROJECTS[@]} -gt 0 ]] || { echo "usage: coldset.sh --dist DIST PROJECT..." >&2; exit 2; }
DIST="$(cd "$DIST" && pwd)"
HOME_DIR="${HOME_DIR:-$HOME/src/scratch/eta-home-$LABEL}"
LOGS="${LOGS:-$HOME/src/scratch/eta-corpus/$LABEL}"
mkdir -p "$LOGS"
LOGS="$(cd "$LOGS" && pwd)"
COPIES="$HOME/src/scratch/eta-cold-copies-$LABEL"
MANIFEST="$LOGS/manifest.tsv"
echo "# run_dir	scenario	iteration	exit	started	copy" > "$MANIFEST"

export JK_HOME="$HOME_DIR" JK_SELF_UPDATE=never
J="$JK_HOME/bin/jk"
[[ -x "$J" ]] && "$J" engine stop --now >/dev/null 2>&1 || true
rm -rf "$HOME_DIR" "$COPIES"
mkdir -p "$HOME_DIR" "$COPIES"
printf '[http]\nport = 0\n' > "$HOME_DIR/config.toml"
bash "$(dirname "$0")/../../install.sh" "$DIST/jk" > "$LOGS/install.log" 2>&1
trap '"$J" engine stop --now >/dev/null 2>&1 || true' EXIT

for src in "${PROJECTS[@]}"; do
  src="$(cd "$src" && pwd)"
  name="$(basename "$(dirname "$src")")-$(basename "$src")"
  copy="$COPIES/$name"
  mkdir -p "$copy"
  (cd "$src" && tar --exclude=target --exclude=node_modules --exclude=.jk --exclude=.playwright-mcp \
    --exclude=.angular --exclude=.next --exclude=.svelte-kit --exclude=dist -cf - .) | (cd "$copy" && tar -xf -)
  (cd "$copy" && git init -q && git add -A && git -c user.email=eta@localhost -c user.name=eta commit -qm cold)
  started="$(date +%T)"
  echo "== $name: jk build ($started)" | tee -a "$LOGS/progress.txt"
  set +e
  (cd "$copy" && "$J" build) > "$LOGS/$name.log" 2>&1
  rc=$?
  set -e
  echo "   exit=$rc $(date +%T)" | tee -a "$LOGS/progress.txt"
  run="$(ls -d "$JK_HOME"/state/builds/projects/*/ | while read -r d; do
    if grep -q "$copy" "$d/identity.toml" 2>/dev/null; then echo "$d"; fi; done | head -1 || true)"
  run="${run}runs/$(cat "${run}run-number.txt" 2>/dev/null | tr -d '[:space:]')"
  printf '%s\t%s\t1\t%s\t%s\t%s\n' "$run" "$name" "$rc" "$started" "$copy" >> "$MANIFEST"
  python3 -I "$(dirname "$0")/score.py" --spot "$run" | tee -a "$LOGS/progress.txt" || true
done
echo DONE | tee -a "$LOGS/progress.txt"
