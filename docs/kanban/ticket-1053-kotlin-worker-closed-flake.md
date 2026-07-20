# ticket-1053 — Kotlin worker “closed” flake (HiltTransformTest)

**Priority:** P1-infra  
**Status:** done  
**Kind:** go-do  
**Source:** Full-suite flake: `Diagnostic[step=compile-kotlin, code=exception, message=closed]`  

## Failure mode

| Field | Value |
|---|---|
| Step | `compile-kotlin` |
| Code | `exception` (Pipeline synthesizes when step throws without `ctx.error`) |
| Message | bare `closed` (pipe / stream closed mid-worker) |

## Shipped

1. **PluginProcess** — pipe-closed detection; prefer `waitFor` exit / wrap with exit code context  
2. **KotlincDriver** — one retry on pipe-closed IOException  
3. **Pipeline.diagnosticMessage** — append `(ExceptionClass)` for bare `closed` / stream closed  

## Acceptance

- [x] Bare `message=closed` enriched with class name  
- [x] Retry / clearer failure path for worker pipe close  
- [x] Unit tests for `isPipeClosed` + diagnostic message  
