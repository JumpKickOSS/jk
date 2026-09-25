# Lint

Checkstyle, PMD, SpotBugs and detekt run as **cached build steps after compile**: the `[lint]`
table enables each with its configuration, the step forks the tool over the module and reads its
report back, and every finding is a diagnostic — file, line, the rule id and the message — in
`target/jk-results.md`, on the terminal, and in the MCP `diagnostics` an agent reads. A finding
that is not in the results file is a finding an agent does not see; these are.

```toml
[lint]
checkstyle = "config/checkstyle.xml"                            # Checkstyle with this rule set
pmd        = ["category/java/bestpractices.xml", "config/pmd.xml"]   # PMD with these rulesets
spotbugs   = true                                               # SpotBugs over the compiled classes
detekt     = true                                               # detekt over the Kotlin sources
# fail-on  = "error"                                            # error (default) | warning | never
# pmd-fail-on = "warning"                                       # one tool's own threshold, beside fail-on
```

| Key | Meaning | Default |
|---|---|---|
| `sources` | The module-relative Java source roots the Java tools read; add `src/test/java` to lint the tests | `["src/main/java"]` |
| `kotlin-sources` | The Kotlin roots detekt reads | `["src/main/kotlin"]` |
| `exclude` | Module-relative Ant-style path globs Checkstyle and detekt leave out (`**/generated/**`); PMD excludes through its ruleset's `exclude-pattern`, SpotBugs through `spotbugs-exclude` | `[]` |
| `fail-on` | The finding severity that fails a step: `error`, `warning`, or `never`. Findings render as diagnostics either way | `"error"` |
| `checkstyle-fail-on`, `pmd-fail-on`, `spotbugs-fail-on`, `detekt-fail-on` | One tool's own threshold, taking precedence over `fail-on` for that tool — Maven's plugins each fail on their own terms (Checkstyle on errors, PMD and SpotBugs on every finding), and `jk import` writes these when the tools of a module differ | `fail-on` |
| `checkstyle` | Enable Checkstyle with this configuration file — a module-relative path, the rule set at an `https://` URL, fetched once into the store and read from there on every build after, or a resource inside a `checkstyle-classpath` jar, named as Checkstyle resolves it (`checkstyle.xml`) | off |
| `checkstyle-suppressions` | A suppressions file — module-relative, at an `https://` URL, or a `checkstyle-classpath` resource — handed to the rule set the way Maven's `<suppressionsLocation>` is: as the `checkstyle.suppressions.file` property a `SuppressionFilter` reads (`<property name="file" value="${checkstyle.suppressions.file}"/>`) | none |
| `checkstyle-header` | A header file — module-relative, at an `https://` URL, or a `checkstyle-classpath` resource — handed to the rule set the way Maven's `<headerLocation>` is: as the `checkstyle.header.file` property a `Header` check reads | none |
| `checkstyle-properties` | Properties the rule set reads as `${name}`, the way Maven's `<propertyExpansion>` hands them over: `{ "checkstyle.build.directory" = "target" }`; `checkstyle.suppressions.file` and `checkstyle.header.file` come from their own keys | `{}` |
| `checkstyle-classpath` | Coordinates whose jars join Checkstyle's classpath with their closures — the jars a build ships its rule set, header, suppressions or check classes in, as a Maven plugin's `<dependencies>` do (`["org.springframework.cloud:spring-cloud-build-tools:5.0.3", "io.spring.javaformat:spring-javaformat-checkstyle:0.0.47"]`) | `[]` |
| `checkstyle-version` | The Checkstyle release; a bare version is exact | `"14.1.0"` |
| `pmd` | Enable PMD with these rulesets: a built-in `category/java/…` or `rulesets/java/…`, `rulesets/java/maven-pmd-plugin-default.xml` (Maven's default, which jk carries), or a module-relative ruleset file | off |
| `pmd-exclude` | A module-relative file in `maven-pmd-plugin`'s `excludeFromFailureFile` shape — `package.Class=Rule,Rule` per line — whose findings are left out of the report | none |
| `pmd-version` | The PMD release; `jk import` writes the one the POM's plugin runs — the `pmd-java` it pins, else the release its `maven-pmd-plugin` bundles, read from that plugin's POM — since a newer PMD reports what an older one let through | `"7.27.0"` |
| `spotbugs` | Enable SpotBugs over the module's classes, against its compile classpath | `false` |
| `spotbugs-exclude` | A SpotBugs filter file of findings to leave out | none |
| `spotbugs-effort` | `min`, `less`, `default`, `more`, `max` | `"default"` |
| `spotbugs-threshold` | The lowest confidence SpotBugs reports: `high`, `medium` (SpotBugs's and the Maven plugin's default), `low` | `"medium"` |
| `spotbugs-max-rank` | The scariest bug rank SpotBugs reports, 1 to 20, as the Maven plugin's `<maxRank>`; every rank when unset | none |
| `spotbugs-omit-visitors` | Detectors SpotBugs leaves out, by class name (`["ConstructorThrow", "FindReturnRef"]`), as the Maven plugin's `<omitVisitors>` | `[]` |
| `spotbugs-visitors` | The only detectors SpotBugs runs, by class name, as the Maven plugin's `<visitors>` | `[]` |
| `spotbugs-plugins` | Coordinates of detector plugins loaded beside SpotBugs's own — find-sec-bugs, fb-contrib — as the Maven plugin's `<plugins>` (`["com.h3xstream.findsecbugs:findsecbugs-plugin:1.14.0"]`). Each jar is fetched as written, without its dependencies, and a `@SuppressFBWarnings` naming one of its patterns is then a suppression rather than a useless one | `[]` |
| `spotbugs-version` | The SpotBugs release. SpotBugs reads the class files of the build JDK it runs on, so a release older than that JDK is refused before anything is fetched, with the floor named: `4.2.2` on JDK 17, `4.8.0` on JDK 21, `4.9.4` on JDK 25 | `"4.10.4"` |
| `detekt` | Enable detekt over the Kotlin sources | `false` |
| `detekt-config` | A detekt configuration laid over the default rule set | none |
| `detekt-version` | The detekt release | `"1.23.8"` |

A second Checkstyle run over another rule set is a `[lint.<name>]` entry beside the table:

```toml
[lint]
checkstyle           = "checkstyle.xml"                 # a resource of spring-cloud-build-tools
checkstyle-classpath = ["org.springframework.cloud:spring-cloud-build-tools:5.0.3"]
checkstyle-header    = "checkstyle-header.txt"

[lint.nohttp]                                           # the step lint-checkstyle-nohttp
checkstyle           = "https://raw.githubusercontent.com/spring-cloud/spring-cloud-build/main/spring-cloud-build-tools/src/checkstyle/nohttp-checkstyle.xml"
checkstyle-classpath = ["io.spring.nohttp:nohttp-checkstyle:0.0.11"]
sources              = ["."]
exclude              = ["**/target/**/*", "**/.git/**/*"]
```

An entry carries its own `checkstyle`, `checkstyle-suppressions`, `checkstyle-header`,
`checkstyle-properties`, `checkstyle-classpath`, `sources`, `exclude` and `fail-on` (`error`
unless written — the table's thresholds do not reach it) and runs as the step
`lint-checkstyle-<name>` on its own `checkstyle-version` — the table's unless the entry writes
one, so two runs may fork two Checkstyle releases — with a report of its own that the
`lint.checkstyle` guard measure counts beside the table's. `jk import` writes one entry per
further `maven-checkstyle-plugin` execution, named by the execution's id.

Each enabled tool is one step named `lint-<tool>` — `jk explain` shows them. A step's **cache
key** is the source roots it reads, its configuration files, the module's compiled classes (and,
for SpotBugs, the compile classpath), the table, the tool's jar hashes, the build JDK and the
worker: a source, rule or version edit re-runs the tool; an unchanged module is a hit. The tool
runs in a **forked JVM** on the build's JDK, never in the engine, with its runtime closure fetched
into the content-addressed store at the pinned release — nothing of it is in `[dependencies]`.

## Findings and severity

The tool's report is read back, not its console: Checkstyle's and detekt's XML, PMD's XML,
SpotBugs's `BugCollection`. Each finding becomes a diagnostic of the form

```text
src/main/java/demo/Sample.java:41:9: 'if' construct must use '{}'s. [NeedBraces]
```

with the rule id — Checkstyle's check name, PMD's rule, SpotBugs's bug pattern, detekt's rule —
after the message. Severity follows the tool: Checkstyle's `severity` property (`error` /
`warning`; `ignore` is dropped), PMD priorities 1–2 as errors and 3–5 as warnings, SpotBugs
priority 1 (high) as an error and the rest as warnings, detekt's `warning`. `fail-on` decides
what fails a step: `error` (the default) lets warnings through, `warning` fails on any finding,
`never` reports and passes; a tool with its own `<tool>-fail-on` follows that instead, so
`pmd-fail-on = "warning"` beside the default fails the build on any PMD finding while Checkstyle
fails on errors alone, and the failure names the key that applied. A tool that cannot run — a rule set that does not parse, a missing
PMD ruleset — fails the step with the tool's last lines. Two cases are not failures: a module
whose roots hold no file for the tool (Checkstyle's `exclude` globs covering every source, a test
module with no `src/main/java`) is a step labelled `(no sources)` with an empty report, and a
`checkstyle` or `detekt-config` file the module does not hold — the spelling `jk import` keeps for
a rule set it could not carry, `sun_checks.xml` — is a warning naming it, labelled
`(no configuration)`, until the file is there.

