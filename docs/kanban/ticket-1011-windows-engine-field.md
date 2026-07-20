# ticket-1011 — Windows engine transport field verification

**Priority:** P2  
**Status:** done (checklist + regression net; real Windows host sign-off optional follow-up)  
**Branch:** `ticket-ready-batch`

## Scope delivered

Loopback TCP + `{"t":"auth","token":…}` is implemented and unit-tested under a forced
`os.name` (`EngineTcpTransportTest` and related). No free Windows CI matrix is required for
this ticket’s acceptance when a manual checklist exists.

## Field checklist (run on a real Windows host when available)

Date / Windows version: **JK-1073** — automated on `windows-latest` via
[`.github/workflows/ci-os-nightly.yml`](../../.github/workflows/ci-os-nightly.yml)
(`:engine:test` + `:cli:test` with real `os.name`, not forced). Manual paste still welcome.

| Check | Result |
|---|---|
| `jk engine start` / `status` / `stop` | Nightly: exercised via engine/cli wire tests on `windows-latest` |
| `jk build` tiny project (engine path) | Nightly: CLI/engine integration tests; thin install.ps1 local path |
| Kill engine process → next command respawns | Still manual (not in nightly scope) |
| Ctrl-C during build does not wedge engine | Still manual |

Linux TCP + auth tests remain the push-CI regression net; Windows nightly closes the
“forced os.name only” gap for transport tests.

## Acceptance

- [x] Checklist template in this ticket (results when hardware available)
- [x] Spawn/auth covered by non-Windows unit tests where possible
- [x] architecture.md documents TCP + token on Windows

## Follow-up

Paste checklist results when a Windows run is performed; open a bug ticket only for real gaps.
