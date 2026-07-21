# ticket-1050 — Timeline + watch polish (depth of 1023 / 1025)

**Priority:** P3  
**Status:** backlog  
**Kind:** go-do (small)  
**Source:** 1023 / 1025 non-goals and residual Mill UX  
**Depends on:** 1023, 1025 (done)  
**Branch:** `ticket-1050-timeline-watch-polish`  

## Problem

Core chrome timeline and `jk watch` shipped. Residual polish that Mill users notice:

| Area | Gap |
|---|---|
| Timeline | Discoverability (always print path? CI artifact tip); optional `--no-timeline`; OTEL still out |
| Timeline | Verify/fix parallel module `tid`/`pid` overlaps if any holes remain |
| Watch | Continuous test UI / richer TDD loop (1025 non-goal) |
| Watch | Debounce/config knobs, ignore globs documented |

## Goal

Pick a **thin** polish slice (do not expand into a rewrite):

1. Timeline: one-line “wrote timeline …” on build by default or under verbose; guide CI tip  
2. Watch: document ignore patterns; optional debounce flag if missing  
3. Tests only where behavior changes  

## Acceptance

- [ ] At least timeline discoverability **or** watch config polish landed  
- [ ] Guide updated  
- [ ] No OTEL unless explicitly expanded  

## Non-goals

- OpenTelemetry exporter (1023 non-goal unless product asks)  
- Full continuous testing dashboard  

## Refs

- `ChromeTimeline*`, `WatchCommand`, `SourceWatch`  
