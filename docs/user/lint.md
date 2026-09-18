# Lint

Checkstyle, PMD, SpotBugs and detekt run as **cached build steps after compile**: the `[lint]`
table enables each with its configuration, the step forks the tool over the module and reads its
report back, and every finding is a diagnostic — file, line, the rule id and the message — in
`target/jk-results.md`, on the terminal, and in the MCP `jk_diagnostics` an agent reads. A finding
that is not in the results file is a finding an agent does not see; these are.

```toml
[lint]
checkstyle = "config/checkstyle.xml"                            # Checkstyle with this rule set
pmd        = ["category/java/bestpractices.xml", "config/pmd.xml"]   # PMD with these rulesets
spotbugs   = true                                               # SpotBugs over the compiled classes
detekt     = true                                               # detekt over the Kotlin sources
# fail-on  = "error"                                            # error (default) | warning | never
```

| Key | Meaning | Default |
|---|---|---|
| `sources` | The module-relative Java source roots the Java tools read; add `src/test/java` to lint the tests | `["src/main/java"]` |
| `kotlin-sources` | The Kotlin roots detekt reads | `["src/main/kotlin"]` |
| `exclude` | Module-relative Ant-style path globs Checkstyle and detekt leave out (`**/generated/**`); PMD excludes through its ruleset's `exclude-pattern`, SpotBugs through `spotbugs-exclude` | `[]` |
| `fail-on` | The finding severity that fails the step: `error`, `warning`, or `never`. Findings render as diagnostics either way | `"error"` |
| `checkstyle` | Enable Checkstyle with this configuration file | off |
| `checkstyle-version` | The Checkstyle release; a bare version is exact | `"14.1.0"` |
| `pmd` | Enable PMD with these rulesets: a built-in `category/java/…` or `rulesets/java/…`, or a module-relative ruleset file | off |
| `pmd-version` | The PMD release | `"7.27.0"` |
| `spotbugs` | Enable SpotBugs over the module's classes, against its compile classpath | `false` |
| `spotbugs-exclude` | A SpotBugs filter file of findings to leave out | none |
| `spotbugs-effort` | `min`, `less`, `default`, `more`, `max` | `"default"` |
| `spotbugs-version` | The SpotBugs release | `"4.10.4"` |
| `detekt` | Enable detekt over the Kotlin sources | `false` |
| `detekt-config` | A detekt configuration laid over the default rule set | none |
| `detekt-version` | The detekt release | `"1.23.8"` |

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
what fails the step: `error` (the default) lets warnings through, `warning` fails on any finding,
`never` reports and passes. A tool that cannot run — a rule set that does not parse, a missing
ruleset — fails the step with the tool's last lines.

## Configuration files

The files are yours and live in the module: jk ships no house rule set. A PMD ruleset is either
one of PMD's built-in categories (`category/java/bestpractices.xml`, `rulesets/java/quickstart.xml`)
or a file; SpotBugs's `spotbugs-exclude` is its filter-file format; detekt's `detekt-config` is
laid over detekt's default configuration (`--build-upon-default-config`). Suppressions stay in the
tool's own idiom — `@SuppressWarnings("PMD.Rule")`, `@SuppressFBWarnings`, a Checkstyle
`SuppressionFilter`, `@Suppress("MagicNumber")`.

## Beside the other tables

`[lint]` sits beside `[spring-boot]`, `[kotlin]` or any generator table in one module. What
`jk format` owns — layout, import order, unused imports — belongs in no rule set here; a
Checkstyle configuration that repeats the formatter's job reports what `jk format` has already
fixed. The worked example: [`examples/lint-checkstyle`](examples/lint-checkstyle/). `jk import`
writes the table from a POM's `maven-checkstyle-plugin`, `maven-pmd-plugin` and
`spotbugs-maven-plugin` ([Migration](migration.md#which-maven-plugins-import-and-how-well)).

## Related

[Format](format.md) · [Guards](guards.md) — house rules over the code's shape · [Explain](explain.md)
