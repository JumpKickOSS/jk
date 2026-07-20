# ticket-1051 — Engine job deadline / stream heartbeat (← 1043)

**Priority:** P2-infra  
**Status:** backlog  
**Kind:** go-do  
**Source:** ticket-1043 out-of-scope (client-side recovery shipped)  
**Depends on:** ticket-1043 (**done**)  
**Branch:** `ticket-1051-engine-job-deadline`  
**Estimate:** M  

## Problem

Ticket-1043 made the **client** displace silent peers and fail closed on stream idle.
Long builds that legitimately go quiet (no progress frames) still rely on a high
`JK_STREAM_IDLE_MS` default. A wedged **in-engine** job can hold a connection open until
that idle timeout without emitting an ERROR frame.

## Goal

Engine-side bounds so a stuck pipeline cannot sit silent forever:

1. Optional **per-request wall deadline** (config/env) that aborts with ERROR.  
2. Or lightweight **heartbeat / progress lines** on long compile/test so client idle is meaningful at a shorter default.  
3. Document interaction with `JK_STREAM_IDLE_MS`.

## Acceptance

- [ ] Wedged in-engine job surfaces ERROR (or heartbeat keeps idle honest) within a tunable bound  
- [ ] Huge monorepo builds still succeed with defaults or documented knobs  
- [ ] Tests for deadline/heartbeat path  
- [ ] Architecture liveness table updated  

## Non-goals

- External supervisor process  
- Changing resident-engine model  
