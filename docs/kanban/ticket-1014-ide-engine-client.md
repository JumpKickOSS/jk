# ticket-1014 — IDE clients over engine protocol

**Priority:** P2 (v1.0 target area)  
**Status:** open / placeholder  
**Refs:** [architecture.md](../architecture.md), [architecture.md](../architecture.md),
[architecture.md](../architecture.md)

## Intent

IntelliJ / VS Code talk `jk-api` + engine wire protocol (sync, build events), not only
generated IDE files + shelling out to CLI.

## Placeholder

- Requires ticket-1001 protocol hygiene first
- Thin client: import progress, classpath sync, run configurations
