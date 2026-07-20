# ticket-1034 — `jk outdated` polish (Mill showUpdates parity)

**Priority:** P2  
**Status:** ready  
**Kind:** go-do (polish — **command already exists**)  
**Source:** [mill-comparison.md](../mill-comparison.md) §9  
**Depends on:** none  
**Branch:** `ticket-1034-outdated`  
**Estimate:** S (0.5–1 day)  
**Refs:** `OutdatedCommand`, engine outdated report / pipelines, `jk update`, `jk tree` / `jk why`

## Problem

`jk outdated` already reports Current / Compatible / Latest (workspace cascade, `--show-tip`,
`--exclude-up-to-date`). Remaining gap vs Mill’s polished `Dependency/showUpdates` is **product
surface**, not a rewrite:

| Gap | Today | Target |
|---|---|---|
| Discoverability | Buried in command list | Guide next to `jk update`; README one-liner if missing |
| Machine output | Human table | Document / ensure global `--output json` (or dedicated) emits stable schema |
| Cross-links | None | Result lines or footer: “see `jk why <coord>` / `jk tree`” |
| Exit semantics | Unclear | Document: 0 = success (whether or not rows); optional `--fail-if-outdated` for CI |
| Offline | May be murky | Clear message when offline / no metadata |

## Goal

Make `jk outdated` the obvious first step in the **lockfile-respecting update workflow**:

```bash
jk outdated                 # what can move
jk outdated --exclude-up-to-date
jk update                   # only after review (lockfile law)
```

## Implementation notes

1. Read `OutdatedCommand` + report DTO; inventory JSON path via `GlobalOptions.outputIsJson()`.  
2. If JSON missing or unstable: add a minimal schema `{ modules: [{ name, deps: [{ coord, current, compatible, latest, tip? }] }] }`.  
3. Guide: short “Check for updates” under dependencies; CI tip for `--fail-if-outdated` **only if** flag is added (optional; skip flag if exit-code bikeshed — prefer docs-only for v1).  
4. One integration/fixture test: project with deliberately old pin → row appears; up-to-date project → empty message.

## Acceptance

- [ ] Guide section: outdated → update workflow (and offline note)  
- [ ] Machine-readable output documented and covered by a test **or** proven already via existing JSON path  
- [ ] Human output points at `jk why` / `jk tree` for at least one row style (footer OK)  
- [ ] Smoke/fixture test green  
- [ ] README / features list mentions `outdated` if public claims list deps tooling  

## Non-goals

- Auto-PR bots, Dependabot clone  
- Changing PubGrub / re-resolve during `outdated`  
- BOM publishing modules  
