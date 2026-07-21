# ticket-1025 — `jk watch` (+ `dev` = `watch run`)

**Priority:** P0  
**Status:** done  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §3, §11  
**Branch:** `ticket-mill-steal-p0-p1`

## Product model

One live-loop family — **not** two competing commands:

```bash
jk watch compile | test | build | run
jk dev   # alias for: jk watch run
```

| Piece | Code |
|---|---|
| FS watch + debounce | `cli.watch.SourceWatch` |
| App reload / device | `cli.watch.AppWatchLoop` |
| Verb dispatch | `WatchCommand` |
| Short name | `DevCommand` → delegates to `watch run` |

## Acceptance

- [x] Verb loops for compile / test / build  
- [x] `watch run` carries former `jk dev` behavior  
- [x] Single watch implementation (no duplicate WatchService stack)  
- [x] Guide documents the unified model  

## Non-goals

- Continuous test UI  
