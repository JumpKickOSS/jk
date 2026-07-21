# ticket-1002 — Solver package identity includes type/classifier

**Priority:** P0 (resolver fidelity)  
**Status:** done  
**Branch:** `ticket-1002-classifier-identity`  
**Refs:** [architecture.md](../architecture.md), `PackageId`, `MavenPackageSource`, `PubGrubResolver`,
`Lockfile.Artifact`

## Problem

PubGrub package keys were **`group:artifact` only**. Classifiers and non-jar types collapsed into
one version decision.

## What landed

- **`PackageId`** (`group:artifact:type:classifier`; default `g:a:jar:`) with bare-`g:a` parse for
  lock migration
- Solver roots, POM expand, dependsOn, KMP redirects use full package keys
- BOM soft-prefer and exclusions stay **GA-scoped**
- Lock rows write full keys; `Artifact.packageKey()` / `coordinate()` normalize legacy bare names
- Dual-classifier unit fixture (`ClassifierPackageIdentityTest`)
- Architecture note updated

## Acceptance

- [x] Two classifiers of the same GA lock as distinct rows/keys
- [x] Netty-style dual-classifier fixture (in-memory source)
- [x] `:resolver` / `:jk-api` tests green; bare `g:a` lock names still load
- [x] architecture.md resolution section updated
