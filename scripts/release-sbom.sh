#!/usr/bin/env bash
# The release's CycloneDX SBOM, taken from the engine jar that ships: the engine embeds the document
# its packaging wrote (META-INF/sbom/application.cdx.json, derived from jk-lock.toml), so the SBOM
# published beside the tree describes exactly the bytes the signed SHA256SUMS lists.
#
# Usage:
#   scripts/release-sbom.sh <jk-engine-<version>.jar> <out.cdx.json>
# Refuses a document that is not CycloneDX 1.6 naming jk-engine with at least one component.
set -euo pipefail

JAR="${1:?usage: release-sbom.sh <engine-jar> <out.cdx.json>}"
OUT="${2:?usage: release-sbom.sh <engine-jar> <out.cdx.json>}"
[[ -f "$JAR" ]] || { echo "release-sbom: no engine jar at $JAR" >&2; exit 2; }
mkdir -p "$(dirname "$OUT")"
if command -v unzip >/dev/null 2>&1; then
  unzip -p "$JAR" META-INF/sbom/application.cdx.json >"$OUT"
else
  python3 -c 'import sys, zipfile; sys.stdout.buffer.write(zipfile.ZipFile(sys.argv[1]).read("META-INF/sbom/application.cdx.json"))' "$JAR" >"$OUT"
fi
[[ -s "$OUT" ]] || { echo "release-sbom: $JAR carries no META-INF/sbom/application.cdx.json" >&2; exit 1; }
summary="$(python3 -I -c '
import json, sys
d = json.load(open(sys.argv[1]))
ok = d.get("bomFormat") == "CycloneDX" and d.get("specVersion") == "1.6" \
    and len(d.get("components", [])) > 0 and d.get("metadata", {}).get("component", {}).get("name") == "jk-engine"
print(("%d components, CycloneDX %s" % (len(d.get("components", [])), d.get("specVersion"))) if ok else "")
' "$OUT")"
[[ -n "$summary" ]] || { echo "release-sbom: $OUT is not the engine's CycloneDX 1.6 document" >&2; exit 1; }
echo "release-sbom: $OUT ($summary)"
