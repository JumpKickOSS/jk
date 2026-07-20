# ticket-1051 — Engine job deadline / stream heartbeat (← 1043)

**Priority:** P2-infra  
**Status:** done  
**Kind:** go-do  
**Depends on:** ticket-1043 (**done**)  

## Acceptance

- [x] Wedged in-engine job can be capped: `JK_ENGINE_JOB_DEADLINE_MS` → cancel + `error` code `deadline`  
- [x] Heartbeats while async jobs run: `JK_ENGINE_HEARTBEAT_MS` (default 30s) → wire `heartbeat` lines  
- [x] Tests for heartbeat JSON + defaults (`JobWatchdogConfigTest`)  
- [x] Architecture liveness table updated  

## Knobs

| Env | Default | Meaning |
|---|---|---|
| `JK_ENGINE_HEARTBEAT_MS` | `30000` | Interval; `0` disables |
| `JK_ENGINE_JOB_DEADLINE_MS` | `0` | Wall cap; `0` = off (huge monorepos) |
| `JK_STREAM_IDLE_MS` | 60m | Client idle; heartbeats reset it |
