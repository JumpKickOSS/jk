# ticket-1026 — Programmable escape hatch design (Mill-style, outside TOML)

**Priority:** P0  
**Status:** done  
**Kind:** research / design  
**Source:** [mill-comparison.md](../mill-comparison.md) §6  
**Branch:** `ticket-1026-escape-hatch-design`  
**Deliverable:** `docs/features/programmable-escape-hatch.md` (feature PRD)

## Problem

No designed way to add custom graph nodes without (a) first-party plugins or (b) TOML scripts
(anti-goal). Mill’s OO tasks are the bar for ergonomics.

## Hard constraints

1. **No executable code in `jk.toml`** — only data + references  
2. Prefer **Java/Kotlin** for hatch language  
3. Tasks are **pure graph nodes**: declared inputs/outputs, CAS/action-cache compatible  
4. Heavy isolation remains **out-of-process plugins**; hatch may call them  

## Research checklist (answer in the PRD)

- [ ] Discovery: path in TOML vs convention `build/jk/`  
- [ ] SPI: how hatch registers tasks/steps with the engine without loading arbitrary code into CLI  
- [ ] Override points: which first-party steps are spliceable (resources, package, custom)  
- [ ] Caching keys + sandbox dest  
- [ ] Workspace shared traits  
- [ ] IDE/BSP future (1028)  
- [ ] Security: project-local hatch vs path deps  

## Worked example (must appear in PRD)

Mill “lineCount → generated resource” as a JumpKick hatch sketch (API + `jk.toml` pointer only).

## Acceptance

- [ ] PRD merged under `docs/features/` + listed in `docs/features/README.md`  
- [ ] Explicit non-goals (no TOML scripts; no Gradle config graph)  
- [ ] MVP scope for ticket-1037 listed with acceptance bullets  
- [ ] Maintainer-ready freeze (this ticket done when PRD is the source of truth)  

## Non-goals

- Implementing hatch runtime (1037)  
