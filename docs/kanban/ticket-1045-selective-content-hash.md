# ticket-1045 — Selective content-hash prepare (depth of 1027 / 1040)

**Priority:** P3  
**Status:** backlog  
**Kind:** go-do  
**Source:** mill-comparison §4; 1027 / 1040 non-goals  
**Depends on:** 1040 (done)  
**Branch:** `ticket-1045-selective-content-hash`  

## Problem

`jk selective prepare/resolve/run` is **git-ref + module list** only. Mill’s selective layer can
snapshot **task inputs** so CI re-runs only when content (not just file path set) changed, and
documents determinism pitfalls (`selectiveInputs`, VCS version tasks, path identity).

## Goal

1. **Prepare** records a content fingerprint per selected module (or declared inputs) under
   `.jk/selective-plan.json` (or sibling file).  
2. **Run** (or `resolve --changed`) compares fingerprints and skips modules whose inputs match.  
3. Docs: determinism caveats (absolute paths in fingerprints, generated always-dirty files, git
   version stamping).  

## Acceptance

- [ ] Content-hash prepare + “nothing changed” skip path with test  
- [ ] Git-ref mode still works (intersection or explicit flag)  
- [ ] Guide CI section updated  
- [ ] Explicit non-support list for Mill features we still skip  

## Non-goals

- Full Mill `selectiveInputs` override DSL on every task  
- Remote cache / RBE handoff of build artifacts between agents  

## Refs

- `SelectiveCommand`, `AffectedSelection`, `ModuleSelection`  
