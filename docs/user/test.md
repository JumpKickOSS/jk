# Test

JumpKick is opinionated about **when** to run tests, not only how. Write a pyramid
(many unit, fewer integration, few e2e). Run a ladder: climb only as high as this
turn requires. Product bet: [Why JumpKick](why.md#test-rungs-the-execute-moat).

```bash
jk test                              # unit rung — default suite only. Inner loop.
jk test --guard                       # share-the-commit: unit + integration (if present) + guard scripts
jk test --scripts-only               # guard scripts, no JUnit
jk test --guard --no-scripts          # guard suites, skip extra scripts
jk test --suite integration          # -s is the short form. Climb one named suite.
jk test --suite e2e                  # UI / compose / contract; not a habit
jk test --all                        # every suite; tag excludes cleared. Nightly / release.
jk test --exclude-tags slow,bench
jk test --include-tags smoke
jk test --class OrdersTest           # one class (simple or qualified name, * wildcards; repeatable)
jk test --debug-jvm                  # suspended test JVM listening on localhost:5005 — attach and go
jk test --coverage                   # JaCoCo agent on every suite JVM; Coverage block in jk-results.md, HTML per module
jk test --affected                   # ranked classes for the working tree (does not run them)
jk test --affected-since=HEAD~2      # ranked classes since that ref (does not run them)
jk build --guard                      # package with the guards green (same flag on assemble, image, native, install)
jk build --all                       # package with the full suite green
```

`--affected` and `--affected-since=<ref>` rank at most 20 test **classes**, print them as a
table, and write `target/jk-tests-affected.md`. They do **not** run tests. `--affected` is
the git working tree (unstaged + untracked; last commit if the tree is clean).
`--affected-since` is `ref...HEAD`. Neither is `jk explain`'s rebuild set. They cannot
be combined (`--aff` is ambiguous). Refuse exits **2**; that file is not `jk-results.md`.

`--all` is **not** the inner loop. Agents and humans fixing a unit assertion should
run `jk test`, not `--all`. Before you share a commit, run **`--guard`**: unit + `integration` when that directory exists. Tag excludes
from `[test]` still apply (`slow` / `network` / `bench` stay out). `--guard` cannot
combine with `--all`. `--suite` wins over `--guard` (a warning is printed).

Override the guard suite list:

```toml
[test]
guard-suites = ["test", "integration", "contract"]
```

Unknown names in `guard-suites` fail with the discovered-suite list. Missing
`integration` on the default list is not an error.

Canonical extra suite **names** are `integration` and `e2e`. Any other suite
directory still works (`contract`, `mutation`, …) — [layout](layout.md). Cost that
crosses a suite (`slow`, `network`, `bench`) is a **JUnit tag**, not a fourth
directory.

## The guard suite is not a test suite

`src/guard/java` holds **guard tests** — `@Guard` methods in a `@GuardSuite` class that read the
same facts, model and text the declarative rules in `jk-guards.toml` read (see
`jk guard explain --schema guard-test`). jk compiles it as `compile-guard`, against main classes,
the test compile classpath (so ArchUnit or Konsist come from `[test-dependencies]`; there is no
`[guard-dependencies]`) and `cc.jumpkick:jk-guards-junit` at the installed jk's version, which jk
provisions from its local store and pins in `jk-lock.toml` — nothing to declare. Discovery never
returns `guard`: `jk test`, `--all` and `--guard` do not collect it, `--suite guard` is an error
that says so, and the guard lanes run it. `jk ide` exports the directory as a test root so a guard
test has a debugger. The rules themselves, their kinds and their baseline: [Guards](guards.md).

`--scripts-only` and `--no-scripts` cannot be combined. `--scripts-only` with no
`guard` stem is a config error. Same flags on `jk build`. See [build logic](build-logic.md).

`--all` and `--suite` cannot be combined. `--all` and `--guard` cannot be combined.
Unknown suite names error with the available list (`--guard`'s default `integration`
is the exception: skip if absent). `--all` means “everything”: every suite directory
**and** cleared `[test]` / profile tag excludes. Explicit `--include-tags` /
`--exclude-tags` still compose on top. `jk build` accepts the same selection flags.

Default-suite paths depend on [layout](layout.md) (`src/test/…` vs `test/src/`). Named
suites are discovered when those directories exist.

When tests fail: `jk results` — [Troubleshooting](troubleshooting.md).

## Pick classes (`--class`)

`--class <name>` runs only the matching test classes of the selected suites: a fully
qualified name is exact, a simple name matches in any package, and `*` stands for any run of
characters (`--class '*IT'`). Repeat the flag to union. Tags still apply. The filter is part of
the run's stamp, so a green `--class` run never marks the whole suite up to date.

The patterns are judged against the **run**, not each module. In a workspace a module whose
suites contain no matching class is skipped — its `run-tests` step reads
`no classes matched --class OrdersTest — skipped`, the same shape as a module that lacks the
selected suite. The run **fails** with `no test classes matched --class OrdersTest` only when no
module matched anything: a typo must not pass green. A standalone project is its own run, so
there the empty match fails on the spot. `[test] serial-tags` partitions inside one module are
judged together: a pattern that only matches serial-tagged classes is a match.

`-m` narrows the run to the selected modules **and their prerequisites**, and a prerequisite
runs its own suite too (a green suite replays from its stamp; a dirty one runs). `-m` is the
same cone for `jk build` and `jk test`. To reach one module's class from the workspace root,
name the class: `jk test -m clients/cli --class SelfNukeCommandTest` runs it in `clients/cli`
and skips the prerequisites, whose suites match nothing.

## An empty run is not green

A workspace `jk test` in which no module ran a test fails with **exit 2** and the reason
`no tests ran: none of the 2 modules has a test suite (com.example:lib, com.example:app)` — the
test verb's sibling of `built nothing`. Every module still finishes (the verdict is the run's, so
`## Modules` reads green), and the headline of `target/jk-results.md` carries the reason, so an
agent reading the exit or the file cannot take an empty run for a passing suite. A suite replayed
from its green stamp counts as run. The verdict is not raised for `--skip-tests`, for a `--class`
selection (whose empty match is `no test classes matched`, exit 4), or for a plain project.

