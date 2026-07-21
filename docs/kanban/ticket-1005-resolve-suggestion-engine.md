# ticket-1005 — Resolve conflict suggestion engine

**Priority:** P1 (diagnostics product)  
**Status:** done  
**Branch:** `ticket-1005-1006-features-suggestions`  
**Refs:** `Diagnostics.appendSuggestions`, `DiagnosticsTest`

## What landed

- After conflict prose, a **Suggestions:** block (≤3 lines)
- From `NoVersions` with `available:` samples → pin / relax lines
- Unknown package → **no** fabricated suggestions
- Package display via `PackageId.display()` when applicable

## Acceptance

- [x] Fixture contains `Suggestions:` + pin line
- [x] Unknown package stays without Suggestions
- [x] Offline; no extra network
- [x] `:resolver` Diagnostics tests green
