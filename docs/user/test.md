# Test

JumpKick is opinionated about **when** to run tests, not only how. Write a pyramid
(many unit, fewer integration, few e2e). Run a ladder: climb only as high as this
turn requires. Product bet: [Why JumpKick](why.md#test-rungs-the-execute-moat).

```bash
jk test                              # unit rung — default suite only. Inner loop.
jk test --gate                       # share-the-commit: unit + integration (if present) + gate scripts
jk test --pre-merge                  # silent alias of --gate
jk test --scripts-only               # gate scripts, no JUnit
jk test --gate --no-scripts          # gate suites, skip extra scripts
jk test --suite integration          # -s is the short form. Climb one named suite.
jk test --suite e2e                  # UI / compose / contract; not a habit
jk test --all                        # every suite; tag excludes cleared. Nightly / release.
jk test --exclude-tags slow,bench
jk test --include-tags smoke
jk test --affected                   # rank ≤20 classes/module for the working tree and run them
jk test --affected-since=origin/main # modules changed since the ref; all their tests
jk build --gate                      # package with the gate green
jk build --all                       # package with the full suite green
```

`--affected` selects the working-tree module cone and ranks at most 20 test **classes per
module**. `--affected-since=<ref>` is the commit-range cone and runs **all** tests in those
modules. They cannot be combined (`--aff` is ambiguous). Ranked output:
`target/jk-tests-affected.md`. Refuse exits **2**; that file is not `jk-results.md`.

`--all` is **not** the inner loop. Agents and humans fixing a unit assertion should
run `jk test`, not `--all`. Before you share a commit, run **`--gate`** (silent
alias `--pre-merge`): unit + `integration` when that directory exists. Tag excludes
from `[test]` still apply (`slow` / `network` / `bench` stay out). `--gate` cannot
combine with `--all`. `--suite` wins over `--gate` (a warning is printed).

Override the gate suite list:

```toml
[test]
gate-suites = ["test", "integration", "contract"]
```

Unknown names in `gate-suites` fail with the discovered-suite list. Missing
`integration` on the default list is not an error.

Canonical extra suite **names** are `integration` and `e2e`. Any other suite
directory still works (`contract`, `mutation`, …) — [layout](layout.md). Cost that
crosses a suite (`slow`, `network`, `bench`) is a **JUnit tag**, not a fourth
directory.

`--scripts-only` and `--no-scripts` cannot be combined. `--scripts-only` with no
`gate` stem is a config error. Same flags on `jk build`. See [build logic](build-logic.md).

`--all` and `--suite` cannot be combined. `--all` and `--gate` cannot be combined.
Unknown suite names error with the available list (`--gate`'s default `integration`
is the exception: skip if absent). `--all` means “everything”: every suite directory
**and** cleared `[test]` / profile tag excludes. Explicit `--include-tags` /
`--exclude-tags` still compose on top. `jk build` accepts the same selection flags.

Default-suite paths depend on [layout](layout.md) (`src/test/…` vs `test/src/`). Named
suites are discovered when those directories exist.

When tests fail: `jk results` — [Troubleshooting](troubleshooting.md).

## Affected tests (WIP)

```bash
jk test --affected              # rank ≤20 classes/module for the working tree, run them
jk test --affected-since=origin/main   # modules changed since the ref; all tests in those modules
```

`--affected` is the working-tree cone (unstaged + untracked; last commit if the tree is clean).
It is **not** `--affected-since=HEAD` (that range is empty). The two flags cannot be combined.
`--aff` is ambiguous.

The ranked list is written to `target/jk-tests-affected.md` (modules + tests, or REFUSED).
It does not replace `target/jk-results.md`. Ranking refuse exits **2**; test failures **4**.
When the guess would be dishonest (`jk.toml` change, too many types, a dirty test outside the
current suite), jk prints `cannot rank affected tests` and tells you to run `jk test`.

## Tag filters

```toml
[test]
workers = 1
exclude-tags = ["slow", "network", "bench"]

[profiles.ci]
# Auto-selected when CI is set. Keep the inner/gate excludes — do not clear them
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
stamp: changing selection re-runs tests even if sources are unchanged.

## Module graph (`-j`)

Same as [build](build.md#parallelism--j): default all effective cores.

## Within-module workers (`-w`)

Discover test classes, then fork N runners that **pull** classes until empty.

| Value | Meaning |
|-------|---------|
| omit / `0` | **Auto:** `min(jobs, classCount)`, then heap-clamped |
| `1` | One test JVM (serial within the module) |
| `N` | Cap at N runners (still ≤ class count; heap-clamped) |

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

Modules that cannot share a JVM pin workers in `jk.toml`. The module pin **wins** over
CLI auto / `-w N`.

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
