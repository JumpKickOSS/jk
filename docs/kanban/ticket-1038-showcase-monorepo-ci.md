# ticket-1038 — Showcase monorepo CI dogfood

**Priority:** P2  
**Status:** ready  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §10  
**Depends on:** soft — benefits from 1031 selectors / 1040 selective (both done)  
**Branch:** `ticket-1038-showcase-ci`  
**Estimate:** M (1–2 days)  
**Refs:** `.github/workflows/ci.yml`, `../jk-examples` (if present), CONTRIBUTING reinstall path, AGENTS done criteria

## Problem

Mill’s trust story includes **checked-in third-party ports** and continuous demos. JumpKick has
architecture + dogfood of itself, but no small **public multi-module sample** that CI builds with
the **reinstalled** binary (the AGENTS “project smoke” bar for product claims).

## Goal

One in-repo (or sibling `jk-examples`) **multi-module** workspace that CI builds after:

```bash
./gradlew clean dist installLocal && ./install.sh build/dist/jk
jk engine status
cd <showcase> && jk lock && jk build && jk test
```

Optional stretch: `jk selective prepare --since=…` / `jk build --modules …` smoke.

## Shape of the sample

Minimal but real:

```text
showcase/   # or docs/features/examples/workspace-showcase/
  jk.toml                 # [workspace] modules = ["lib", "app"]
  lib/  jk.toml + src     # library
  app/  jk.toml + src     # depends on lib (workspace = true)
```

- Java 25, no exotic plugins required for the default CI path  
- Optional second job or matrix cell for Kotlin later — **not** required  

## Implementation plan

1. Choose location: prefer **in-repo** under `docs/features/examples/workspace-showcase/` (docs stay small) **or** `examples/workspace-showcase/` if that’s the convention. Avoid growing infinite docs — one folder + CONTRIBUTING pointer.  
2. Add CI job (or step on existing `ci.yml`) that:
   - builds/installs local jk (or uses already-built dist from prior job)  
   - runs lock/build/test on the showcase with **PATH pointing at install**  
   - fails the job on non-zero  
3. CONTRIBUTING: “Showcase smoke” subsection with the same commands humans use.  
4. Optional: assert `jk build --modules app` works.

## Acceptance

- [ ] Multi-module sample committed  
- [ ] CI job builds it with reinstalled (or installDist) `jk`, not only Gradle `:cli:test`  
- [ ] CONTRIBUTING documents the smoke  
- [ ] Failure is visible in CI logs (no `continue-on-error`)  

## Non-goals

- Netty/Mockito-scale third-party port  
- Full self-host of jk’s Gradle build  
- Publishing the sample as a separate product  
