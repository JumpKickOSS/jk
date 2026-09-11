#!/usr/bin/env bash
# Dogfood wall comparison: this tree built by Gradle vs by jk, on the same machine.
#
# The point is NOT to win a race with Gradle. Wall-clock parity is hygiene
# (docs/user/why.md); the Gradle column is a sanity rail. The point is a repeatable row
# so a scheduling change can be shown to have moved the number, and so a claim about
# "the gap" carries the machine it was measured on.
#
# Usage:
#   ./scripts/dogfood-wall-measure.sh                 # full: rebuild + no-op + touched
#   ./scripts/dogfood-wall-measure.sh rebuild         # only the --redo / --rerun-tasks row
#   ./scripts/dogfood-wall-measure.sh noop touched    # pick rows
#
# Env:
#   JK_BIN   — jk binary (default: ~/.jk/bin/jk, else PATH)
#   RUNS     — timed runs per side (default 2; the first warms the daemon/engine)
#   SIDES    — "both" (default), "jk", or "gradle"
#   OUT_DIR  — where logs and the row land (default: build/dogfood-wall)
#   TOUCH    — file to edit for the `touched` row. Default is a shared/core production file,
#              deliberately: it must sit in BOTH builds' default cone or the row is not a
#              comparison. `server/engine` is the trap — the CLI depends on jk-engine only as a
#              TEST dependency, so a bare `jk build` correctly does not rebuild it while
#              `./gradlew build` rebuilds everything, and the row reads as a 4x jk win that is
#              really jk doing less work.
#
# Rows:
#   rebuild  `./gradlew build dist --no-build-cache --rerun-tasks`  vs  `jk build -r`
#            Both compile everything, run the fast/unit tier, package, build the engine
#            fat jar and native-image the CLI. Dropping `dist` would let Gradle skip
#            native-image; passing --skip-tests would let jk skip the suite. Neither is fair.
#   noop     `./gradlew build dist`  vs  `jk build`  — the warm repeated cycle, which is
#            the workload jk's product bet actually rests on. When the tree carries
#            jk-guards.toml the jk side is measured raw (`[guards] on-build = false`, the
#            published comparison) and then with its house-rule guards on, labelled opt-in.
#   touched  one source file really edited, then the same commands — the everyday inner loop.
#            The file must be in both builds' default cone; see TOUCH above.
#            A `touch` is not enough: jk keys its action cache on CONTENT, so bumping mtime
#            leaves the build a no-op and the row silently measures nothing. The row appends a
#            unique comment line and restores the original bytes afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

if [[ -n "${JK_BIN:-}" ]]; then JK="$JK_BIN"
elif [[ -x "$HOME/.jk/bin/jk" ]]; then JK="$HOME/.jk/bin/jk"
else JK="$(command -v jk || true)"; fi

