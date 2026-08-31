# Tools (`jkx`)

Ephemeral / pinned CLI tools on the JVM, in the spirit of `uvx`. JBang-compatible scripts
work too.

```bash
# Run once, without installing. `--` separates jk's own flags from the tool's, and
# `--main` is required for a jar with no Main-Class in its manifest.
jk tool run com.puppycrawl.tools:checkstyle:14.1.0 \
  --main com.puppycrawl.tools.checkstyle.Main -- -c checkstyle.xml src/main/java

# Or install once; the launcher lands on PATH under the artifact's short name.
jk tool install com.puppycrawl.tools:checkstyle:14.1.0 \
  --main com.puppycrawl.tools.checkstyle.Main
checkstyle -c checkstyle.xml src/main/java

jk tool list
jk tool uninstall checkstyle
jk tool dir
jk tool run script.java                                 # JBang-compatible headers
```

Two sharp edges worth knowing before you copy the above:

- **`--` is not optional.** `jk tool run` parses the leading flags itself, so
  `jk tool run <tool> -c foo.xml` exits 64 on `unrecognized option '-c'`. Everything after
  `--` goes to the tool untouched. `jkx` is the same binary and behaves identically.
- **`jk tool run` does not resolve an installed tool by name.** Even after
  `jk tool install`, `jk tool run checkstyle -- …` answers ``checkstyle` is not in the
  library catalog` — pass the full coordinate, or use the launcher the install put on
  `PATH`. Tracked at JK-2622.

`jk install g:a:v` outside a project is the same jkx-style install (coordinate on PATH).
Trust gates stay on the CLI — MCP `jk_install action=list` shows installed tools but does
not perform tool installs.

## Build tools

The same verb installs the **build-tool distributions** jk provisions for itself — Kotlin
(for `.kt` sources and `.kts` build logic), Maven and Gradle (for `jk mvn` / `jk gradle`):

```bash
jk install kotlin:latest        # the version jk would provision on demand
jk tool install kotlin:2.4.10   # a specific one
jk tool install maven:3.9.9
jk tool list                    # build tools, then CLI tools
jk tool uninstall kotlin:2.4.10 # version required — several may be installed
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
