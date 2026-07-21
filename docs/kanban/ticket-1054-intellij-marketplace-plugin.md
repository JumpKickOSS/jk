# ticket-1054 — IntelliJ marketplace plugin (← 1017 Track B)

**Priority:** P2  
**Status:** done (install-from-disk MVP)  
**Kind:** go-do  
**Depends on:** ticket-1017 Track A (**done**), BSP 1028/1041  

## Shipped

- `clients/intellij/` — Tools → JumpKick actions (BSP install, lock, sync, build, test)  
- Wire-only: `Process`/`OSProcessHandler` → `jk` on PATH (`JK_BIN` override)  
- `./scripts/package-intellij.sh` → `build/distributions/jumpkick-intellij-*.zip`  
- README + guide/architecture/CONTRIBUTING  

## Acceptance

- [x] Installable IntelliJ plugin zip  
- [x] BSP install action for import (JetBrains BSP client for classpath)  
- [x] Sync/build with progress indicator + balloon  
- [x] Docs; no engine jars on plugin classpath  

## Non-goals (unchanged)

- Full debug adapter / test gutter  
- Bundling engine jars  
- JetBrains Marketplace publish (install-from-disk is enough for MVP)  
