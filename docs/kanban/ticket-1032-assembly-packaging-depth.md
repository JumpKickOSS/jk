# ticket-1032 — Assembly packaging depth + R8 productization

**Priority:** P2  
**Status:** ready  
**Kind:** go-do + evaluation (decision already drafted)  
**Source:** [mill-comparison.md](../mill-comparison.md) §8  
**Depends on:** none (uses existing `package-shadow`, `plugins/shrink`)  
**Branch:** `ticket-1032-assembly`  
**Estimate:** M–L (2–4 days)  
**Refs:**
- `[application] shadow-jar = true` → `StepNames.PACKAGE_SHADOW` / `BuildPipelines`  
- `plugins/shrink` — R8 `--classfile` via `[shrink]` (`ShrunkJarPackager`)  
- Spring Boot packager (separate; do not subsume)

## Problem

Mill’s `.assembly` is the obvious “one fat jar” story. JumpKick **already has**:

1. **Shadow / fat jar** — `shadow-jar = true` → `package-shadow`  
2. **R8 shrink** — opt-in `[shrink]` (documented in README claims)

Gaps are **discoverability, merge/exclude rules, and a packaging matrix**:

| User need | Today | Gap |
|---|---|---|
| Runnable fat jar | `shadow-jar = true` + `jk build` | No Mill-like merge rules (services, META-INF) |
| Smaller CLI jar | `[shrink]` | Low discoverability; no hero sample |
| Boot fat jar | spring-boot plugin | Must stay distinct |
| Mental model | Three paths, under-documented | Need a 1-page matrix |

## Product decision (locked for this ticket)

**B + CLI sugar (from prior eval):**

| Artifact | How | R8? |
|---|---|---|
| Thin jar | default `jk build` | no |
| Fat / assembly jar | `shadow-jar = true` **and** deepen merge rules; optional `jk assembly` alias that sets/docs the same path | no |
| Shrunk jar | `[shrink]` table | yes (keep-rules user-owned) |
| Spring Boot | spring-boot plugin | plugin-owned |

- Do **not** enable R8 by default.  
- Do **not** replace Spring Boot packaging.  
- Optional later: `[assembly] shrink = true` as alias → not required in this ticket.

## Implementation plan

### Phase A — Docs matrix (do first, land even if code slips)

Document in guide (or features short page if needed):

```text
thin jar  → default package-jar
fat jar   → [application] shadow-jar = true   (alias: jk assembly if shipped)
shrunk    → [shrink] …                        (R8; size report)
boot jar  → [plugins] spring-boot / plugin table
```

### Phase B — Assembly rules

1. Inventory current shadow packager merge behavior.  
2. Add ≥1 **merge** rule and ≥1 **exclude** (e.g. merge `META-INF/services/*`, exclude `META-INF/*.SF`).  
3. Config surface: prefer **data in `jk.toml`** under existing application/shadow table or a small `[assembly]` table that only configures the shadow step — **no scripts**.  
4. Tests: fixture jar contents assert merge/exclude.

### Phase C — Shrink productization

1. Sample under `docs/features/examples/` or `examples/shrunk-cli` (tiny main + `[shrink]`).  
2. `jk build` / package help or guide callout: “smaller artifact → `[shrink]`”.  
3. Confirm size before→after still surfaces; mention in build summary if cheap.

### Phase D — Optional CLI sugar

`jk assembly` = `jk build` with shadow semantics (or a thin wrapper that errors if `shadow-jar` not set, with a one-line fix). Only if it doesn’t fork a second packaging graph.

## Acceptance

- [ ] Guide packaging matrix (thin / fat / shrink / boot)  
- [ ] Fat jar path documented as one obvious command or `shadow-jar` flag  
- [ ] ≥1 merge + ≥1 exclude rule with tests  
- [ ] Shrink sample + discoverability polish  
- [ ] Existing shrink tests still green; no R8-by-default  

## Non-goals

- jlink / jpackage  
- Obfuscation by default  
- Full Gradle Shadow plugin rule DSL  
- Replacing Spring Boot repackage  
