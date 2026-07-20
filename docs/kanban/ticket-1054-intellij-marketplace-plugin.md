# ticket-1054 — IntelliJ marketplace plugin (← 1017 Track B)

**Priority:** P2  
**Status:** backlog  
**Kind:** go-do  
**Source:** ticket-1017 Track B deferral  
**Depends on:** ticket-1017 Track A (**done**), BSP 1028/1041  
**Branch:** `ticket-1054-intellij-plugin`  
**Estimate:** L  

## Goal

IntelliJ plugin that imports JumpKick via BSP (`.bsp/jk.json` / `jk bsp serve`) and offers
sync/build actions without putting engine jars on the plugin classpath. Marketplace **or**
install-from-disk is enough for MVP.

## Acceptance

- [ ] Installable IntelliJ plugin (zip / marketplace)  
- [ ] Import/open project shows modules/classpath via BSP or thin process  
- [ ] Sync or build action with progress  
- [ ] Docs; same wire-only constraint as VS Code  

## Non-goals

- Full debug adapter / test gutter in v1  
- Bundling engine jars  
