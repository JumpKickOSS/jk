# Imports

`jk import` turns a Maven POM or a Gradle build into `jk.toml`. Gradle is evaluated in a fork; each module becomes a workspace member. The MCP tool `import` is the same importer. `jk export maven`, `gradle`, or `bom` writes the other shape.

`jk mvn` and `jk gradle` run the real other tool on a tree that is not imported yet. Do not add `pom.xml` or `build.gradle` to a project that already has `jk.toml`.

After import, read the report, then `jk test`. A directory that is only a POM has no manifest to edit until you import it.
