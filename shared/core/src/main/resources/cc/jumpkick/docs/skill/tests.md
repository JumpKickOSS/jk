# Tests

Suites choose tests, never a profile. `jk test` and `build()` run the unit suite: the inner loop.

- One class: `jk test -S C`, `build(classes=["C"])`. One suite: `-s integration`, `build(suites=["integration"])`
- Before handing work back, or after a DB, HTTP or filesystem change: `jk test -g`, `build(kind=gate)` (the `[test] guard-suites`, by default unit and integration, with the guard lanes)
- Never as a habit: `jk test -A`. After a failure, replay the same selection

`modules` on `build` (CLI `-m`) limits modules. Roots: `src/test`, `src/integration`, `src/e2e` (simple: `test/src`, …). A tag tier is `[test.suites.<name>] tags = [...]`; `jk new` writes `network`, `slow`, `bench`.

A green suite replays while its inputs hold, shell variables too. `W tests reached <host>`: it reruns every time; stub it on loopback, move it to `network`, or `[test] network = "allowed"` when the remote cannot change its result.

## Slow tests

`jk test` ends with its slowest classes and methods (1s or more). Share the costly fixture (`@BeforeAll`), seam the wait (a clock or latch, not `sleep`), or tag it `slow`. `[test] fast-method-budget = "1s"` warns on a fast-tier method over it.
