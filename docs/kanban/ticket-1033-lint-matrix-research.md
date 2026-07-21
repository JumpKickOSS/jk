# ticket-1033 — Lint / static analysis matrix (research → thin go-do)

**Priority:** P2  
**Status:** done  
**Kind:** research → thin go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §8  
**Depends on:** none (formatter plugin already exists for format, not lint)  
**Branch:** `ticket-1032-1038-1033-p2`  
**Estimate:** S research + S go-do (1–2 days total)  
**Refs:** `plugins/formatter` (`jk format`), mill-comparison lint table, plugin worker model

## Problem

Mill ships a **lint/static analysis matrix** (ErrorProne, Checkstyle, PMD, Detekt, ktlint, …).
JumpKick has **format** first-party; lint is missing, so “modern JVM shop” checklists look empty.

We will **not** clone Mill’s full contrib set. Pick a **thin, maintainable** first path.

## Research decision (filled)

| Language | Candidate | Integrate how? | Decision |
|---|---|---|---|
| Java | ErrorProne (javac plugin) | Engine/javac flags + dep | **Defer** — needs engine compile flag plumbing and toolchain coupling; high surface for first lint path |
| Java | SpotBugs | Fork worker / `jk tool` | **Defer** — heavier runtime; less common as first CI lint than style/static rules |
| Java | PMD | Fork worker / `jk tool` | **Defer** — overlaps Checkstyle for many shops |
| Java | **Checkstyle** | Documented **`jk tool install` + recipe** | **Chosen** — zero engine change; works with existing tool install / classpath launch; ubiquitous CI config |
| Kotlin | detekt | Plugin table / worker | **Defer** until demand after Java recipe; analysis ≠ format |
| Kotlin | ktlint | Overlaps `jk format` | **Defer** — style covered by formatter first-party |

### Why this lean default

1. **No new first-party plugin** until a tool proves itself via the recipe path.  
2. Checkstyle is pure Java, ships a main class, and matches “config file in repo” CI habits.  
3. ErrorProne is more powerful but is a **compiler** integration — wrong first ticket.  
4. Kotlin: keep `jk format` as the style path; detekt is analysis and can land as a second recipe later (same pattern as Checkstyle).

## Thin go-do (shipped)

1. Guide **Quality** section: format vs lint; Checkstyle recipe via `jk tool install`.  
2. Sample config + README under `docs/features/examples/checkstyle-recipe/`.  
3. Explicit Kotlin defer in that guide section.

## Acceptance

- [x] Decision table filled (Java chosen; Kotlin deferred)  
- [x] ≥1 Java lint/analysis path runnable on a fixture (recipe + sample config)  
- [x] ≥1 Kotlin path **or** explicit defer with rationale  
- [x] Guide snippet (how to run)  
- [x] Non-goal: Mill full matrix  

## Non-goals

- Full ErrorProne + Checkstyle + PMD + SpotBugs + detekt + ktlint all first-party  
- IDE inspection plugins  
- Replacing `jk format`  
