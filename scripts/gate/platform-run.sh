#!/usr/bin/env bash
# One platform run of one commit, on the Windows or macOS host itself (Git Bash on Windows, the
# system bash 3.2 on macOS). scripts/gate/platform-loop.sh feeds it to the host on stdin. It keeps
# a checkout and a JumpKick home of its own under PLATFORM_DIR, so the host's own install, engine
# and ~/.m2 are never touched:
#
#   1. detaches the checkout at the commit, fetched from <repo-url>;
#   2. installs the commit into the home without tests (`jk install -S`); a home that does not exist
#      yet, or that cannot install the commit, is made again as CI makes one (scripts/ci-bootstrap.sh,
#      then scripts/ci-takeover.sh --install-only);
#   3. under a freshly started engine, runs every module's fast tier (`jk test -c`);
#   4. runs each test class whose source carries an OS condition, fast or integration (those tagged
#      live, slow, network or bench are left out), a JUnit 3 one through the module's test classes
#      that name it: `jk test -A -c -m <module> --class ...` per module;
#   5. runs the product smoke (scripts/ci-product-smoke.sh): the engine answers, a new project builds.
#
# It prints a `STEP <name> EXIT=<n>` line per step, the FAIL lines of each red one, and last
# `PLATFORM GREEN <sha>` or `PLATFORM RED <sha>: <red steps>`. `PLATFORM BUSY` means another run
# holds this host's platform directory; nothing ran. Step output stays in PLATFORM_DIR/logs.
#
# Usage: bash platform-run.sh <platform> <sha> <repo-url>
#
#   PLATFORM_DIR  the run's checkout, home, local Maven repository and logs (default ~/jk-platform)
#
# The whole body is one brace group, so bash has read all of it (from stdin too) before it runs.
{
  exec </dev/null
  set -u
  platform="${1:?usage: platform-run.sh <platform> <sha> <repo-url>}"
  sha="${2:?usage: platform-run.sh <platform> <sha> <repo-url>}"
  url="${3:?usage: platform-run.sh <platform> <sha> <repo-url>}"
  dir="${PLATFORM_DIR:-$HOME/jk-platform}"
  co="$dir/checkout"
  logs="$dir/logs"
  mkdir -p "$logs" || { echo "PLATFORM RED $sha: cannot write $dir"; exit 0; }

  # One run per host at a time: the lock is a directory holding the owner's pid.
  if ! mkdir "$dir/run.lock" 2>/dev/null; then
    owner="$(cat "$dir/run.lock/pid" 2>/dev/null)"
    if [ -n "$owner" ] && kill -0 "$owner" 2>/dev/null; then
      echo "PLATFORM BUSY: run $owner holds $dir"
      exit 0
    fi
    rm -rf "$dir/run.lock"
    mkdir "$dir/run.lock" || { echo "PLATFORM BUSY: $dir/run.lock"; exit 0; }
  fi
  echo $$ >"$dir/run.lock/pid"
  trap 'rm -rf "$dir/run.lock"' EXIT

  export JK_HOME="$dir/home" JK_M2_LOCAL="$dir/m2"
  export PATH="$JK_HOME/bin:$PATH"
  red=""
  # step <name> <command...>: run it in the checkout, output to logs/<name>.log; a red one prints
  # its FAIL lines and joins $red.
  step() {
    local name="$1" e
    shift
    (cd "$co" && "$@") >"$logs/$name.log" 2>&1 </dev/null
    e=$?
    echo "STEP $name EXIT=$e $(date +%H:%M:%S)"
    if [ "$e" -ne 0 ]; then
      grep -aE '^(FAIL|T |E )|FAILED ' "$logs/$name.log" | tail -20
      red="${red:+$red, }$name"
    fi
    return "$e"
  }
  verdict() {
    if [ -z "$red" ]; then echo "PLATFORM GREEN $sha"; else echo "PLATFORM RED $sha: $red"; fi
    exit 0
  }

  echo "platform $platform on $(hostname) $(uname -s) at $(date +%Y-%m-%dT%H:%M:%S)"
  if [ ! -d "$co/.git" ]; then
    rm -rf "$co"
    git clone -q -c core.autocrlf=false -c core.longpaths=true --no-checkout "$url" "$co" >"$logs/clone.log" 2>&1 \
      || { tail -5 "$logs/clone.log"; echo "PLATFORM RED $sha: cannot clone $url"; exit 0; }
  fi
  git -C "$co" cat-file -e "$sha^{commit}" 2>/dev/null || git -C "$co" fetch -q origin main >"$logs/fetch.log" 2>&1
  git -C "$co" cat-file -e "$sha^{commit}" 2>/dev/null || git -C "$co" fetch -q origin "$sha" >>"$logs/fetch.log" 2>&1
  if ! git -C "$co" checkout -q -f --detach "$sha" >"$logs/checkout.log" 2>&1; then
    tail -5 "$logs/fetch.log" "$logs/checkout.log"
    echo "PLATFORM RED $sha: cannot check out $sha"
    exit 0
  fi
  git -C "$co" clean -qfd

  # The home's own engine may still run the previous commit's jar; the install replaces it.
  if [ -x "$JK_HOME/bin/jk" ] || [ -x "$JK_HOME/bin/jk.exe" ]; then
    step install jk install -S --no-ansi || {
      jk engine stop --now --no-ansi >/dev/null 2>&1
      rm -rf "$JK_HOME"
      red=""
    }
  fi
  if [ ! -x "$JK_HOME/bin/jk" ] && [ ! -x "$JK_HOME/bin/jk.exe" ]; then
    step bootstrap bash scripts/ci-bootstrap.sh && step takeover bash scripts/ci-takeover.sh --install-only
  fi
  [ -z "$red" ] || verdict
  jk engine stop --no-ansi >/dev/null 2>&1

  step fast jk test -c --no-ansi

  # The test classes whose source names an OS, by module: `<module dir> <class>` lines. Those of the
  # live, slow, network and bench tiers are left to the runs that own those tiers. A JUnit 3 class
  # (one that extends a *TestCase) is not a class the JUnit Platform discovers: the module's test
  # classes that name it run it, so those stand in for it, and it stays itself when none does.
  junit3='class[[:space:]]+[[:alnum:]_]+([[:space:]]+extends[[:space:]]+|[[:space:]]*:[[:space:]]*)[[:alnum:]_.]*TestCase([^[:alnum:]_]|$)'
  untiered() { xargs grep -LE '@Tag\("(live|slow|network|bench)"\)' 2>/dev/null; }
  (cd "$co" && git ls-files '*/src/test/*.java' '*/src/test/*.kt' '*/src/test/*.groovy' \
    | xargs grep -lE '@(Enabled|Disabled)OnOs|OS\.(WINDOWS|MAC|LINUX)|IS_OS_|isWindows|isMac|os\.name' 2>/dev/null \
    | grep -E '(Test|Tests|IT)\.(java|kt|groovy)$' | untiered \
    | while read -r f; do
      if ! grep -qE "$junit3" "$f"; then
        echo "$f"
        continue
      fi
      hosts=""
      for h in $(git ls-files "${f%%/src/test/*}/src/test/*" | grep -E '(Test|Tests|IT)\.(java|kt|groovy)$'); do
        [ "$h" != "$f" ] && grep -qw "$(basename "${f%.*}")" "$h" && ! grep -qE "$junit3" "$h" && hosts="${hosts:+$hosts
}$h"
      done
      if [ -z "$hosts" ]; then echo "$f"; else echo "$hosts" | untiered; fi
    done \
    | sed -nE 's#^(.*)/src/test/(java|kotlin|groovy)/(.*)\.(java|kt|groovy)$#\1 \3#p' \
    | awk '{ gsub("/", ".", $2); print }' | sort -u) >"$logs/os-classes.txt"
  echo "OS_CLASSES $(wc -l <"$logs/os-classes.txt" | tr -d ' ') in $(awk '{ print $1 }' "$logs/os-classes.txt" | sort -u | wc -l | tr -d ' ') modules"
  awk '{ print $1 }' "$logs/os-classes.txt" | sort -u >"$logs/os-modules.txt"
  while read -r module; do
    set -- jk test -A -c --no-ansi -m "$module"
    while read -r class; do set -- "$@" --class "$class"; done < <(awk -v m="$module" '$1 == m { print $2 }' "$logs/os-classes.txt")
    step "os-$(echo "$module" | tr '/' '-')" "$@"
  done <"$logs/os-modules.txt"

  step smoke env JK_BIN=jk bash scripts/ci-product-smoke.sh
  jk engine stop --no-ansi >/dev/null 2>&1
  verdict
}
