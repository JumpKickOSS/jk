# Tests

Default `jk test` and `run(kind=test)` run the unit suite only. That is the inner loop.

- Fixing a unit: `jk test`
- About to push, or the change touches DB, HTTP, or the filesystem: `jk test --guard`
- One named suite: `jk test --suite integration`
- Not as a habit: `jk test --all`

MCP climbs with `suites=["integration"]`. Do not pass every suite. Replay the same selection after a failure.

Traditional roots are `src/test`. Simple layout uses `test/src`. Named suites are `src/integration` and `src/e2e` (or `integration/src` and `e2e/src`). Tag expensive tests `slow`, `network`, or `bench`.

`only` on `run` limits modules. The CLI flag is `-m`.
