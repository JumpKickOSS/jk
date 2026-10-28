# Plugins and format

Plugins are tables in `jk.toml` (`[spring-boot]`, `[quarkus]`, and the others), not a Gradle `plugins` block and not a Maven plugin list.

`jk format` rewrites Java (Palantir), Kotlin (ktfmt), Groovy and Scala (scalafmt), and turns FQCNs into imports. With `[format] enforce`, `jk guard` formats what changed (checks it under CI): an agent never runs `jk format` itself. Without `enforce`, leave the project's formatting alone.

MCP `publish` is a dry-run; `publish(upload=true)` uploads to the repository `[publish]` names. Central and ad-hoc URLs: the CLI, `jk publish`.

`jk dev` runs the app with reload. That loop stays on the terminal.
