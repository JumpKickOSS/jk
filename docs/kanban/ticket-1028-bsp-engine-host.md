# ticket-1028 — BSP server / packagable IDE host

**Priority:** P1  
**Status:** done  
**Kind:** research → MVP  
**Source:** [mill-comparison.md](../mill-comparison.md) §7  
**Depends on:** ticket-1014 (done)  
**Branch:** `ticket-1028-bsp-host`  
**Refs:** `IdeEngineClient`, wire verbs project-info / sync / build / ide-model

## Problem

No BSP → IDEs cannot import JumpKick projects without shelling `jk` or marketplace plugins
reimplementing hosts. Layering must stay wire-only (1020).

## Phase A — design (in-ticket section or short architecture addendum)

Map BSP build targets / compile / dependency modules → engine wire. Lifecycle: who spawns engine.

## Phase B — MVP

- `jk bsp` or `jk --bsp-install` writes `.bsp/jk.json` launcher  
- BSP server process (can live in `clients/cli` or small module) speaks BSP stdio/socket  
- Enough for: list targets, compile one Java module, expose dependency jars from `ideModel()`  
- Tests: protocol smoke with mock client **or** recorded handshake  

## Acceptance

- [ ] Design mapping documented (architecture or ticket appendix when merged)  
- [ ] `.bsp` config install works for sample project  
- [ ] At least one compile path via BSP or IdeEngineClient-backed server  
- [ ] No server jars on IDE classpath  
- [ ] Docs: architecture IDE sequence updated  

## Non-goals

- Marketplace packaging (1017)  
- Full Metals/Scala  
- Language server  

## Shipped

- `jk bsp install` → `.bsp/jk.json`
- `jk bsp serve` → minimal BSP JSON-RPC over stdio via `IdeEngineClient`
- architecture + guide notes; framing smoke test
