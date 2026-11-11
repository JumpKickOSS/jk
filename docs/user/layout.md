# Source layout

JumpKick honors **either** source tree. Detection is from the directories on disk:
if `src/main/{java,kotlin,scala,groovy,resources}` or
`src/test/{java,kotlin,scala,groovy,resources}` exists as a **directory**, the
module is **traditional** (Maven). Otherwise it is **simple** (Mill-like).

Language is by **file extension**. `.java` / `.kt` / `.groovy` may share a directory.
`jk new` still asks which tree to scaffold; that only **places files**.

One module may mix Java, Kotlin and Groovy, in main and in tests, compiled in Gradle's order:
Kotlin first (reading Java declarations from source), then Groovy against Kotlin's output
(sweeping the Java sources in joint mode), then Java against both. So Groovy and Java may
reference Kotlin, and Java may reference Groovy; Kotlin cannot reference Groovy. Java and Scala
compile together in one session, so each may reference the other, and a `.java` file under
`src/main/scala` (or a test suite's `scala` root) compiles with them, as sbt and Gradle compile it.
Scala does not mix with Kotlin or Groovy in one module.
`jk new -L simple` scaffolds the Mill-like columns.

`--layout` on `jk new` is file placement only. Prefer shaping the tree so detection
is unambiguous. An optional `layout = "simple"` or `layout = "traditional"` in
`jk.toml` exists as an escape hatch when a tree is mixed (for example a Mill-like
module that still contains a vendored `src/main/resources`); omit it unless you need
that override.

Web “Layout” does the same for templates that declare both layouts —
[Templates](templates.md).

## Trees

| Input | Traditional | Simple |
|-------|-------------|--------|
| Main sources | `src/main/{java,kotlin,groovy}` | `src/` |
| Main resources | `src/main/resources` | `resources/` |
| Default tests | `src/test/{java,kotlin,groovy}` | `test/src/` |
| Default test resources | `src/test/resources` | `test/resources/` |
| Test fixtures | `src/fixtures/java` (`[test] fixtures`) | — |
| Named test suite `<name>` | `src/<name>/{java,kotlin,groovy}` | `<name>/src/` (e.g. `integration/src/`) |
| Named suite resources | `src/<name>/resources` | `<name>/resources` |

Canonical extra suite names — use these unless you have a reason not to:

| Suite | Traditional | Simple | Run with |
|-------|-------------|---------|----------|
| **unit** (default) | `src/test/…` | `test/src/` | `jk test` |
| **integration** | `src/integration/…` | `integration/src/` | `jk test -g` (share-the-commit) |
| **e2e** | `src/e2e/…` | `e2e/src/` | `jk test -s e2e` (CI / judgment) |

Other names (`contract`, `mutation`, …) are discovered the same way. Do not put
Playwright or compose stacks in `src/test`. Cost (`slow`, `network`) is a JUnit
tag, not a fourth canonical directory. [Test](test.md) · [Why](why.md#test-rungs).

A symbolic link to a file in any of these trees is read through: the target's bytes are compiled,
keyed and packaged, and an edit to the target rebuilds the module. A link to a directory is not
followed; a build that meets one fails, naming it. Point `[build] extra-src` or `[resources]
dirs` at the target instead.

## Build output

Every module writes to its own **`target/`**, in Maven's layout, so a tool, a CI glob or a test
harness that knows where Maven puts things finds them there:

| What | Where |
|---|---|
| Main classes, resources copied in | `target/classes/` |
| Test classes, test resources copied in | `target/test-classes/` |
| Annotation-processor sources | `target/generated-sources/annotations/`, `target/generated-test-sources/test-annotations/` |
| Jar, sources jar, javadoc jar | `target/<name>-<version>.jar`, `-sources.jar`, `-javadoc.jar` |
| Fat jar, minified jar (JumpKick's own) | `target/<name>-<version>-all.jar`, `-min.jar` |
| War, exploded war (`[war]`) | `target/<name>-<version>.war`, `target/<name>-<version>/` |
| Runtime image (`[jlink]`) | `target/<name>-jri/`; its training runs' scratch in `target/jlink-train/` |
| Native binary | `target/<name>` |
| JUnit XML | `target/surefire-reports/`; the `integration` suite in `target/failsafe-reports/` |
| JaCoCo | `target/jacoco.exec`, report in `target/site/jacoco/` |
| Javadoc HTML | `target/site/apidocs/` |

JumpKick's own working state sits beside them (`kotlin/`, `groovy/`, `incremental/`, `plugin/`, …),
and its reports (`jk-results.md`, `jk-guards.*`) in the `target/` of the directory a build ran
from.

## Tests

`jk test` runs the **default (unit) suite** only. Other suite directories are discovered
when they exist. Select them with `--suite` / `--all`. `--all` is nightly, not the
inner loop. Details: [Test](test.md).

`jk ide` marks every discovered suite as IDE test source roots.

Suite resources ride the test classpath only when that suite is selected.

### Extra and filtered resources

`[resources]` adds resource roots beyond the layout's and expands placeholders while copying,
as Maven's resource filtering does:

```toml
[resources]
dirs = ["src/extra/resources"]          # copied as written
filtered = ["src/filter/resources"]     # ${name} and @name@ become the value of name
# test-dirs = […]  test-filtered = […]  # the same for the default test suite
# delimiters = ["${*}", "@"]            # the default; ["@"] leaves ${name} for the application

[resources.properties]
"remoting.version" = "3391.va_37fa_a_305d6d"
```

A filtered file's `${name}` and `@name@` take their value from `[resources.properties]`, then
from `project.groupId`, `project.artifactId` and `project.version`, which JumpKick sets itself.
`delimiters` names the placeholders that are expanded, as Maven's resources plugin writes them:
`<begin>*<end>` (`${*}`, `#{*}`) or one token that both begins and ends the name (`@`). Spring
Boot's `["@"]` leaves `${name}` as written for Spring to resolve at run time.
A backslash keeps a placeholder as written: `\${name}` stays `${name}`. A placeholder with no value
is copied as written, and warned when its two ends differ (`${name}`, not `@name@`). Images and
files that are not UTF-8 are copied as they are. A layout root may be listed as filtered.
`jk import` writes the table from a POM's `<resources>`.

### When resources are copied

Main resources are copied into `target/classes/` **before** the main compile, as Maven's
`process-resources` runs before `compile`. An annotation processor can therefore read a resource
from the class output through the `Filer`, and a file it writes at a resource's path replaces the
copy: the jar carries what the processor wrote. Helidon relies on this — its codegen reads a
module's static `META-INF/helidon/manifest` and writes back one that also lists the service
registry it generated.

A compile that runs processors is keyed on the main resources too, so editing or deleting one
compiles the module again and the processor merges into the new bytes; a compile that runs no
processor ignores them. While a resource is unchanged, the copy leaves the file a processor
wrote at its path, a compile restored from the action cache lays that file down again, and
`jk explain` does not count it as resource drift.

Test resources are copied into `target/test-classes/` by the test compile, after it compiles.

## Related

[Projects](projects.md) · [Workspaces](workspaces.md) · [IDE](ide.md)
