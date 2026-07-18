# ticket-1013 — Affected-since workspace builds

**Priority:** P2  
**Status:** done  
**Branch:** `ticket-ready-batch`

## Shipped

- CLI: `jk build --affected-since=<git-ref>`
- `git diff --name-only <ref>…HEAD` → map paths to modules via `AffectedModules`
- Reverse-dep closure (dependents rebuild too)
- Empty set → “nothing affected”, exit 0
- Unit tests: path mapping + reverse closure without real git

## Acceptance

- [x] Flag parses; invalid ref → usable error
- [x] Unit test: changed paths under `libs/a` → `{a, app}`, not `b`
- [x] Outside git / bad ref → clear message
- [x] Default `jk build` unchanged when flag absent
