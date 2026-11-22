# Tests

Suites choose tests, never a profile. `jk test` and `build()` run the unit suite: the inner loop.

- One class: `jk test -S C`, `build(classes=["C"])`. One suite: `-s integration`, `build(suites=["integration"])`
- Before handing work back, or after a DB, HTTP or file change: `jk test -g`, `build(kind=gate)` (`[test] guard-suites`, unit and integration by default, and the guard lanes)
- Never as a habit: `jk test -A`. After a failure, replay the same selection

`modules` on `build` (CLI `-m`) limits modules. Roots: `src/test`, `src/integration`, `src/e2e` (simple: `test/src`, …). A tag tier is `[test.suites.<name>] tags = [...]`.

A green suite replays while its inputs hold. `W tests reached <host>`: it reruns every time; stub it on loopback, tag it `network`, or `[test] network = "allowed"` if the remote cannot change it.

Slow: `jk test` ends naming those of 1s+. Share fixtures (`@BeforeAll`), wait on a clock or latch (not `sleep`), or tag it `slow`.

## Flaky tests

`quarantined flaky test failed, not your change: C` ends a passing verdict: C is a known flake (`.jk/test/quarantine.toml`); do not chase it. `jk flaky list|show|issue|release`, `flaky(action=…)`*.
