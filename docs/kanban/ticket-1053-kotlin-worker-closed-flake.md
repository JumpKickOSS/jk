# ticket-1053 — Kotlin worker “closed” flake (HiltTransformTest)

**Priority:** P3-infra  
**Status:** backlog  
**Kind:** go-do  
**Source:** Full-suite run during infra batch (2026-07); `HiltTransformTest` failed once with
`Diagnostic[step=compile-kotlin, code=exception, message=closed]`; clean re-run green  
**Depends on:** none  
**Branch:** `ticket-1053-kotlin-closed-flake`  
**Estimate:** S–M  

## Problem

Intermittent engine test failure when the Kotlin compile worker stream closes unexpectedly
(`message=closed`). Looks like a race on worker process lifecycle / stdout drain, not product
logic in Hilt.

## Goal

Reproduce under load if possible; harden worker JSONL / process teardown so mid-suite
`HiltTransformTest` (and similar) do not flake.

## Acceptance

- [ ] Failure mode understood (or documented as un-reproduced with mitigations)  
- [ ] Fix or retry/backoff where appropriate  
- [ ] `./gradlew :engine:test --tests '*HiltTransformTest*'` stable across N runs  

## Non-goals

- Rewriting the Kotlin compiler plugin  
