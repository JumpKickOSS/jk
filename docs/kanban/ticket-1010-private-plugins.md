# ticket-1010 — Private plugins without a marketplace

**Priority:** P2 (enterprise adoption)  
**Status:** open  
**Refs:** [plugins.md](../plugins.md), [plugins.md](../plugins.md),
PRD “no marketplace before v2”

## Problem

SPI exists; enterprises need **vendor a jar + pin hash** before any public registry.

## Outcome

- `jk.toml` / workspace declaration of plugin jar path or coord + sha256
- Trust: refuse unsigned/unpinned remote load
- Docs: private plugin packaging checklist (copy spring-boot plugin shape)

## Acceptance

- [ ] External jar (outside monorepo) loads in a fixture project when pinned
- [ ] Missing/mismatched hash fails closed
