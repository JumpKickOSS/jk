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
jk test --coverage                   # every suite JVM under the JaCoCo agent; reports/jacoco.xml per module
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

`--exclude-tags ""` is the CLI form of a clear. Suites and tags are part of the test
stamp: changing selection re-runs tests even if sources are unchanged. A failed suite is never
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
`workers` and `env`. This is not the `key.workspace = true` spelling that identity keys
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
