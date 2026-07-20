# ticket-1046 — Incremental compile: Zinc decision + Kotlin contracts

**Priority:** P3  
**Status:** backlog  
**Kind:** research → optional go-do  
**Source:** mill-comparison §2; ticket-1029 non-goals  
**Depends on:** 1029 (done — ABI contracts)  
**Branch:** `ticket-1046-incremental-zinc`  

## Problem

1029 documented and tested **Java ABI** incremental contracts. Mill still leads on:

- **Zinc** (battle-tested analysis for Java/Scala)  
- **Kotlin** incremental quality / Build Tools API parity  
- Richer **why-this-file-recompiled** UX for multi-module / Kotlin  

## Goal

1. **Research write-up** (in-ticket): stay on ABI vs open Zinc (or Zinc-compatible analysis) —
   cost, Graal/native engine constraints, Kotlin interaction.  
2. If stay-ABI: Kotlin contract tests analogous to 1029 (body vs public API) **or** explicit defer.  
3. If Zinc: spike acceptance + follow-on implementation ticket.  
4. Optional polish: surface dirty-reason in `jk explain --verbose` for more than one path.  

## Acceptance

- [ ] Decision recorded (ABI forever / Zinc spike / hybrid)  
- [ ] Kotlin contracts **or** written defer with rationale  
- [ ] Guide incremental section updated  

## Non-goals

- Full Scala Zinc productization  
- Replacing action cache  

## Refs

- `JavaIncrementalCompile`, `ClassAbi`, `ClassAbiContractTest`, kotlin-compiler plugin  
