# Plugins and format

Plugins are tables in `jk.toml` (`[spring-boot]`, `[quarkus]`, and the others), not a Gradle `plugins` block and not a Maven plugin list.

`jk format` rewrites Java (Palantir), Kotlin (ktfmt), Groovy and Scala (scalafmt), and turns FQCNs into imports. Run it after source edits. `jk format -c` is the CI gate. MCP: `run(kind=format)`.

Real publish uploads are the CLI: `jk publish`. MCP `publish` and `run(kind=publish)` are always a dry-run.

`jk dev` runs the app with reload. That loop stays on the terminal.
