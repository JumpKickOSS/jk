# ticket-1017 — Marketplace IDE plugins (IntelliJ / VS Code)

**Priority:** P2 (after 1014 facade)  
**Status:** backlog  
**Depends on:** ticket-1014 (IdeEngineClient — **done**)  
**Branch:** `ticket-1017-ide-marketplace-plugins`

## Problem

ticket-1014 delivers an engine-backed Java facade with progress callbacks. Shipping user-facing
IDE plugins (JetBrains Marketplace / VS Code Marketplace) is a separate product and packaging
effort.

## Scope (when refined)

- IntelliJ plugin: project import, sync action, build tool window wired to
  `IdeEngineClient`, run configurations
- VS Code extension: tasks, status bar sync, optional debug adapter later
- Do **not** re-implement classpath math client-side — call the facade / wire model

## Acceptance (draft)

- [ ] Published or installable plugin that connects to a local engine without shelling `jk`
- [ ] Sync progress visible in the IDE UI
- [ ] Docs link from guide → architecture IDE sequence

## Post-1020 note

Client is wire-only (`IdeEngineClient` → `EngineClient`). Marketplace plugins must spawn/connect
a real engine (or use a versioned `jk-engine.jar`), never link server modules. Prefer process
lifecycle owned by the IDE plugin (start on project open, stop on close) with UDS/TCP as today.

## Out of scope

- Language server / semantic highlighting
- Replacing `jk ide` file export entirely
- Bundling the engine into the IDE plugin classpath (anti-goal; ticket-1020)
