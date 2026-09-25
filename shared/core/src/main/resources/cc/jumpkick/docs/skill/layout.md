# Layout

Layout is the directory shape, not a manifest key. Traditional: `src/main/java`, `src/test/java`. Simple: `src/`, `test/src/`. The file extension is the language. Java with Kotlin, or Java with Groovy, can share a module. Kotlin with Groovy cannot.

`java = 25` is the usual language level. Omit it to inherit. The host JDK 25 cross-compiles 17 and 21. `jdk =` selects an install and is rare.

`jk new` and `jk init` scaffold a project. `jk new -t <ref>` applies a template. MCP `new` with `action=templates` lists ids. Pass `preview=true` before writing.

Build outputs live under `target/`. `jk clean` deletes `target/`. The action cache can restore inputs that did not change.
