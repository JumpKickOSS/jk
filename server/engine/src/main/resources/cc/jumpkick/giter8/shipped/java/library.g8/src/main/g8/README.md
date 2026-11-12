# $name$

A published library. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says
what to do.

```bash
jk test          # unit tier
jk build         # jar with git.properties, plus sources and javadoc jars, under target/
jk publish       # dry-run by default; see `jk skill`
```

Every package is `@NullMarked` and `[nullness]` checks it with NullAway at compile time: a type is
non-null unless it says `@Nullable`.

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
