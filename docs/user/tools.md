# Tools (`jkx`)

Ephemeral / pinned CLI tools on the JVM, in the spirit of `uvx`. JBang-compatible scripts
work too.

```bash
jk tool run checkstyle -c checkstyle.xml src/main/java
jkx checkstyle -c checkstyle.xml src/main/java          # same idea, after install
jk tool install com.puppycrawl.tools:checkstyle:10.21.4
jk tool list
jk tool uninstall …
jk tool dir
jk tool run script.java                                 # JBang-compatible headers
```

`jk install g:a:v` outside a project is the same jkx-style install (coordinate on PATH).
Trust gates stay on the CLI — MCP `jk_install action=list` shows installed tools but does
not perform tool installs.

## Build tools

The same verb installs the **build-tool distributions** jk provisions for itself — Kotlin
(for `.kt` sources and `.kts` build logic), Maven and Gradle (for `jk mvn` / `jk gradle`):

```bash
jk install kotlin:latest        # the version jk would provision on demand
jk tool install kotlin:2.4.0    # a specific one
jk tool install maven:3.9.9
jk tool list                    # build tools, then CLI tools
jk tool uninstall kotlin:2.4.0  # version required — several may be installed
jk tool dir                     # $JK_STORE_DIR/tools
```

These are not launchers on `PATH`: a distribution is unpacked under
`$JK_STORE_DIR/tools/<tool>/<version>/` and the engine consumes it as a home. Installing
ahead of time is therefore a **cache hit** for the build that later needs it, not a second
copy — same provisioning path either way.

They live in the **store**, not the cache, because a fetched distribution is an artifact:
`jk cache nuke` does not cost you an 83 MB Kotlin re-download. `--no-discover` forces a
download instead of linking a host install.

## Lint is a recipe, not a first-party matrix

| Concern | Path |
|---------|------|
| **Format** (style rewrite) | [`jk format`](format.md) |
| **Java lint** | Checkstyle via `jk tool install` / `jk tool run` |
| **Kotlin analysis** | Use `jk format` for style; detekt later as the same recipe pattern |

JumpKick does **not** ship Mill’s full lint matrix as first-party plugins.

Sample: [examples/checkstyle-recipe/](examples/checkstyle-recipe/).
