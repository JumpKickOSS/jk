# Feature PRDs

Product requirements and design decisions that **tickets and implementation work reference**.
These are not end-user guides (see [guide.md](../guide.md)); they freeze vocabulary and
boundaries so agents and humans stay aligned.

| PRD | Summary |
|---|---|
| [**Libraries, BOMs, starters, and catalogs**](libraries-boms-starters.md) | Layered short-name catalog, platform BOMs (enforced / floor), Maven starters, `jk export bom`; import/Gradle alignment |
| [**Project build logic**](project-build-logic.md) | `.jk-build/` convention (+ `[build].logic` override); Mill-style tasks outside TOML |
| [**Packaging matrix**](packaging.md) | Thin / fat (`jk assemble`) / R8 shrink / Spring Boot / Quarkus / Grails — rules and samples |
| [**Giter8 templates**](giter8-templates.md) | `jk new --template`: local path + short-name catalog (`java-cli`, `quarkus`); remotes later |
| [**CLI ↔ web visual alignment**](cli-web-visual-alignment.md) | Color-means-state contract; web CSS ↔ `JkDarkTheme` token map (JK-1081) |
| [**Exclusive builds + durable in-flight**](exclusive-builds.md) | Same-fingerprint reject (`Build #N already running`); start-time numbers; journal `running` for web refresh (JK-1248–1251) |

Planning board: [kanartist](https://github.com/jkbuild/kanartist) project `jk` (`JK-NNNN`).
