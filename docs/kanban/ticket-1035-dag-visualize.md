# ticket-1035 — DAG visualize (DOT)

**Priority:** P3  
**Status:** backlog (refined; not ready until P2 packaging/showcase pressure eases)  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §3  
**Depends on:** soft — `ExplainPlan` / workspace forecast edges already exist  
**Branch:** `ticket-1035-dag-visualize`  
**Estimate:** S–M (1 day)  
**Refs:** `ExplainCommand`, `ExplainPlan`, `BuildPlan`, workspace module edges (`ModuleOrder` / `AffectedModules.edgesFor`)

## Problem

Monorepo power users debug “why did X rebuild” with graphs. Mill has `visualize`. JumpKick has
`jk explain` (text) and selectors, but no **machine graph** for external tools (Graphviz, docs).

## Goal

Export the **module dependency DAG** (and optionally step-level later) as **DOT**:

```bash
jk explain --graph dot > modules.dot
# or
jk graph --format dot
```

Prefer extending `explain` to avoid a new verb unless UX is clearer as `jk graph`.

## Design

1. **Nodes:** workspace modules (coord or path label).  
2. **Edges:** same prereq rules as workspace build order (`ModuleOrder` / `AffectedModules.edgesFor`).  
3. **Filter:** honor `--modules` / `--affected-since` when present (1031/1027).  
4. **Output:** stdout DOT; `--output` file optional.  
5. **No** Graphviz binary required in jk; users run `dot -Tsvg` externally.

## Acceptance

- [ ] Multi-module fixture → DOT with expected edge(s)  
- [ ] Empty workspace / single module: valid trivial graph  
- [ ] Docs: one guide line + example `dot -Tsvg`  
- [ ] Unit test on DOT string (no graphviz install in CI)  

## Non-goals

- Interactive web UI  
- Shipping graphviz native binary  
- Full Mill task-level `foo.compile` graph (module-level is enough)  
- SVG generation inside jk  

## Ready criteria (when to promote)

- P2 packaging (1032) and showcase (1038) not blocking product narrative  
- Explain forecast API stable enough to hang export on  
