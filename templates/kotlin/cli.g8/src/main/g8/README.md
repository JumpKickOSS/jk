# $name$

A command line program in Kotlin. No framework pack fits a plain program, so `jk-guards.toml` is a
small house baseline of its own: a `file-size` cap, and a `forbid` that proves it bites against
`guard-fixtures/no-soft-reference/`. `jk guard explain` lists them, and a guard failure's
`Instead:` line says what to do.

```bash
jk test          # unit tier
jk run           # Hello from $name$
jk build         # the jar under target/
jk image         # a container image with a JRE and an AOT cache
```

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
