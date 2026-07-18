# ticket-1013 — Affected-since workspace builds

**Priority:** P2  
**Status:** open / placeholder  
**Refs:** [guide.md](../guide.md) §13.2 `--affected-since`

## Intent

`jk build --affected-since=origin/main` using git diff + reverse module dep graph so monorepos
do not rebuild the world.

## Placeholder

- Depends on stable workspace scheduler + module graph edges
- CLI flag + tests with a synthetic git history or path-change stub
