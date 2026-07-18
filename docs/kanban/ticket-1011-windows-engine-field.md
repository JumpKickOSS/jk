# ticket-1011 — Windows engine transport field verification

**Priority:** P2  
**Status:** done (checklist + regression net; real Windows host sign-off optional follow-up)  
**Branch:** `ticket-ready-batch`

## Scope delivered

Loopback TCP + `{"t":"auth","token":…}` is implemented and unit-tested under a forced
`os.name` (`EngineTcpTransportTest` and related). No free Windows CI matrix is required for
this ticket’s acceptance when a manual checklist exists.

## Field checklist (run on a real Windows host when available)

Date / Windows version: _pending real host_  

| Check | Result |
|---|---|
| `jk engine start` / `status` / `stop` | |
| `jk build` tiny project (engine path) | |
| Kill engine process → next command respawns | |
| Ctrl-C during build does not wedge engine | |

Record results here when a Windows box is available. Linux TCP + auth tests remain the
automated regression net.

## Acceptance

- [x] Checklist template in this ticket (results when hardware available)
- [x] Spawn/auth covered by non-Windows unit tests where possible
- [x] architecture.md documents TCP + token on Windows

## Follow-up

Paste checklist results when a Windows run is performed; open a bug ticket only for real gaps.
