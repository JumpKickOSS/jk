# $name$

A published Scala 3 library. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says
what to do.

```bash
jk test          # unit tier
jk build         # jar with git.properties under target/
jk publish       # dry-run by default; see `jk skill`
```

Every package is `@NullMarked` (JSpecify): Scala cannot annotate a package, so its
`package-info.java` sits in `src/main/java`.

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
