# ticket-1013 — Affected-since workspace builds

**Priority:** P2  
**Status:** ready  
**Branch:** `ticket-1013-affected-since`  
**Refs:** [guide.md](../guide.md) workspaces (flag may be mentioned as planned),
`BuildGraph` / `ModuleOrder` / `WorkspaceScheduler`, `clients/cli/.../command/BuildCommand.java`

## Problem

Monorepos rebuild the world even when only one leaf module changed. Want:

```bash
jk build --affected-since=origin/main
```

using git changed paths + reverse module dependency edges so only affected modules (and
dependents) run.

## Scope

1. **CLI:** `--affected-since=<git-ref>` on `jk build` (and optionally `test` if free)
2. **Compute changed paths:** `git diff --name-only <ref>…HEAD` (fail clearly outside a git repo)
3. **Map paths → modules** via workspace module dirs
4. **Closure:** include reverse-deps from `BuildGraph` / module prereq edges (dependents of
   changed modules)
5. **Empty set:** print “nothing affected” and exit 0 (or skip build) — document choice
6. **Tests:** stub path list (no real git required in unit test) + one integration with temp git
   if cheap

## Acceptance

- [ ] Flag parses; invalid ref → usable error
- [ ] Unit test: given changed paths under `libs/a`, modules `{a, app}` scheduled, `libs/b` not
- [ ] Outside git / no workspace → clear message
- [ ] Default `jk build` unchanged when flag absent

## Out of scope

- Watch mode / continuous affected
- Non-git VCS
- Affected across unpublished external coords
