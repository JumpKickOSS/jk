# ticket-1029 — Incremental compile contracts + why-recompiled

**Priority:** P1  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §2  
**Branch:** `ticket-1029-incremental-contracts`  
**Refs:** `JavaIncrementalCompile`, `ClassAbi`, `JavaIncrementalCompilerTest`, `FreshnessStamp`

## Problem

ABI incremental exists but lacks Mill-style **documented contracts** and user-facing
“why this file is dirty.”

## Goal

1. Contract tests (body change vs public API change recompile sets)  
2. Diagnostic hook: step message or explain line for dirty reason when available  
3. One-paragraph guide: what we guarantee / don’t  

## Acceptance

- [ ] Test: body-only edit recompiles strictly fewer compilation units than adding a public method  
- [ ] User-visible or machine-readable dirty reason for ≥1 path (e.g. verbose compile / explain)  
- [ ] Doc snippet in guide or architecture  
- [ ] Decision note in ticket: stay on ABI path (default) vs open Zinc spike  

## Non-goals

- Zinc integration in this ticket  
- Kotlin-only contracts (follow-up OK if free)  
