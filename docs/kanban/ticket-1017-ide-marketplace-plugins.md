# ticket-1017 — Marketplace IDE plugins (IntelliJ / VS Code)

**Priority:** P2  
**Status:** backlog → **ready** (refined; after 1014 + 1028 + 1041)  
**Kind:** go-do (phased MVP)  
**Source:** [mill-comparison.md](../mill-comparison.md) §7  
**Depends on:**
- ticket-1014 IdeEngineClient (**done**)  
- ticket-1028 BSP host (**done**)  
- ticket-1041 BSP import reliability (**done**)  
**Branch:** `ticket-1017-ide-marketplace`  
**Estimate:** L (multi-day; ship one IDE first)

## Problem

Wire facade + BSP exist, but users still cannot **install a marketplace plugin** and get
JumpKick import/sync/build without shelling `jk` or hand-editing IDE files.

## Product constraint (hard)

- Plugins are **wire-only clients** (ticket-1020).  
- **Never** put engine/server jars on the IDE plugin classpath.  
- Lifecycle: plugin starts/connects engine (or relies on `jk bsp serve` / user-installed engine).  
- Prefer BSP for import where the IDE already speaks BSP; use `IdeEngineClient` for richer UX only inside our plugin code that still talks wire/BSP, not server modules.

## Phased MVP (pick **one** first track in implementation PR)

### Track A — VS Code (recommended first)

| Slice | Deliverable |
|---|---|
| A1 | Extension packages `.bsp/jk.json` install (`jk bsp install`) + documents open-with-BSP |
| A2 | Status bar / task: sync or build via `jk` subprocess **or** BSP compile (document choice) |
| A3 | README + guide link |

### Track B — IntelliJ

| Slice | Deliverable |
|---|---|
| B1 | Plugin registers JumpKick as build system or BSP-backed import |
| B2 | Sync/build action with progress (BSP or thin process) |
| B3 | Marketplace **or** install-from-disk instructions (publishing can be manual) |

**Default recommendation:** Track A first (faster BSP reuse, less UI surface). Track B follows once A proves the lifecycle story.

## Acceptance (MVP = one track complete)

- [ ] Installable artifact (VSIX or IntelliJ plugin zip) for **one** IDE  
- [ ] Fresh project: import or open → targets/classpath visible without manual `.iml` editing  
- [ ] Sync or build action shows progress or clear failure  
- [ ] Docs: guide → architecture IDE sequence; “engine must be installable / on PATH”  
- [ ] No server modules on plugin classpath (architecture review checklist)  

## Non-goals

- Language server / semantic highlighting  
- Full debug adapter  
- Replacing `jk export idea|vscode` entirely (export remains fallback)  
- Bundling Graal native image inside the plugin  

## Open questions (resolve in PR description)

1. Does the plugin spawn `jk bsp serve` or an embedded wire client?  
2. Who owns engine version skew (plugin pins min `jk` version)?  
