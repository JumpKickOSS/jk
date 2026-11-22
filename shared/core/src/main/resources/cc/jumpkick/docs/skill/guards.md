# Guards

`jk-guards.toml` holds house rules. A failure's code is a rule id. Fix the site the message points at. An exemption is an `allow` entry with a reason, and that is the user's call. Do not edit `jk-guards-baseline.toml`. Do not add a suppression comment. There is no such syntax.

`jk guard explain <id>` says what to do instead. `jk guard explain --schema <kind>` lists that kind's keys. `jk guard explain --schema guard-test` is the skeleton for a check TOML cannot say. Kinds: forbid, annotate, classes, layers, cycles, coupling, split-package, api, depend, toolchain, tiers, text, metric, vocabulary, parity, generated, output, commit, test, statics.

Stop and ask when the fix is an exemption, the message says thrash or names `jk guard freeze <id> -W "…"`, or the outcome is `blind`, `owner-missing`, `stale-allow`, `no-bite`, or `scanner-failed`.

`jk guard` (`build(kind=guard)`) runs every lane, no tests. `jk test -g` (`build(kind=gate)`) runs them with the guard suites and is the bar before handing work back; with `[format] enforce` both format what changed first.
