# ticket-1031 — Module / task query selectors

**Priority:** P1-depth  
**Status:** done
 
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §3  
**Branch:** `ticket-mill-steal-p0-p1` (depth pass)  
**Refs:** workspace loader, `BuildCommand` module list, `AffectedSelection`

## Goal

```bash
jk build --modules api,worker
jk test --modules 'libs/*'
jk explain --modules {api,worker}
```

Documented glob/brace/list syntax. Precedence with `--affected-since`: **intersection** when both set.
Single-module projects: `--modules` matching the project name or `.` selects it; no match → error.

## Acceptance

- [x] build + test + explain honor selection  
- [x] empty match → clear error (non-zero)  
- [x] intersection with `--affected-since`  
- [x] guide + unit tests for selector parser  

## Shipped

- `ModuleSelection` in `shared/core` (comma / braces / globs)  
- `--modules` on `build`, `test`, `explain`, `selective`  
- Intersection with `--affected-since` via `resolveOptional`  


## Non-goals

- Full Mill `foo.test.compile` path UX (unless hatch needs it later)  

