# Tools (`jkx`)

Ephemeral / pinned CLI tools on the JVM, in the spirit of `uvx`. JBang-compatible scripts
work too.

```bash
# Run once, without installing. `--` separates jk's own flags from the tool's, and
# `--main` is required for a jar with no Main-Class in its manifest.
jk tool run com.puppycrawl.tools:checkstyle:14.1.0 \
  --main com.puppycrawl.tools.checkstyle.Main -- -c checkstyle.xml src/main/java

# Or install once; the launcher lands on PATH under the artifact's short name,
# and the name then resolves in `jk tool run` / `jkx` too — reusing the recorded
# coordinate, Main-Class and classpath, so --main is never repeated.
jk tool install com.puppycrawl.tools:checkstyle:14.1.0 \
  --main com.puppycrawl.tools.checkstyle.Main
checkstyle -c checkstyle.xml src/main/java
jk tool run checkstyle -- -c checkstyle.xml src/main/java   # same thing

jk tool list
jk tool uninstall checkstyle
jk tool dir
jk tool run script.java                                 # JBang-compatible headers
```

One sharp edge worth knowing before you copy the above:

- **`--` is not optional.** `jk tool run` parses the leading flags itself, so
  `jk tool run <tool> -c foo.xml` exits 64 on `unrecognized option '-c'`. Everything after
  `--` goes to the tool untouched. `jkx` is the same binary and behaves identically.

How a bare name resolves, in order: an **installed tool** (what `jk tool list` shows) wins,
then the **library catalog**; a full `group:artifact[:version]` coordinate skips both. An
installed name therefore shadows a catalog entry of the same name — the tool this machine
installed is the least surprising answer, and the coordinate spelling is always available to
force the catalog's. Asking for a version (`name@selector`), or passing `--with` / `--main`,
is a request the install did not record, so those resolve fresh instead of using the
installed pin.

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
download instead of linking a host install. A distribution no published checksum vouches for
(the Maven 3.6 line) is refused until `--accept-unverified-tool` (or `JK_ACCEPT_UNVERIFIED_TOOL=1`)
accepts that one download — the same consent `jk mvn` takes: the archive installs, its SHA-256
is recorded as `tools/<tool>/<version>.accepted.sha256`, and every later download of that version
verifies against the record ([Migration](migration.md)).

## Lint is a build step, not a tool

| Concern | Path |
|---------|------|
| **Format** (style rewrite) | [`jk format`](format.md) |
| **Java lint** (Checkstyle, PMD, SpotBugs) and **Kotlin analysis** (detekt) | the [`[lint]` table](lint.md): cached steps after compile, findings in `jk-results.md` |
| **Database migrations** (Flyway, Liquibase) | [a tool recipe](database.md): `jk tool install` with the driver, the URL from the environment |

A one-off run of any of them is still `jk tool run <coordinate> -- <args>`.
