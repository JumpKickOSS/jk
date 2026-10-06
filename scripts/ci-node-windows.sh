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

run_set "" "${untagged[@]}"
run_set "--include-tags integration" "${integration[@]}"
if ((network)); then run_set "--include-tags network" "${networked[@]}"; fi
