# ticket-1010 — Private plugins without a marketplace

**Priority:** P2 (enterprise adoption)  
**Status:** done  
**Branch:** `ticket-1010-private-plugins`  
**Refs:** [plugins.md](../plugins.md) (Private plugins section), `PluginDeclaration`,
`LockPipelines` lock-plugins, `PluginDescriptorOps`, `ThirdPartyPluginTest`, `PrivatePluginPathTest`

## Shipped

1. **`sha256` required** on every `[plugins]` entry (parse error if missing)
2. **Path pin:** `path = "…/plugin.jar"` + `sha256` — file hashed at lock; mismatch fails closed
3. **Coord pin:** `group`/`name`/`version` or `coordinate = "g:a:v"` + `sha256` — fetch then verify
4. Lock materializes `jk-plugin.toml` into `target/plugin-manifests/<sha>.jk-plugin.toml`
5. Docs: packaging checklist + fail-closed table in plugins.md
6. Tests: path pin success, missing sha256, path mismatch at lock, coord without sha256

## Acceptance

- [x] External jar path pin with correct sha256 → contributions apply after materialize
- [x] Wrong/missing sha256 fails closed with clear messages
- [x] plugins.md documents declaration + packaging
- [x] Unit/integration tests under `:core` / `:engine`
- [x] `./gradlew test` green for modules touched

## Out of scope (later)

- Marketplace / `jk plugin install` search
- Cosign / signatures beyond sha256
- Loading plugin classes into the engine JVM
