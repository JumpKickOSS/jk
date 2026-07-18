# ticket-1009 — `jk explain` / why-rebuilt hero UX

**Priority:** P1 (product differentiation)  
**Status:** open  
**Refs:** [guide.md](../guide.md) §25 diagnostics, [guide.md](../guide.md), aliases `why-rebuilt` → `explain`

## Problem

Offline “what will run / why did this rebuild?” should be the signature DX vs Gradle build scans.
Explain exists; depth and discoverability may not match the PRD showcase.

## Outcome

- Clear cache hit/miss per step with input key diff
- Stable docs + help text; no lagging `why-rebuilt` as a separate promise

## Acceptance

- [ ] Fixture: change one source → explain shows only affected compile step dirty
- [ ] README or diagnostics section points to explain as the default for rebuild questions
