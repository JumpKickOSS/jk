# Tests

Default `jk test` and `run(kind=test)` run the unit suite only. That is the inner loop.

- Fixing a unit: `jk test`
- About to push, or the change touches DB, HTTP, or the filesystem: `jk test -g`
- One named suite: `jk test -s integration`
- Not as a habit: `jk test -A`

MCP climbs with `suites=["integration"]`. Do not pass every suite. Replay the same selection after a failure.

Traditional roots are `src/test`. Simple layout uses `test/src`. Named suites are `src/integration` and `src/e2e` (or `integration/src` and `e2e/src`). Tag expensive tests `slow`, `network`, or `bench`.

`only` on `run` limits modules. The CLI flag is `-m`.

## Slow tests

`jk test` ends with its slowest classes and methods (1s or more), each with module and tier: on the terminal, as a `slowest-tests` line under `-O jsonl`, and in `jk results`. Speed one up: share the costly fixture (`@BeforeAll`, one container per class), seam the wait (a clock or latch, not `sleep`), or tag it `integration` or `slow`. `[test] fast-method-budget = "1s"` warns on a fast-tier method over it.