## Debug a test JVM

```bash
jk test --debug-jvm                     # localhost:5005, suspend=y — IDEA's default remote port
jk test --debug-jvm=0                   # a free port jk picks; the address is printed
jk test --debug-jvm=6006,suspend=n      # listen, but do not wait for the attach
jk test --debug-jvm='*:5005'            # every interface (remote attach)
jk test --class OrdersTest --debug-jvm  # the usual selection applies unchanged
```

The forked test JVM — and only that JVM — starts with
`-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=<host:port>`. The engine,
compiler workers and test discovery run as they always do. One line on stderr says where:

```
● Debugger listening on localhost:5005 — the JVM waits for a debugger to attach
```

Attach a stock remote debugger (IDEA *Remote JVM Debug*, VS Code `java` `attach`) to that
address; the recipe is in [IDE and BSP](ide.md#debugging-through-bsp). A debug run pins the
module to one test JVM (`-w1`) and runs modules serially, because one listener means one
JVM at a time — in a workspace each module's suite takes the address in turn, prerequisites
included, so pass `--class` with `-m <module>` to make the module you are debugging the only
suite that runs. A debug run always runs the suite, even when the cache would have skipped it.

jk settles the address before the launch: an explicit port is used as given, and `0` is a
free port bound and released by the client so the JVM can take it. Nothing reads the JVM's
own "Listening for transport" line — `jk run` hands the terminal to the program, and both the
CLI and a BSP client need the address *before* a suspended JVM exists. If another process
takes the port in the meantime, the JVM refuses to start and the run fails loudly with the
bind error; run again.

## Affected tests (WIP)

```bash
jk test --affected              # rank ≤20 classes for the working tree; print a table; do not run
jk test --affected-since=HEAD~2 # same table for ref...HEAD (does not run)
```

`--affected` is the working-tree cone (unstaged + untracked; last commit if the tree is clean).
It is **not** `--affected-since=HEAD` (that range is empty). The two flags cannot be combined.
`--aff` is ambiguous.

Both flags print the ranking as a table (Score · Class · Reason) and write
`target/jk-tests-affected.md`. They do **not** run tests and do not replace
`target/jk-results.md`. `-m` intersects the ranked list exactly as it intersects the
build cone (`jk test --affected -m api`). Ranking refuse exits **2**. When the guess
would be dishonest (`jk.toml` change, stale class files, too many types or modules, a
dirty test outside the current suite), jk prints `cannot rank affected tests` and tells
you to run `jk test`.

## Tag filters

```toml
[test]
workers = 1
exclude-tags = ["slow", "network", "bench"]

[profiles.ci]
# Auto-selected when CI is set. Keep the inner/guard excludes — do not clear them
# just because it is CI. Nightly is `jk test --all` (or a dedicated nightly profile),
# not the PR job.
exclude-tags = ["slow", "network", "bench"]
```

**Precedence** (later layer replaces an earlier list only when it *speaks* for that list):

| Layer | Behavior |
|-------|----------|
| `[test] include-tags` / `exclude-tags` | Baseline for bare `jk test` |
| Active profile (`--profile` / CI auto `ci`) | Replaces a list **only if that key is present** (including `= []` to clear) |
| `--include-tags` / `--exclude-tags` | Fully replace that list for the run |

`--exclude-tags ""` is the CLI form of a clear. A profile's `jvm-args` (`[profiles.<name>]
jvm-args = ["-Dprobe=1"]`) are appended to every forked test JVM after jk's own tuning and after
[`[test] jvm-args`](#the-test-jvms-flags-test-jvm-args-test-system-properties), and the step
prints the whole list as `test jvm-args: …`; its `javac` list reaches the compiler the same way
([Projects](projects.md#features-profiles-variants)). Suites, tags and the test JVM's flags are
part of the test stamp: changing any of them re-runs tests even if sources are unchanged. A failed suite is never
skipped: it leaves a red marker under the same stamp, so the next run executes it again and
`jk explain` prices it as a suite rather than as a stale stamp.

## The tier table partitions its vocabulary

`[test]` and the profiles together form a tier table, and the engine holds it to one invariant
when guards are on (`jk-guards.toml` present): every combination of tags in the vocabulary is run
by exactly one tier, and every `@Tag` a compiled test carries is a tag some tier owns. A tag in
`[test] exclude-tags` with no profile that includes it is a test that never executes and never
goes red; two profiles including the same tag charge one test to two budgets; a misspelt tag runs
in the fast tier by default. The check is exhaustive over the vocabulary (up to sixteen tags) and
reports under the code `tiers` — `jk guard explain tiers` describes it. The `ci` profile is the
fast tier under another name and is not a tier of its own.

## Workspace scope: tag filters come from the root

In a workspace, the tag filters are resolved **once, from the invocation root**, and apply to
every member:

- `[test] include-tags` / `exclude-tags`
- `[test] guard-suites`
- `[profiles.<name>] include-tags` / `exclude-tags`

A member's own tags are used only when the root resolved none — the root wins outright rather
than merging, so a workspace has one answer for "which tests". Running from inside a member
directory makes no difference: the CLI rehomes to the workspace root first.

Everything else under `[test]` is per-module and is never inherited, including `extra-src`,
`workers`, `assertions`, `jvm-args`, `system-properties` and `env`. This is not the `key.workspace = true` spelling that identity keys
(`jdk`, `java`, `layout`, …) and dependency versions use — the tag filters describe the
invocation, not the module.

## Module graph (`-j`)

Same as [build](build.md#parallelism--j): default all effective cores.

## Within-module workers (`-w`)

Discover test classes, then fork N runners that **pull** classes until empty.

| Value | Meaning |
|-------|---------|
| omit / `0` | **Auto:** this build's share of the jobs budget — `jobs / dirty-module width`, then `min(…, classCount)`, then heap-clamped. `jobs` is `-j` / `JK_JOBS` / `[engine] jobs`, default all cores |
| `1` | One test JVM (serial within the module) |
| `N` | Cap at N runners (still ≤ class count; heap-clamped) |

Auto is a **share**, not "as many as this module could use", because jk takes its
parallelism from modules first and the two layers spend one budget. `-j N` therefore caps
the whole build: at most N modules at once and at most N test JVMs in total. `jk explain`
prices suites on the same share the build hands out:

| Build (default `jobs` = all cores) | Dirty width | Auto workers per module |
|---|---|---|
| touched one module | 1 | all cores |
| a few modules | 4 | cores / 4 |
| full 30-module rebuild | 13 | 1 — the modules already fill the machine |
| one module, `-j 4` | 1 | 4 |

That last row is the point. Sharding *every* module of a wide build as if it were alone
costs more in JVM starts than it wins in overlap: on jk's own 30-module tree it moved the
rebuild from 73 s to 103 s, and the whole-build wall is monotone in `-w`
(73 s at `-w1`, 77 s at `-w2`, 81 s at `-w4`). Width comes from the **dirty** set, so
touching one module in a big workspace still shards wide — that is the inner loop.

An explicit `-w N` is never rescaled; it means N.

When `W > 1`, each runner gets its own `java.io.tmpdir` and its own `JK_STATE_DIR`
(nested-engine suites get distinct engine sockets).

```bash
jk test -w1          # debug flakes / one JVM per module
jk test -w4          # cap class-shard pool
```

## Cross-module tests

By default **module suites overlap**. Opt out:

```bash
jk test --serial-tests
# alias: --no-parallel-tests
```

`--parallel-tests` is accepted (affirmative no-op; default is already on).

## Per-module serial opt-out

Modules that cannot share a JVM pin workers in `jk.toml`. A positive module pin **wins**
over CLI auto / `-w N`. `workers = 0` is the same as no pin: the module takes this build's
auto share (it does not get the whole machine).

```toml
[test]
workers = 1          # serial within this module
# parallel = false   # alias for workers = 1

# Or under [build]:
# test-workers = 1
# test-parallel = false
```

When only *some* classes are unhermetic, tag them instead of pinning the whole module:

```toml
[test]
workers = 0                     # unit tier shards…
serial-tags = ["integration"]   # …these classes run on one trailing worker
```

`serial-tags` partitions at **class** level. Method-level tags inside an otherwise-untagged
class still shard with their class. When `W = 1` the setting is a no-op.

## External tools the suite shells out to (`[test] tools`)

A suite is replayed from its stamp when nothing it depends on has moved: its sources, the main
classes, the runtime classpath, the lock, the selection, `[test] env`. A test that runs `node`,
`git` or `protoc` depends on one more thing the stamp cannot see on its own — the tool. Name it:

```toml
[test]
tools = ["node"]        # by the bare name the tests invoke; a path is refused
```

Each named tool's identity — where the name resolves on the PATH the test JVM gets, and the first
line of its `--version` — is a run-tests input. Upgrading node, switching version managers, or
losing the tool from the PATH (`missing`) changes the stamp and re-runs the suite on the next
`jk test`, no `--redo` needed; a tool left alone costs one `--version` per engine lifetime. The
key is test-scoped like `env`: nothing about it enters the compile or package keys.

## Test frameworks: Jupiter by default, JUnit 4 via Vintage

`jk test` discovers and runs tests through the JUnit Platform launcher and nothing else; `jk lock`
puts `junit-platform-launcher` on every test classpath, and `junit-jupiter` on a module that
declares no `[test-dependencies]` at all. Once you own that table, the framework is yours.

The launcher follows the Jupiter you declare. Jupiter 5.x.y runs on Platform 1.x.y and Jupiter 6
shares the Platform's number, so `junit-jupiter = "5.9.0"` locks `junit-platform-launcher 1.9.0`,
`"^5.9"` locks `^1.9`, and a module with no Jupiter (or a range) takes `latest`. A Platform 6
launcher beside a Jupiter 5 engine would drop the engine without a word and the run would report
success with no tests; a lock whose launcher and Jupiter engine sit on different lines — your own
launcher pin on the wrong line — is refused by `jk lock` with the pin that aligns them.

A run that discovers no test where test classes exist fails the `run-tests` step with the count
(`no tests discovered in 3 classes under …`): an engine missing from the classpath, a framework
classloader that failed, a suite of helpers with no `@Test`. Tag filters do not trip it — a tier
with nothing in it is judged against a second discovery without the filters — and neither does
`--class`, which the run judges as a whole. A green with zero tests is never the answer.

A framework that has no Platform engine of its own gets one from the lock. Declare `junit:junit`
and `jk lock` adds `org.junit.vintage:junit-vintage-engine` beside the launcher, on the declared
Jupiter's line (`latest` when there is none) so both sit on one Platform line:

```toml
[test-dependencies]
junit = { group = "junit", version = "4.13.2" }      # @org.junit.Test suites, and JUnit 3 TestCase classes
```

The JUnit 4 you declare is the JUnit 4 the suite runs on — Vintage's own `junit:junit` edge takes
your pin, as a transitive takes a direct dependency's in Maven. Vintage refuses a JUnit older than
4.12, so an exact pin below that is refused by `jk lock` with the fix (`4.13.2`, the last release of
the line, still runs `TestCase` suites); `jk import` writes that raise for you and says so in its
notes. Results render per test as they do for Jupiter: the class from the runner, the method from
the JUnit 4 display name. Declaring the Vintage engine yourself is fine — the injection is
`putIfAbsent`, and your version wins.

### When the launcher cannot start

A forked test JVM that exits without running a test is a **launcher failure**, not a failed test:
`run-tests` fails as a step, and `jk-results.md` names the exit, the exception and the engine the
runner reported (`TestEngine with ID 'junit-jupiter' failed to discover tests`), with the fork's
output under it. The usual cause is two versions of one JUnit line on the classpath — a
`junit-platform-engine` pin off the launcher's line — and the report spells both out from the lock,
marks the declared one, and points at `jk why org.junit.platform:junit-platform-launcher`. Align
the pin with the platform line (one version for every artifact of the line) and `jk lock`. A
launcher pin on the wrong line (`junit-platform-launcher = "1.13.4"` beside `junit-jupiter 6.1.3`)
and a pin the solve itself cannot satisfy (`junit-jupiter-api = "=5.0.0"` under `junit-jupiter
6.1.3`) never get this far: `jk lock` refuses them with the fix.

## Coverage (`--coverage`, `[test] coverage`)

`jk test --coverage` starts every test JVM under the JaCoCo agent (fetched from the project's
repositories at its newest release; not a lock entry) and, per module, writes
`target/<module>/reports/jacoco.xml` and the JaCoCo HTML report at
`target/<module>/reports/coverage/index.html` (a standalone project: `target/reports/…`). The
results file gets a **Coverage** block after Tests — per module, covered lines and branches as a
percentage with the counts, an **all** row for a workspace — and a `Coverage:` line in the
headline; a workspace also gets a roll-up page at `target/reports/coverage/index.html` linking
each module's report. MCP `jk_results` carries the same block; the figures are the whole-report
`LINE` and `BRANCH` counters of each `jacoco.xml`.

The second coverage run of a project shows a **Δ** column against the previous run that measured
coverage (`_Δ vs run #41_`), read from the build journal; runs in between that measured nothing
are skipped. A module that appears for the first time reads `new`.

```toml
[test]
coverage = true          # every jk test / jk build of this module is a coverage run
```

Coverage is an inventory, not a verdict: a coverage run never fails on a number. To enforce a
floor, declare a `metric` guard on `coverage.line` (or `coverage.branch`) with `min` and a `band`
— a ratchet the baseline raises as the module climbs — and run `jk guard` after the coverage run;
the rule reads the same `jacoco.xml`. [Guards](guards.md#baseline-and-ratchets).

## Assertions in the test JVM (`[test] assertions`)

Every test JVM jk forks runs with `-ea`, as Surefire's and Gradle's do: a Java `assert` or a Kotlin
`assert()` in a test, or in the code it exercises, is a check that fails the test. Turn it off per
module when a suite relies on assertions being inert:

```toml
[test]
assertions = false      # default true
```

The setting is a run-tests input, so flipping it re-runs the suite.

## The test JVM's flags (`[test] jvm-args`, `[test] system-properties`)

What Surefire's `<argLine>` and `<systemPropertyVariables>` say, per module:

```toml
[test]
jvm-args          = ["-Xmx1g", "--add-opens", "java.base/java.lang=ALL-UNNAMED"]
system-properties = { "spring.profiles.active" = "test", "java.awt.headless" = true }
```

`jvm-args` is appended verbatim to every forked test JVM after jk's own tuning, so an `-Xmx`, an
`-Xss` or an agent here wins over the default; `system-properties` forks as one `-Dkey=value` per
entry, a number or boolean rendered as its string. A profile's `jvm-args` follow both, so the
profile wins where they disagree. The step prints the whole list as `test jvm-args: …`, and both
keys are run-tests inputs: changing either re-runs the suite. `[jvm] args` is the other knob and
reaches every worker JVM the module forks, compilers included; a value only the tests read belongs
here. `jk import` writes Surefire's `<argLine>` (minus the `${argLine}` placeholder and the JaCoCo
agent) and its system properties into these keys.

## The test JVM's thread stack

Every test JVM jk forks runs on the JVM's default thread stack, as Surefire's and Gradle's do, so a
recursive test that passes under Maven passes under jk. jk's own compiler and plugin workers run
with a smaller reserve (`-Xss512k`); the suite never inherits it. A suite that needs a deeper stack
puts `-Xss` in `[test] jvm-args`.

## Isolation contract

Tests never run in the engine process (always a forked JVM). Defaults assume tests are
hermetic enough to share a machine.

**You still must avoid:**

- Fixed ports shared across tests or modules — allocate free ports, or pin `workers = 1`
  and/or `--serial-tests` while debugging
- Shared mutable statics that assume a single suite order
- Writing outside worker temp into a shared project path without coordination
- Assuming one JVM for the whole monorepo

Failure lines include **module** (and **worker** when `W > 1`).

## JUnit in-process parallel vs `-w`

JUnit Platform `junit.jupiter.execution.parallel.enabled` is **separate** from jk `-w`
process workers. JumpKick does not turn Jupiter parallel on. Prefer **one** layer:
multi-worker `-w` **or** Jupiter parallel with **`-w1`**. When both are active, jk emits a
**warn** (`jupiter-parallel`).

## Recipes

```bash
jk test                         # default: parallel modules, auto -w
jk test -w1                     # one JVM per module
jk test --serial-tests          # one module at a time
export JK_AOT_TRAIN=off         # CI / short-lived engines
jk test -j0 -w0
```

IDE run configurations after `jk ide`: [IDE](ide.md).
