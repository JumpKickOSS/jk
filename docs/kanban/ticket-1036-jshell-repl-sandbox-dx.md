# ticket-1036 — jshell / REPL + test sandbox DX

**Priority:** P3  
**Status:** backlog (refined)  
**Kind:** go-do  
**Source:** [mill-comparison.md](../mill-comparison.md) §11  
**Depends on:** soft — classpath from build/ide model  
**Branch:** `ticket-1036-repl-sandbox-dx`  
**Estimate:** M (1–2 days)  
**Refs:** `IdeEngineClient` / project classpath, `jk run`, test-runner env, Mill `jshell` + `MILL_TEST_RESOURCE_DIR`

## Problem

Edit–compile–explore loop still drops to IDE or ad-hoc `jshell --class-path …`. Mill attaches
`jshell` to a module. JumpKick also under-documents **test sandbox env** (resource dirs, working
directory) that plugin authors need.

## Goal (two slices; either can land alone)

### Slice A — `jk jshell` (or `jk repl`)

```bash
jk jshell              # module at -C / cwd; compile classpath after build if needed
jk jshell --no-build   # use existing target/classes + deps only
```

1. Resolve module (workspace: current module or root’s default).  
2. Ensure main classes exist (`jk build --skip-tests` if stale — or document manual build).  
3. Exec `jshell` with classpath = main output + resolved deps (same sources as run/test).  
4. Fail clearly if jshell binary missing (JDK without jshell).

### Slice B — Test sandbox docs + env

Document env vars / conventions test code can rely on (and implement if missing):

| Concern | Proposal |
|---|---|
| Test resources root | Document actual layout (`src/test/resources` → classes) |
| Working directory | Process cwd = module dir (state truthfully) |
| Optional | `JK_TEST_RESOURCE_DIR` if test-runner already injects something Mill-like — wire or document absence |

## Acceptance

- [ ] Slice A **or** B complete (prefer A if only one)  
- [ ] If A: REPL starts with compile classpath on a fixture module  
- [ ] If B: guide “Testing” subsection with sandbox facts  
- [ ] Non-zero exit when jshell unavailable (A)  

## Non-goals

- Kotlin REPL (`kotlin` binary) in the same ticket (follow-up)  
- Full debugger  
- Mill `runBackground`  

## Ready criteria

- After P2 lint/packaging noise settles; or pull early if DX complaints pile up  
