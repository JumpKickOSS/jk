# $name$

A command line program. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`) plus one ban of the project's own: `jk guard explain` lists them, and a guard
failure's `Instead:` line says what to do.

```bash
jk test          # unit tier
jk run -- Ada    # Hello, Ada!
jk build         # the jar under target/
jk image         # a container image with a JRE and an AOT cache
```

Every package is `@NullMarked` and `[nullness]` checks it with NullAway at compile time: a type is
non-null unless it says `@Nullable`.

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
