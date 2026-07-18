# ticket-1011 — Windows engine transport field verification

**Priority:** P2  
**Status:** ready  
**Branch:** `ticket-1011-windows-engine-field`  
**Refs:** [architecture.md](../architecture.md) (TCP + token), `EngineTransport.useLoopbackTcp()`,
`EngineServer` auth, `EngineClient` spawn / detach, existing `EngineTcpTransportTest` (forces
`os.name` only)

## Problem

Loopback TCP + `{"t":"auth","token":…}` is implemented and unit-tested under a fake `os.name`.
It is **not** proven on a real Windows host (spawn, `javaw` detach, kill recovery, Ctrl-C).

## Scope

1. **Checklist** (record results in this ticket when run):
   - `jk engine start` / `status` / `stop`
   - `jk build` against a tiny project (engine path)
   - Kill engine process → next command respawns
   - Ctrl-C during build does not leave a wedged engine (or document if it does)
2. **CI:** add a Windows job **if** free GitHub Actions Windows minutes are acceptable; otherwise
   document manual sign-off and keep Linux TCP tests as the regression net.
3. File follow-up tickets only for real gaps found (spawn/detach), not speculative rewrites.

## Acceptance

- [ ] Checklist results pasted into this ticket (date + Windows version) **or** green Windows CI job
- [ ] Any spawn/auth bugs fixed with tests that do not require Windows where possible
- [ ] architecture.md one line if behavior differs from Unix (only if true)

## Out of scope

- Named-pipe transport rewrite
- Full Windows product polish (paths, installers) beyond engine transport