## Configuration files

The files are yours and live in the module: jk ships no house rule set. A Checkstyle rule set
may instead be the one a build shares from a URL (`checkstyle = "https://raw.githubusercontent.com/…/checkstyle.xml"`,
the shape `jk import` keeps from a POM's `<configLocation>`): the engine fetches it once into
the store — `--offline` with no copy yet fails naming the URL — and its content is part of the
step's cache key, so the file is re-read, never re-fetched, until the URL changes. A rule set a
build ships inside a jar — spring-cloud-build's `checkstyle.xml` in spring-cloud-build-tools,
with the `io.spring.javaformat` checks it names — is a resource of a `checkstyle-classpath` jar:
the jars join Checkstyle's classpath and `checkstyle`, `checkstyle-suppressions` and
`checkstyle-header` name their resources as Checkstyle resolves them. `jk import` reads each jar
the plugin's `<dependencies>` name and confirms every such resource is in one of them: a rule set,
header or suppressions file no jar holds is a row in the report, since the step would find no
configuration and lint nothing. A rule set whose
`SuppressionFilter` reads `${checkstyle.suppressions.file}` — the property
`maven-checkstyle-plugin` binds `<suppressionsLocation>` to — gets it from
`checkstyle-suppressions`, one whose `Header` check reads `${checkstyle.header.file}` from
`checkstyle-header`, and any other `${name}` from `checkstyle-properties`, all through
Checkstyle's own `-p` properties file. A PMD ruleset is either
one of PMD's built-in categories (`category/java/bestpractices.xml`, `rulesets/java/quickstart.xml`),
`rulesets/java/maven-pmd-plugin-default.xml` — the ruleset `maven-pmd-plugin` runs when a POM names
none, which PMD itself does not ship and jk carries so an imported build lints as Maven did — or a
file; `pmd-exclude` leaves a class's listed rules out, as Maven's `excludeFromFailureFile` does;
SpotBugs's `spotbugs-exclude` is its filter-file format; detekt's `detekt-config` is
laid over detekt's default configuration (`--build-upon-default-config`). Suppressions stay in the
tool's own idiom — `@SuppressWarnings("PMD.Rule")`, `@SuppressFBWarnings`, a Checkstyle
`SuppressionFilter`, `@Suppress("MagicNumber")`.

## Ratcheting the findings down

The reports are what a guard reads: a `metric` rule with `measure = "lint.findings"` (or one
tool's `lint.checkstyle`, `lint.pmd`, `lint.spotbugs`, `lint.detekt`) counts a module's findings
from the XML each step leaves — the report as the step reported it, so a PMD violation
`pmd-exclude` leaves out is not in the file and not in the count — and with `cap` and
`baseline = true` refuses a build whose count
rises above the baseline's entry while the entry follows the count down — so a project that
adopts a rule set with hundreds of findings sets `fail-on = "never"`, lets the diagnostics show,
and tightens the ratchet as they are fixed. The shape is [Guards](guards.md#keys)' `metric` kind.

## Beside the other tables

`[lint]` sits beside `[spring-boot]`, `[kotlin]` or any generator table in one module. What
`jk format` owns — layout, import order, unused imports — belongs in no rule set here; a
Checkstyle configuration that repeats the formatter's job reports what `jk format` has already
fixed. The worked example: [`examples/lint-checkstyle`](examples/lint-checkstyle/). `jk import`
writes the table from a POM's `maven-checkstyle-plugin`, `maven-pmd-plugin` and
`spotbugs-maven-plugin` ([Migration](migration.md#which-maven-plugins-import-and-how-well)).

## Related

[Format](format.md) · [Guards](guards.md) — house rules over the code's shape · [Explain](explain.md)
