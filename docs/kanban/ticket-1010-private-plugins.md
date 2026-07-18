# ticket-1010 — Private plugins without a marketplace

**Priority:** P2 (enterprise adoption)  
**Status:** ready  
**Branch:** `ticket-1010-private-plugins`  
**Refs:** [plugins.md](../plugins.md) (distribution section already points here),
[architecture.md](../architecture.md) plugins, `PluginDescriptor` / `jk-plugin.toml`,
`PluginLoader` (forked workers), first-party `plugins/spring-boot` as packaging blueprint

## Problem

SPI and forked workers exist; first-party plugins ship **inside** the jk distribution. Enterprises
need to **vendor a jar** (path or Maven coordinate) **with a content pin** before any public
marketplace. Today there is no supported project-level declaration for “load this external plugin
jar and refuse if the hash drifts.”

## Direction

### Declaration (choose one primary shape; support both if cheap)

```toml
# Option A — path pin (air-gapped / monorepo vendor dir)
[[plugins]]
id = "acme-rules"
path = "vendor/acme-rules-1.0.0.jar"
sha256 = "…"   # required for path and remote

# Option B — Maven coordinate pin
[[plugins]]
id = "acme-rules"
coordinate = "com.acme:acme-rules:1.0.0"   # or group/name/version table form
sha256 = "…"
```

Exact TOML table name should match parser conventions (`[[plugins]]` vs `[plugins.acme-rules]`) —
pick one, document it in plugins.md, and keep it stable.

### Trust model (fail closed)

| Situation | Behavior |
|---|---|
| Path/coord declared, **no** sha256 | **Error** at load (no silent trust) |
| sha256 mismatch vs file/CAS blob | **Error** with both digests in message |
| Remote coord without pin | **Error** (no auto-download of unpinned plugins) |
| Unknown plugin id in `jk.toml` table without jar | Existing unowned-table rules apply |

Optional later (not MVP): signature / cosign; for MVP **content hash is the trust root**.

### Runtime

1. Resolve jar (path relative to project / workspace root, or fetch coord into CAS)
2. Verify sha256
3. Extract/read `jk-plugin.toml` (declarative layer only in engine process)
4. Register contributions like first-party plugins
5. Worker code layer forks with that jar on the worker classpath; action keys already hash
   worker jars — keep that property

### Docs

- plugins.md: “Private plugins” section — packaging checklist (mirror spring-boot jar layout),
  pin generation (`sha256sum`), and fail-closed examples
- No marketplace, no global plugin index

## Scope

**In (MVP)**

- Project (or workspace-root) declaration + pin
- Path-based load with hash verify (fixture test)
- Coord-based load with hash verify **if** fetch reuses existing repo/CAS paths; otherwise path-only
  MVP and document coord as phase 1.1
- Clear errors for missing/mismatched hash
- Guide/plugins.md updates (edit existing files only)

**Out**

- Public registry / search / `jk plugin install` marketplace UX
- Signing infrastructure beyond sha256
- Loading arbitrary code into the **engine** JVM (workers only)
- Auto-updating floating versions without pin

## Dependencies

- None hard. Benefits from stable plugin SPI (exists). Independent of 1007/1014.

## Acceptance

- [ ] Fixture project declares an external jar (outside the monorepo tree, temp dir OK) with
      correct sha256 → plugin table contributions apply (`jk lock` or `jk build` uses them)
- [ ] Same fixture with wrong/missing sha256 → non-zero exit and message naming expected vs actual
- [ ] plugins.md documents declaration + packaging checklist
- [ ] Unit/integration tests under `:engine` or `:cli-engine` (no network required for path pin)
- [ ] `./gradlew test` green for modules touched

## Risks

- Ambiguous resolution if both bundled and private plugins claim the same `table=` — define
  precedence (private overrides bundled with a warning, or error on conflict)
- Workspace multi-module: declare once at root vs per module — prefer root-only for MVP
