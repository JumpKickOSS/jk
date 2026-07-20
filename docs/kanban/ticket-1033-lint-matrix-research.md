# ticket-1033 — Lint / static analysis matrix (research → thin go-do)

**Priority:** P2  
**Status:** ready  
**Kind:** research → thin go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §8  
**Depends on:** none (formatter plugin already exists for format, not lint)  
**Branch:** `ticket-1033-lint-matrix`  
**Estimate:** S research + S go-do (1–2 days total)  
**Refs:** `plugins/formatter` (`jk format`), mill-comparison lint table, plugin worker model

## Problem

Mill ships a **lint/static analysis matrix** (ErrorProne, Checkstyle, PMD, Detekt, ktlint, …).
JumpKick has **format** first-party; lint is missing, so “modern JVM shop” checklists look empty.

We will **not** clone Mill’s full contrib set. Pick a **thin, maintainable** first path.

## Research (must complete before merge)

### Decision table (fill in ticket before coding)

| Language | Candidate | Integrate how? | Decision |
|---|---|---|---|
| Java | ErrorProne (javac plugin) | Engine/javac flags + dep | **pending** |
| Java | SpotBugs / PMD / Checkstyle | Fork worker or `jkx` recipe | **pending** |
| Kotlin | detekt | Plugin table / worker | **pending** |
| Kotlin | ktlint | Overlaps format; lint rules differ | **pending** |

**Recommended lean default (validate in research write-up):**

1. **Java:** Checkstyle **or** SpotBugs as a **documented `jkx` / tool recipe** first (no new first-party plugin unless integration is &lt;1 day). Prefer whatever already fits `jk tool` / plugin-worker patterns with least engine change.  
2. **Kotlin:** **detekt** via documented recipe **or** explicit **defer** with rationale (formatter covers style; detekt is analysis).  
3. ErrorProne only if javac integration is already almost free — do not boil the ocean.

### Deliverable of research half

A short section in this ticket (or `docs/features/` only if it earns a permanent page — prefer **ticket body + guide snippet** to avoid doc sprawl):

- Chosen Java path + command to run  
- Kotlin: path or “deferred until …”  
- Why not the others (one line each)

## Thin go-do (after decision)

1. One runnable path from a sample project (script, `jk tool run`, or first-party opt-in table).  
2. Guide snippet under “Quality” / format section.  
3. CI optional: not required for ticket done (showcase CI is 1038).

## Acceptance

- [ ] Decision table filled (Java chosen; Kotlin chosen or deferred)  
- [ ] ≥1 Java lint/analysis path runnable on a fixture  
- [ ] ≥1 Kotlin path **or** explicit defer with rationale  
- [ ] Guide snippet (how to run)  
- [ ] Non-goal: Mill full matrix  

## Non-goals

- Full ErrorProne + Checkstyle + PMD + SpotBugs + detekt + ktlint all first-party  
- IDE inspection plugins  
- Replacing `jk format`  
