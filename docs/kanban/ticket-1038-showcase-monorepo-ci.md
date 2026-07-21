# ticket-1038 — Showcase monorepo CI dogfood

**Priority:** P2  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §10  
**Depends on:** soft — benefits from 1031 selectors / 1040 selective (both done)  
**Branch:** `ticket-1032-1038-1033-p2`  

## Acceptance

- [x] Multi-module sample committed (`docs/features/examples/workspace-showcase/`)  
- [x] CI job **Showcase monorepo (jk)** builds it with reinstalled thin client  
- [x] CONTRIBUTING documents the smoke  
- [x] Failure is visible in CI logs (no `continue-on-error`)  

## Notes

Workspace-root `jk test` does not cascade; CI uses `jk test --modules app`.
