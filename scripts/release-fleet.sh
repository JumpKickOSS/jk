#!/usr/bin/env bash
# Drive release-fleet-check.sh / .ps1 over every machine of the fleet after a release is published,
# from the orchestrating Linux host, and log one row per system to a Markdown table. One host at a
# time, so the rest of the fleet keeps working: each drains (waits until its engine has no job and no
# agent session), stops its engines, empties ~/.jk but ~/.jk/creds, installs from jumpkick.build,
# takes the fleet's machine settings from scripts/fleet-profiles/<target>.toml when that file exists,
# starts its engine and verifies the release. A host that stays busy is left as it was and its row
# says so. This refuses unless https://jumpkick.build/releases/latest/VERSION is the version given.
# Safe to run without asking once a release is published: it interrupts no running work.
#
# Usage:
#   scripts/release-fleet.sh <version> [target...]
# Targets (default: all, one after another — never two heavy builds at once):
#   local                 this host
#   daybreak              Windows (ssh bryan@daybreak)
#   daybreak-wsl          its WSL guest Ubuntu-26.04
#   bocaminipc            Windows (ssh bryan@bocaminipc)
#   bocaminipc-wsl        its WSL guest Ubuntu-26.10
#   mac                   the Mac, reached through daybreak (bsant@192.168.68.71)
# Log: ${JK_FLEET_LOG:-~/tmp/jk-release-wall-times.md}; each target's full output beside it.
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
VER="${1:?usage: release-fleet.sh <version> [target...]}"
shift
TARGETS=("$@")
[[ ${#TARGETS[@]} -gt 0 ]] || TARGETS=(local daybreak daybreak-wsl bocaminipc bocaminipc-wsl mac)
LOG="${JK_FLEET_LOG:-$HOME/tmp/jk-release-wall-times.md}"
MAC="${JK_FLEET_MAC:-bsant@192.168.68.71}"
SSH=(ssh -o BatchMode=yes -o ConnectTimeout=15)
SCP=(scp -q -o BatchMode=yes -o ConnectTimeout=15)
SH="$ROOT/scripts/release-fleet-check.sh"
PS="$ROOT/scripts/release-fleet-check.ps1"
PROFILES="$ROOT/scripts/fleet-profiles"

published="$(curl -fsSL https://jumpkick.build/releases/latest/VERSION | tr -d '[:space:]')"
if [[ "$published" != "$VER" ]]; then
  echo "release-fleet: jumpkick.build serves '${published:-nothing}', not $VER: refusing (every target deletes its ~/.jk)" >&2
  exit 1
fi

mkdir -p "$(dirname "$LOG")"
if [[ ! -f "$LOG" ]]; then
  {
    echo "# jk release wall times"
    echo
    echo "| date | target | host | OS | cores / RAM GB | jk | commit | install s | first build s | warm build s | checkout | profile | note | success |"
    echo "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"
  } >"$LOG"
fi

run_target() {
  local t="$1" profile="$PROFILES/$1.toml"
  # A target with a profile gets it copied beside the script and named to it; one without, none.
  local env_sh="" arg_ps=""
  [[ -f "$profile" ]] || profile=""
  case "$t" in
    local) env JK_FLEET_PROFILE="$profile" bash "$SH" "$VER" ;;
    daybreak | bocaminipc)
      [[ -z "$profile" ]] || { "${SCP[@]}" "$profile" "bryan@$t:fleet-profile.toml" || return 1; arg_ps=" -FleetProfile C:\\Users\\bryan\\fleet-profile.toml"; }
      "${SCP[@]}" "$PS" "bryan@$t:release-fleet-check.ps1" &&
        "${SSH[@]}" "bryan@$t" "powershell -NoProfile -ExecutionPolicy Bypass -File C:\\Users\\bryan\\release-fleet-check.ps1 -Version $VER$arg_ps"
      ;;
    daybreak-wsl | bocaminipc-wsl)
      local h="${t%-wsl}" distro=Ubuntu-26.04
      [[ "$h" == bocaminipc ]] && distro=Ubuntu-26.10
      [[ -z "$profile" ]] || { "${SCP[@]}" "$profile" "bryan@$h:fleet-profile-wsl.toml" || return 1; env_sh="JK_FLEET_PROFILE=/mnt/c/Users/bryan/fleet-profile-wsl.toml"; }
      "${SCP[@]}" "$SH" "bryan@$h:release-fleet-check.sh" &&
        "${SSH[@]}" "bryan@$h" "wsl -d $distro -- env $env_sh bash /mnt/c/Users/bryan/release-fleet-check.sh $VER"
      ;;
    mac)
      if [[ -n "$profile" ]]; then
        "${SCP[@]}" "$profile" "bryan@daybreak:fleet-profile-mac.toml" &&
          "${SSH[@]}" bryan@daybreak "scp -q -o BatchMode=yes C:\\Users\\bryan\\fleet-profile-mac.toml ${MAC}:fleet-profile.toml" || return 1
        env_sh="JK_FLEET_PROFILE=fleet-profile.toml"
      fi
      "${SCP[@]}" "$SH" "bryan@daybreak:release-fleet-check.sh" &&
        "${SSH[@]}" bryan@daybreak "scp -q -o BatchMode=yes C:\\Users\\bryan\\release-fleet-check.sh ${MAC}:release-fleet-check.sh" &&
        "${SSH[@]}" bryan@daybreak "ssh -o BatchMode=yes $MAC 'PATH=/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin $env_sh bash release-fleet-check.sh $VER'"
      ;;
    *) echo "release-fleet: unknown target $t" >&2; return 2 ;;
  esac
}

field() { sed -n "s/.* $1=\([^ ]*\).*/\1/p" <<<" $2"; }
status=0
for t in "${TARGETS[@]}"; do
  out="${LOG%.md}-$t.log"
  echo "release-fleet: $t (log $out)" >&2
  run_target "$t" 2>&1 | tr -d '\r' >"$out"
  line="$(grep -a '^RESULT ' "$out" | tail -1)"
  if [[ -z "$line" ]]; then
    echo "| $(date -u +%F) | $t | - | - | - | - | - | - | - | - | - | - | no RESULT line (see $(basename "$out")) | false |" >>"$LOG"
    status=1
    continue
  fi
  echo "release-fleet: $line" >&2
  printf '| %s | %s | %s | %s | %s / %s | %s | %s | %s | %s | %s | %s | %s | %s | %s |\n' "$(date -u +%F)" "$t" \
    "$(field host "$line")" "$(field os "$line")" "$(field cores "$line")" "$(field ram_gb "$line")" \
    "$(field jk "$line")" "$(field commit "$line")" "$(field install_s "$line")" "$(field build1_s "$line")" \
    "$(field build2_s "$line")" "$(field checkout "$line")" "$(field profile "$line")" "$(field note "$line")" "$(field success "$line")" >>"$LOG"
  [[ "$(field success "$line")" == true ]] || status=1
done
echo "release-fleet: rows appended to $LOG" >&2
exit "$status"
