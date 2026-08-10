#!/usr/bin/env bash
# Fail if commit messages in the scanned range contain agent/tool attribution trailers.
# Usage:
#   scripts/check-no-agent-attribution.sh              # entire history
#   scripts/check-no-agent-attribution.sh A..B         # revision range
set -euo pipefail

range="${1:-}"
if [[ -n "$range" ]]; then
  messages="$(git log "$range" --format=%B)"
else
  messages="$(git log --format=%B)"
fi

python3 -c '
import re, sys
text = sys.stdin.read()
pat = re.compile(
    r"(?im)^(Co-Authored-By|Generated-By|Assisted-By):\s*.*"
    r"(\b(claude|anthropic|openai|chatgpt|copilot|cursor|codex|grok|gpt-|xai)\b"
    r"|noreply@anthropic\.com|noreply@openai\.com)"
)
hits = [ln for ln in text.splitlines() if pat.search(ln)]
if hits:
    for h in hits:
        print(h, file=sys.stderr)
    print("error: agent/tool attribution trailer found in commit message(s)", file=sys.stderr)
    sys.exit(1)
print("ok: no agent/tool attribution trailers in commit messages")
' <<<"$messages"