RUNS="${RUNS:-2}"
SIDES="${SIDES:-both}"
OUT_DIR="${OUT_DIR:-$ROOT/build/dogfood-wall}"
TOUCH="${TOUCH:-shared/core/src/main/java/cc/jumpkick/config/WorkspaceClasspath.java}"
ROWS=("$@")
[[ ${#ROWS[@]} -eq 0 ]] && ROWS=(rebuild noop touched)

mkdir -p "$OUT_DIR"
ROW_FILE="$OUT_DIR/row.md"
: > "$ROW_FILE"
# The machine-readable twin of row.md: one JSON object per line, an `env` object followed by one
# `measurement` per side per row. Trend tooling and the scheduled CI job read this file; the
# markdown is for people, and a table nobody can parse is not a baseline.
ROW_JSONL="$OUT_DIR/row.jsonl"
: > "$ROW_JSONL"

# emit_json <type> <key=value>… — values are strings, except a key ending in `_s`, whose value is
# the slash-separated wall list the markdown column carries and lands as an array of seconds.
emit_json() {
  python3 - "$ROW_JSONL" "$@" <<'PY'
import json, sys
out, kind, *pairs = sys.argv[1:]
row = {"type": kind}
for pair in pairs:
    key, _, value = pair.partition("=")
    if key.endswith("_s"):
        row[key] = [None if part == "FAILED" else float(part) for part in value.split("/") if part]
    else:
        row[key] = value
with open(out, "a", encoding="utf-8") as handle:
    handle.write(json.dumps(row, sort_keys=True) + "\n")
PY
}

want_jk()     { [[ "$SIDES" == both || "$SIDES" == jk ]]; }
want_gradle() { [[ "$SIDES" == both || "$SIDES" == gradle ]]; }

# ---------------------------------------------------------------------------
# Environment. Every field here changed a number or a conclusion at least once, so the
# row is not trustworthy without it.
#
# Free memory is the one people omit and the one that matters most on the jk side: jk
# sizes worker JVM heaps and the PluginSlots permit count from available RAM (HeapPlan),
# so the same tree on the same cores forks a different number of workers depending on
# what else is resident. Gradle's `maxParallelForks = cores/2` does not vary that way.
# A row without free memory cannot be compared across machines or across days.
# ---------------------------------------------------------------------------
env_block() {
  local os cpu cores threads memtotal memavail jkver headsha jkbuilt
  os="$(uname -srm)"
  cpu="$(sed -n 's/^model name[[:space:]]*: //p' /proc/cpuinfo 2>/dev/null | head -1)"
  [[ -z "$cpu" ]] && cpu="$(sysctl -n machdep.cpu.brand_string 2>/dev/null || echo unknown)"
  threads="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo '?')"
  cores="$(lscpu 2>/dev/null | sed -n 's/^Core(s) per socket:[[:space:]]*//p' | head -1)"
  [[ -z "$cores" ]] && cores="?"
  memtotal="$(awk '/MemTotal/{printf "%.1f", $2/1048576}' /proc/meminfo 2>/dev/null || echo '?')"
  memavail="$(awk '/MemAvailable/{printf "%.1f", $2/1048576}' /proc/meminfo 2>/dev/null || echo '?')"
  headsha="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
  jkver="$([[ -x "$JK" ]] && "$JK" --version 2>/dev/null | head -1 || echo 'not installed')"
  # A stale client silently measures an older engine, which is the single easiest way to
  # produce a number nobody can reproduce.
  if [[ -x "$JK" ]] && [[ -f "$ROOT/build/dist/jk" ]] \
     && cmp -s "$JK" "$ROOT/build/dist/jk"; then jkbuilt="yes (matches build/dist/jk)"
  else jkbuilt="UNVERIFIED — client differs from build/dist/jk; run ./gradlew dist installLocal && ./install.sh build/dist/jk"; fi

  {
    echo "### Environment"
    echo
    echo "| field | value |"
    echo "|---|---|"
    echo "| date | $(date -Iseconds) |"
    echo "| os | $os |"
    echo "| cpu | $cpu |"
    echo "| cores / threads | $cores / $threads |"
    echo "| memory total / **available at start** | ${memtotal} GiB / **${memavail} GiB** |"
    echo "| HEAD | \`$headsha\` |"
    echo "| jk | $jkver |"
    echo "| jk built from HEAD | $jkbuilt |"
    echo "| gradle | $(./gradlew --version 2>/dev/null | sed -n 's/^Gradle //p' | head -1) |"
    echo "| JAVA_HOME | ${JAVA_HOME:-unset} |"
    echo "| runs per side | $RUNS (first warms the daemon / engine) |"
    echo
    echo "Guard parity: both builds enforce the same guard letters (G51 checks the two sets)."
    echo
  } >> "$ROW_FILE"

  emit_json env \
    "date=$(date -Iseconds)" "os=$os" "cpu=$cpu" "cores=$cores" "threads=$threads" \
    "mem_total_gib=$memtotal" "mem_available_gib=$memavail" "head=$headsha" \
    "jk=$jkver" "jk_built_from_head=$jkbuilt" "runs=$RUNS"
}

# Change a source file's CONTENT, not just its mtime. jk hashes inputs, so `touch` alone leaves
# the build fully cached and the "touched" row measures a no-op instead of an incremental build.
# Restores from the backup first so successive runs are the same one-line delta, not a growing file.
real_edit() {
  local f="$1" tag="$2"
  [[ -f "$TOUCH_BACKUP" ]] && cp "$TOUCH_BACKUP" "$f"
  printf '\n// dogfood-wall-measure edit %s\n' "$tag" >> "$f"
}

# Wall seconds of a command, appended to a log. Echoes the elapsed time.
timed() {
  local log="$1"; shift
  local t0 t1
  t0=$(date +%s.%N)
  if ! "$@" >>"$log" 2>&1; then echo "FAILED" ; return 0; fi
  t1=$(date +%s.%N)
  awk -v a="$t0" -v b="$t1" 'BEGIN{printf "%.2f", b-a}'
}

# ---------------------------------------------------------------------------
# Guards. This tree dogfoods jk's house-rule guards (jk-guards.toml), and a lane runs inside
# `jk build`. The published Gradle-vs-jk trend is a comparison of RAW builds, so the jk side is
# measured with `[guards] on-build = false` first — every lane but the model lane waits for the
# gate — and then again as contributors run it, labelled opt-in, so the guards' own cost is a
# number beside the wall rather than folded into it. The manifest is restored however the row exits.
# ---------------------------------------------------------------------------
MANIFEST_BACKUP="$OUT_DIR/jk.toml.orig"
LOCK_BACKUP="$OUT_DIR/jk-lock.toml.orig"
guards_off() {
  # Backups are this run's only: one left behind by an earlier run would be restored over a tree
  # that had no lock, or a different one.
  rm -f "$MANIFEST_BACKUP" "$LOCK_BACKUP"
  cp jk.toml "$MANIFEST_BACKUP"
  # The lock records the manifests' hash; the edited jk.toml makes jk rewrite that line, so the
  # lock goes back with the manifest or the tree is left dirty by one hash.
  [[ -f jk-lock.toml ]] && cp jk-lock.toml "$LOCK_BACKUP"
  printf '\n[guards]\non-build = false\n' >> jk.toml
}
guards_restore() {
  [[ -f "$MANIFEST_BACKUP" ]] && cp "$MANIFEST_BACKUP" jk.toml
  [[ -f "$LOCK_BACKUP" ]] && cp "$LOCK_BACKUP" jk-lock.toml
  rm -f "$MANIFEST_BACKUP" "$LOCK_BACKUP"
  return 0
}
has_guards() { [[ -f jk-guards.toml ]]; }

run_side() {  # run_side <label> <logbase> -- cmd...
  local label="$1" logbase="$2"; shift 3
  local walls=() i w
  for ((i=1; i<=RUNS; i++)); do
    w="$(timed "$OUT_DIR/${logbase}-$i.log" "$@")"
    walls+=("$w")
    printf '  %-46s run %d: %s s\n' "$label" "$i" "$w" >&2
  done
  ( IFS='/'; echo "${walls[*]}" )
}

# ---------------------------------------------------------------------------
# jk-side facts the wall alone does not show. Read from the run the CLI just wrote:
# metrics.toml has per-module per-step walls, details.jsonl has module-start /
# module-finish timestamps.
#
# `cli_starts_before_engine_finishes` is the load-bearing one. It is the invariant the
# batch-per-level executor violated: engine's package-assembly shared a level with
# engine's 26.5 s suite, so artifactsReady fired at module-finish and the CLI — which
# needs only the jar — started at exactly that millisecond.
# ---------------------------------------------------------------------------
jk_detail() {
  local runs_root="$HOME/.jk/state/builds/projects"
  local latest
  # awk reads to EOF: `head -1` would close the pipe under sort and, with pipefail, end the script
  # with 141 once the runs root holds more records than one pipe buffer.
  latest="$(find "$runs_root" -name details.jsonl -printf '%T@ %p\n' 2>/dev/null | sort -rn | awk 'NR==1{sub(/^[^ ]* /,""); print}')"
  [[ -z "$latest" ]] && { echo "  (no details.jsonl found)" >&2; return 0; }
  python3 - "$latest" "$(dirname "$latest")/metrics.toml" <<'PY' >> "$ROW_FILE"
import json,re,sys
ev=[json.loads(l) for l in open(sys.argv[1]) if l.strip()]
t0=ev[0]["ts"]
start={};fin={}
for e in ev:
    if e["type"]=="module-start": start[e["coord"]]=(e["ts"]-t0)/1000
    if e["type"]=="module-finish": fin[e["coord"]]=(e["ts"]-t0)/1000
step={}
try:
    for line in open(sys.argv[2]):
        m=re.match(r'module\.(/\S+?)\.task\.([a-z-]+)\.wall-ms = (\d+)', line.strip())
        if m: step[(m.group(1).split("/")[-2]+"/"+m.group(1).split("/")[-1], m.group(2))]=int(m.group(3))/1000
except OSError: pass
def g(mod,task): return step.get((mod,task))
def fmt(v): return "n/a" if v is None else f"{v:.2f} s"
cli_start=start.get("cc.jumpkick:jk-cli"); eng_fin=fin.get("cc.jumpkick:jk-engine")
print("| jk fact | value |")
print("|---|---|")
print(f"| engine `run-tests` | {fmt(g('server/engine','run-tests'))} |")
print(f"| engine `package-assembly` | {fmt(g('server/engine','package-assembly'))} |")
print(f"| cli `native-image` | {fmt(g('clients/cli','native-image'))} |")
print(f"| cli `run-tests` | {fmt(g('clients/cli','run-tests'))} |")
print(f"| jk-host `resolve-deps` | {fmt(g('shared/host','resolve-deps'))} |")
print(f"| engine module-finish | {fmt(eng_fin)} |")
print(f"| cli module-start | {fmt(cli_start)} |")
if cli_start is not None and eng_fin is not None:
    slack=eng_fin-cli_start
    ok="YES" if slack>0.5 else "**NO — the barrier is back**"
    print(f"| **cli starts before engine finishes** | {ok} (slack {slack:+.2f} s) |")
print()
PY
}

# ---------------------------------------------------------------------------
env_block

for row in "${ROWS[@]}"; do
  echo "== row: $row ==" >&2
  case "$row" in
    rebuild)
      echo "### Row: rebuild (\`--rerun-tasks\` / \`--redo\`)" >> "$ROW_FILE"; echo >> "$ROW_FILE"
      echo "| side | command | walls (s) |" >> "$ROW_FILE"; echo "|---|---|---|" >> "$ROW_FILE"
      if want_gradle; then
        w="$(run_side "gradle rebuild" gradle-rebuild -- ./gradlew build dist --no-build-cache --rerun-tasks --console=plain)"
        echo "| Gradle | \`build dist --no-build-cache --rerun-tasks\` | $w |" >> "$ROW_FILE"
        emit_json measurement "row=rebuild" "side=gradle" \
          "command=./gradlew build dist --no-build-cache --rerun-tasks" "walls_s=$w"
      fi
      if want_jk; then
        w="$(run_side "jk rebuild" jk-rebuild -- "$JK" build -r)"
        echo "| jk | \`jk build -r\` | $w |" >> "$ROW_FILE"
        emit_json measurement "row=rebuild" "side=jk" "command=jk build -r" "walls_s=$w"
      fi
      echo >> "$ROW_FILE"
      want_jk && jk_detail
      ;;
    noop)
      echo "### Row: warm no-op" >> "$ROW_FILE"; echo >> "$ROW_FILE"
      echo "| side | command | walls (s) |" >> "$ROW_FILE"; echo "|---|---|---|" >> "$ROW_FILE"
      if want_gradle; then
        w="$(run_side "gradle no-op" gradle-noop -- ./gradlew build dist --console=plain)"
        echo "| Gradle | \`build dist\` | $w |" >> "$ROW_FILE"
        emit_json measurement "row=noop" "side=gradle" "command=./gradlew build dist" "walls_s=$w"
      fi
      if want_jk; then
        if has_guards; then
          guards_off; trap guards_restore EXIT
          "$JK" build >> "$OUT_DIR/jk-noop-guards-off-warm.log" 2>&1 || true   # re-plan under the new manifest
          w="$(run_side "jk no-op (guards off)" jk-noop-guards-off -- "$JK" build)"
          guards_restore; trap - EXIT
          echo "| jk | \`jk build\`, raw (\`[guards] on-build = false\`) | $w |" >> "$ROW_FILE"
          emit_json measurement "row=noop" "side=jk" "command=jk build" "guards=off" "walls_s=$w"
          "$JK" build >> "$OUT_DIR/jk-noop-guards-warm.log" 2>&1 || true
          w="$(run_side "jk no-op (guards on, opt-in)" jk-noop -- "$JK" build)"
          echo "| jk | \`jk build\`, with house-rule guards (opt-in) | $w |" >> "$ROW_FILE"
          emit_json measurement "row=noop" "side=jk-guards" "command=jk build" "guards=on" "walls_s=$w"
        else
          w="$(run_side "jk no-op" jk-noop -- "$JK" build)"
          echo "| jk | \`jk build\` | $w |" >> "$ROW_FILE"
          emit_json measurement "row=noop" "side=jk" "command=jk build" "walls_s=$w"
        fi
      fi
      echo >> "$ROW_FILE"
      ;;
    touched)
      echo "### Row: one module touched (\`$TOUCH\`)" >> "$ROW_FILE"; echo >> "$ROW_FILE"
      echo "| side | command | walls (s) |" >> "$ROW_FILE"; echo "|---|---|---|" >> "$ROW_FILE"
      # Restore the file's exact bytes however this exits — a measurement script must not leave
      # an edit behind in the tree it measured.
      TOUCH_BACKUP="$OUT_DIR/$(basename "$TOUCH").orig"
      rm -f "$TOUCH_BACKUP"
      cp "$TOUCH" "$TOUCH_BACKUP"
      restore_touched() { [[ -f "$TOUCH_BACKUP" ]] && cp "$TOUCH_BACKUP" "$TOUCH" && rm -f "$TOUCH_BACKUP"; return 0; }
      trap restore_touched EXIT
      if want_gradle; then
        walls=()
        for ((i=1; i<=RUNS; i++)); do
          real_edit "$TOUCH" "$i"
          walls+=("$(timed "$OUT_DIR/gradle-touched-$i.log" ./gradlew build dist --console=plain)")
          printf '  %-46s run %d: %s s\n' "gradle touched" "$i" "${walls[-1]}" >&2
        done
        echo "| Gradle | \`build dist\` after an edit | $(IFS='/'; echo "${walls[*]}") |" >> "$ROW_FILE"
        emit_json measurement "row=touched" "side=gradle" "command=./gradlew build dist" \
          "walls_s=$(IFS='/'; echo "${walls[*]}")" "touched=$TOUCH"
      fi
      if want_jk; then
        if has_guards; then
          guards_off
          restore_all() { restore_touched; guards_restore; }
          trap restore_all EXIT
          walls=()
          for ((i=1; i<=RUNS; i++)); do
            real_edit "$TOUCH" "1$i"
            walls+=("$(timed "$OUT_DIR/jk-touched-guards-off-$i.log" "$JK" build)")
            printf '  %-46s run %d: %s s\n' "jk touched (guards off)" "$i" "${walls[-1]}" >&2
          done
          guards_restore
          echo "| jk | \`jk build\` after an edit, raw (\`[guards] on-build = false\`) | $(IFS='/'; echo "${walls[*]}") |" >> "$ROW_FILE"
          emit_json measurement "row=touched" "side=jk" "command=jk build" "guards=off" \
            "walls_s=$(IFS='/'; echo "${walls[*]}")" "touched=$TOUCH"
          walls=()
          for ((i=1; i<=RUNS; i++)); do
            real_edit "$TOUCH" "2$i"
            walls+=("$(timed "$OUT_DIR/jk-touched-$i.log" "$JK" build)")
            printf '  %-46s run %d: %s s\n' "jk touched (guards on, opt-in)" "$i" "${walls[-1]}" >&2
          done
          echo "| jk | \`jk build\` after an edit, with house-rule guards (opt-in) | $(IFS='/'; echo "${walls[*]}") |" >> "$ROW_FILE"
          emit_json measurement "row=touched" "side=jk-guards" "command=jk build" "guards=on" \
            "walls_s=$(IFS='/'; echo "${walls[*]}")" "touched=$TOUCH"
        else
          walls=()
          for ((i=1; i<=RUNS; i++)); do
            real_edit "$TOUCH" "1$i"
            walls+=("$(timed "$OUT_DIR/jk-touched-$i.log" "$JK" build)")
            printf '  %-46s run %d: %s s\n' "jk touched" "$i" "${walls[-1]}" >&2
          done
          echo "| jk | \`jk build\` after an edit | $(IFS='/'; echo "${walls[*]}") |" >> "$ROW_FILE"
          emit_json measurement "row=touched" "side=jk" "command=jk build" \
            "walls_s=$(IFS='/'; echo "${walls[*]}")" "touched=$TOUCH"
        fi
      fi
      restore_touched
      guards_restore
      trap - EXIT
      echo >> "$ROW_FILE"
      ;;
    *) echo "unknown row: $row (want: rebuild noop touched)" >&2; exit 2 ;;
  esac
done

echo >&2
echo "row written to $ROW_FILE" >&2
echo "machine-readable rows in $ROW_JSONL" >&2
echo "logs in $OUT_DIR" >&2
echo >&2
cat "$ROW_FILE"
