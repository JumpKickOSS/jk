# ticket-1037 — Programmable escape hatch MVP

**Priority:** P1  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §6  
**Depends on:** ticket-1026 (design freeze)  
**Branch:** `ticket-1037-escape-hatch-mvp`

## Goal

Ship MVP defined by 1026 PRD — minimum:

1. Custom task → generated resource (line-count analogue)  
2. Participates in action cache (noop hits)  
3. `jk.toml` only references hatch entrypoint (no scripts)  

## Acceptance

- [ ] Sample / fixture project  
- [ ] Cache hit on unchanged custom task  
- [ ] guide: hatch vs plugin  
- [ ] Tests: discovery + invalidation  

## Non-goals

- Full monorepo trait system  
- Marketplace of hatch libs  

## Shipped

- Convention dir **`jk-build/`**; override with `[build].logic` / `[build].logic-main` (`logic = "off"` disables)
- `BuildLogicSupport` during copy-resources (action-cached)
- Sample `docs/features/examples/line-count-build/`
- `BuildLogicSupportTest` (convention, override, off, cache hit)
- Docs: [project-build-logic.md](../features/project-build-logic.md)
