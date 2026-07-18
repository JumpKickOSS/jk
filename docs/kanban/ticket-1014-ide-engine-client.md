# ticket-1014 — IDE clients over engine protocol

**Priority:** P2 (v1.0 target area)  
**Status:** ready  
**Branch:** `ticket-1014-ide-engine-client`  
**Refs:** [architecture.md](../architecture.md) (client/engine + wire freeze, extension surface),
[guide.md](../guide.md) (`jk ide` / export), `IdeCommand` / `IdeGenerator` (file generation),
`EngineProtocol` (sync, build, project-info, explain), ticket-1001 (wire freeze — **done**)

## Problem

Today IDEs get **generated project files** (`jk ide`, `jk export idea|vscode`) and optionally
shell out to the CLI. That cannot stream import progress, live classpath sync, or build events
the way a real engine client can. IntelliJ / VS Code should eventually speak **`jk-api` + engine
wire** (JSONL over UDS/TCP), not only files + shell.

## Goal (phased)

### Phase 1 — MVP (this ticket): engine-backed IDE **sync protocol surface**

Not a full marketplace IDE plugin. Deliver a **minimal IDE-oriented client library / facade**
and wire events that an IDE plugin (or a thin prototype) can call without forking `jk` for every
sync:

1. **Connect** — reuse `EngineClient` / transport (UDS; TCP+auth on Windows)
2. **Project model** — `project-info` (and workspace module list) for open project
3. **Classpath / sync** — trigger lock+sync (or dedicated sync request if already present) and
   surface progress events the IDE can map to a progress bar
4. **Build events** — subscribe to build plan/step finish sufficiently to show status (reuse
   existing `build-request` event stream; do not invent a parallel protocol)
5. **Document** the IDE integration sequence in architecture.md (short subsection) or plugins/guide
   cross-link — **no** new top-level product doc

Optional stretch (same PR if small): stub IntelliJ or VS Code extension that only connects and
shows engine status / module list (can live under `clients/` or `contrib/` — prefer not blocking
MVP on marketplace packaging).

### Phase 2 — follow-up tickets

- Full IntelliJ plugin (run configurations, debug, test gutter)
- VS Code extension with tasks/debug
- Incremental classpath push without full re-export of `.iml` / `.classpath`

## Current assets to reuse

| Asset | Role |
|---|---|
| `IdeCommand` + generators | Keep as **file export** path; IDE client complements it |
| `EngineProtocol` project-info, lock, sync, build, explain | Hosted verbs already frozen (1001) |
| `EngineClient` (native CLI) | Pattern for IDE-side Java/Kotlin client |
| `shared/wire` | Shared types; IDE client should depend on wire + thin client jars only |

## Scope

**In (MVP)**

- Java API (or clearly named facade in `shared/` / `clients/`) for: connect, project-info, sync with
  progress callbacks, build with event callbacks
- Tests: fake or real local engine fixture (cli-engine / engine tests) proving event callbacks fire
- Docs: integration sequence + “file generation remains for offline/export”

**Out (MVP)**

- Shipping to JetBrains Marketplace / VS Code Marketplace
- Replacing `jk ide` file generation
- Language server / semantic highlighting
- Debug adapter protocol

## Dependencies

- **Hard:** ticket-1001 wire freeze — **done**
- Soft: 1011 Windows field (TCP) for Windows IDE hosts; Linux UDS is enough for MVP tests

## Acceptance

- [ ] Documented sequence: IDE → engine connect → project-info → sync → (optional) build events
- [ ] Library/facade API used by at least one automated test with a running or in-process engine
- [ ] Progress/status visible via callbacks (not only by parsing CLI stdout)
- [ ] No new public wire message types unless absolutely necessary; if added, extend freeze notes
- [ ] `./gradlew test` green for modules touched

## Non-goals / risks

- Do not pull `server/engine` into the IDE process — engine stays out-of-process
- Avoid duplicating lockfile parse in the IDE; prefer engine answers for classpath truth
- Keep thin-client classpath: `jk-api`, `wire`, client-io as needed — not resolver/toolchain
