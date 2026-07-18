# ticket-1011 — Windows engine transport field verification

**Priority:** P2  
**Status:** open  
**Refs:** [architecture.md](../architecture.md) Windows TCP + token; JVM tests force `os.name` only

## Problem

Loopback TCP + shared-secret auth is implemented but not proven on a real Windows host
(spawn detach / `javaw` notes — see architecture.md and git history).

## Outcome

- CI matrix job or documented manual checklist: start/status/stop, build, kill -9 recovery,
  Ctrl-C does not kill engine
- File bugs for any spawn-detach gaps

## Acceptance

- [ ] At least one real Windows run recorded (CI log or checklist sign-off in this ticket)
