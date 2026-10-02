# Source layout

JumpKick honors **either** source tree. Detection is from the directories on disk:
if `src/main/{java,kotlin,scala,groovy,resources}` or
`src/test/{java,kotlin,scala,groovy,resources}` exists as a **directory**, the
module is **traditional** (Maven). Otherwise it is **simple** (Mill-like).

Language is by **file extension**. `.java` / `.kt` / `.groovy` may share a directory.
`jk new` still asks which tree to scaffold; that only **places files**.
`jk new --layout simple` scaffolds the Mill-like columns.

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
| **integration** | `src/integration/…` | `integration/src/` | `jk test --guard` (share-the-commit) |
| **e2e** | `src/e2e/…` | `e2e/src/` | `jk test --suite e2e` (CI / judgment) |

Other names (`contract`, `mutation`, …) are discovered the same way. Do not put
Playwright or compose stacks in `src/test`. Cost (`slow`, `network`) is a JUnit
tag, not a fourth canonical directory. [Test](test.md) · [Why](why.md#test-rungs-the-execute-moat).

## Build output

Every module writes to its own **`target/`**, in Maven's layout, so a tool, a CI glob or a test
harness that knows where Maven puts things finds them there:

| What | Where |
|---|---|
| Main classes, resources copied in | `target/classes/` |
| Test classes, test resources copied in | `target/test-classes/` |
| Annotation-processor sources | `target/generated-sources/annotations/`, `target/generated-test-sources/test-annotations/` |
| Jar, sources jar, javadoc jar | `target/<name>-<version>.jar`, `-sources.jar`, `-javadoc.jar` |
| Fat jar, minified jar (jk's own) | `target/<name>-<version>-all.jar`, `-min.jar` |
| War, exploded war (`[war]`) | `target/<name>-<version>.war`, `target/<name>-<version>/` |
| Native binary | `target/<name>` |
| JUnit XML | `target/surefire-reports/`; the `integration` suite in `target/failsafe-reports/` |
| JaCoCo | `target/jacoco.exec`, report in `target/site/jacoco/` |
| Javadoc HTML | `target/site/apidocs/` |

jk's own working state sits beside them (`kotlin/`, `groovy/`, `incremental/`, `plugin/`, …),
and its reports (`jk-results.md`, `jk-guards.*`) in the `target/` of the directory a build ran
from.

## Tests

`jk test` runs the **default (unit) suite** only. Other suite directories are discovered
when they exist. Select them with `--suite` / `--all`. `--all` is nightly, not the
inner loop. Details: [Test](test.md).

`jk ide` marks every discovered suite as IDE test source roots.

Suite resources ride the test classpath only when that suite is selected.

## Related

[Projects](projects.md) · [Workspaces](workspaces.md) · [IDE](ide.md)
