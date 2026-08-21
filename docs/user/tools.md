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

## Lint is a recipe, not a first-party matrix

| Concern | Path |
|---------|------|
| **Format** (style rewrite) | [`jk format`](format.md) |
| **Java lint** | Checkstyle via `jk tool install` / `jk tool run` |
| **Kotlin analysis** | Use `jk format` for style; detekt later as the same recipe pattern |

JumpKick does **not** ship Mill’s full lint matrix as first-party plugins.

Sample: [examples/checkstyle-recipe/](examples/checkstyle-recipe/).
