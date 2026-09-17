# $name$

A published library. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says
what to do.

```bash
jk test          # unit tier
jk build         # jar with git.properties, plus sources and javadoc jars, under target/
jk publish       # dry-run by default; see `jk manual`
```

`jk test` is the fast tier; `jk test --profile integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
