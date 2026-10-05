#!/usr/bin/env bash
set -euo pipefail
version="$(tr -d '[:space:]' < .jk/ci-bootstrap-version)"
curl -fsSL https://jumpkick.build/install.sh | JK_VERSION="$version" bash
