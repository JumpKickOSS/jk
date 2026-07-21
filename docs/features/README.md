# Feature PRDs

Product requirements and design decisions that **tickets and implementation work reference**.
These are not end-user guides (see [guide.md](../guide.md)); they freeze vocabulary and
boundaries so agents and humans stay aligned.

| PRD | Summary |
|---|---|
| [**Libraries, BOMs, starters, and catalogs**](libraries-boms-starters.md) | Layered short-name catalog, platform BOMs, Maven starters, optional bundles; import/Gradle alignment |
| [**Project build logic**](project-build-logic.md) | `.jk-build/` convention (+ `[build].logic` override); Mill-style tasks outside TOML |
| [**Packaging matrix**](packaging.md) | Thin / fat (`jk assembly`) / R8 shrink / Spring Boot — rules and samples |
| [**CLI ↔ web visual alignment**](cli-web-visual-alignment.md) | Color-means-state contract; web CSS ↔ `JkDarkTheme` token map (JK-1081) |

Planning board: [kanartist](https://github.com/jkbuild/kanartist) project `jk` (`JK-NNNN`). Frozen local archive: [kanban/README.md](../kanban/README.md).
