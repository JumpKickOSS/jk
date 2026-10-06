#!/usr/bin/env bash
# The Node.js toolchain suites on this host: provisioning, `jk node`, and the node build e2e.
# Two invocations, since a tag filter refuses a --class it excludes: the untagged classes, then the
# integration-tagged ones. --network adds the classes that fetch real generators from npm.
# Usage: scripts/ci-node-windows.sh [--network]
set -euo pipefail

network=0
[[ "${1:-}" == "--network" ]] && network=1

untagged=(
  jk-toolchain-jdk:cc.jumpkick.node.NodeDiscoveryTest
  jk-toolchain:cc.jumpkick.compat.NodeProvisioningTest
  jk-engine:cc.jumpkick.runtime.EnsureNodeFirstBuildTest
  jk-engine:cc.jumpkick.runtime.NodeCommandsTest
  jk-engine:cc.jumpkick.runtime.NodeNetworkTest
  jk-engine:cc.jumpkick.runtime.NodeRunProgressTest
  jk-cli:cc.jumpkick.command.JkEnvNodeTest
)
integration=(
  jk-engine:cc.jumpkick.runtime.NodeBuildStepsTest
  jk-engine:cc.jumpkick.runtime.NodeManagerInstallsTest
  jk-engine:cc.jumpkick.runtime.NodeRegistryAuthTest
  jk-engine:cc.jumpkick.runtime.NodeStepsE2eTest
  jk-engine:cc.jumpkick.runtime.NodeUnlockedNpxTest
  jk-cli:cc.jumpkick.command.pipeline.NodeModuleBuildE2eTest
  jk-cli:cc.jumpkick.command.pipeline.NodePackagingE2eTest
  jk-cli:cc.jumpkick.command.pipeline.NodeSideBySideE2eTest
  jk-cli:cc.jumpkick.command.project.NewNodeE2eTest
  jk-cli:cc.jumpkick.command.toolchain.NodeCommandE2eTest
)
networked=(
  jk-cli:cc.jumpkick.command.pipeline.BootNodeSideBySideNetworkTest
  jk-cli:cc.jumpkick.command.project.NewNodeNetworkTest
)

# jk test -m … --class … for one set of module:class pairs, plus any extra flags.
run_set() {
  local -a args=(test --no-ansi --no-profile --continue)
  local -a modules=()
  local pair
  for pair in "${@:2}"; do
    local module="${pair%%:*}"
    [[ " ${modules[*]-} " == *" $module "* ]] || modules+=("$module")
    args+=(--class "${pair#*:}")
  done
  for module in "${modules[@]}"; do args+=(-m "$module"); done
  # shellcheck disable=SC2086 # $1 is a flag list split on purpose
  jk "${args[@]}" $1
}

# Where a module's test reports land.
module_dir() {
  case "$1" in
    jk-toolchain-jdk) echo shared/toolchain-jdk ;;
    jk-toolchain) echo server/toolchain ;;
    jk-engine) echo server/engine ;;
    jk-cli) echo clients/cli ;;
    *) echo "ci-node-windows: no directory for module $1" >&2; exit 2 ;;
  esac
}

# Every class named here ran at least one test: a fixture that cannot be built on this host
# (FakePrograms aborts without csc.exe) skips its tests, and a green run of nothing proves nothing.
prove_ran() {
  local pair failed=0 python
  python="$(command -v python3 || command -v python)"
  for pair in "$@"; do
    local report
    report="$(module_dir "${pair%%:*}")/target/surefire-reports/TEST-${pair#*:}.xml"
    if [[ ! -f "$report" ]]; then
      echo "::error::${pair#*:} left no report ($report)"
      failed=1
      continue
    fi
    "$python" - "$report" "${pair#*:}" <<'PY' || failed=1
import re, sys, xml.etree.ElementTree as ET
# Only the counts are read; a report a jk without well-formed output wrote still parses.
raw = open(sys.argv[1], "rb").read()
root = ET.fromstring(re.sub(rb"[\x00-\x08\x0b\x0c\x0e-\x1f]", b"", raw))
suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
tests = sum(int(s.get("tests", 0)) for s in suites)
skipped = sum(int(s.get("skipped", 0)) for s in suites)
print(f"{sys.argv[2]}: {tests} tests, {skipped} skipped")
if tests - skipped <= 0:
    print(f"::error::{sys.argv[2]} ran no test on this host")
    sys.exit(1)
PY
  done
  return "$failed"
}

run_set "" "${untagged[@]}"
run_set "--include-tags integration" "${integration[@]}"
if ((network)); then run_set "--include-tags network" "${networked[@]}"; fi

ran=("${untagged[@]}" "${integration[@]}")
if ((network)); then ran+=("${networked[@]}"); fi
prove_ran "${ran[@]}"
