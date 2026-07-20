# ticket-1017 — Marketplace IDE plugins (IntelliJ / VS Code)

**Priority:** P2  
**Status:** done (Track A — VS Code MVP)  
**Kind:** go-do (phased MVP)  
**Source:** [mill-comparison.md](../mill-comparison.md) §7  
**Depends on:** 1014, 1028, 1041 (**done**)  
**Branch:** `ticket-1017-ide-marketplace`  

## Shipped — Track A (VS Code)

| Slice | Deliverable |
|---|---|
| A1 | Extension + `jk bsp install` command / auto-prompt for `.bsp/jk.json` |
| A2 | Commands + tasks: lock / sync / build / test / assemble via `jk` subprocess; status bar + output channel |
| A3 | `clients/vscode/README.md`, guide IDE section, architecture note, `./scripts/package-vscode.sh` → VSIX |

### Resolved open questions

1. **Spawn:** CLI subprocess (`jk …`); BSP via `.bsp/jk.json` → `jk bsp serve`. No embedded wire client in the extension host.  
2. **Engine skew:** user-installed `jk` on PATH (`jk.path` setting); extension does not bundle the engine.

## Acceptance (MVP = one track)

- [x] Installable artifact (VSIX via package script) for **VS Code**  
- [x] Fresh project: `jk bsp install` + open workspace → BSP connection file; tasks/commands without manual `.iml`  
- [x] Sync/build actions show progress (output channel + status bar) or clear failure  
- [x] Docs: guide + architecture IDE sequence; engine on PATH  
- [x] No server modules on plugin classpath (Node extension only)  

## Follow-up

- **Track B — IntelliJ** (register BSP/build system, marketplace or install-from-disk) — backlog when ready  
- Language server / debug adapter — non-goals  

## Non-goals (unchanged)

- Language server / semantic highlighting  
- Full debug adapter  
- Replacing `jk export idea|vscode`  
- Bundling Graal native image inside the plugin  
