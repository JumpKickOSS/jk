# ticket-1008 — Gradle version catalog import

**Priority:** P1 (migration)  
**Status:** open  
**Refs:** [guide.md](../guide.md) import tiers, gradle import honesty in README

## Problem

Modern Gradle projects put coordinates in `gradle/libs.versions.toml`. String-level
`build.gradle.kts` import misses most deps.

## Outcome

`jk import` (or a dedicated path) reads version catalogs into `[dependencies]` / workspace deps
with tiered fidelity notes.

## Acceptance

- [ ] Fixture catalog → `jk.toml` deps for `library` aliases
- [ ] Unresolved catalog refs reported, not silently dropped
- [ ] Docs: still no Groovy/Kotlin script evaluation
