# ticket-1032 — Assembly packaging depth + R8 productization

**Priority:** P2  
**Status:** done  
**Kind:** go-do + evaluation (decision already drafted)  
**Source:** [mill-comparison.md](../mill-comparison.md) §8  
**Depends on:** none (uses existing `package-assembly`, `plugins/shrink`)  
**Branch:** `ticket-1032-1038-1033-p2`  
**Estimate:** M–L (2–4 days)  

## Acceptance

- [x] Guide packaging matrix (thin / fat / shrink / boot)  
- [x] Fat jar path documented as one obvious command or `assembly` flag (`jk assembly`)  
- [x] ≥1 merge + ≥1 exclude rule with tests (services + Spring META-INF merge; signatures + module-info exclude)  
- [x] Shrink sample + discoverability polish  
- [x] Existing shrink tests still green; no R8-by-default  

## Shipped

- `AssemblyPackager`: merge `spring.handlers` / `schemas` / `factories` / Boot auto-config imports; exclude `module-info.class`  
- `jk assembly` (+ aliases `fat-jar`, `shadow`) — requires `assembly = true`  
- `docs/features/packaging.md` + assembly-app / shrunk-cli samples  
- Product key is **`assembly`** (not shadow-jar); CLI `jk assembly` / `jk assemble`  

- Guide packaging section  

## Non-goals (unchanged)

- jlink / jpackage, R8 by default, full Shadow DSL, replacing Spring Boot repackage  
