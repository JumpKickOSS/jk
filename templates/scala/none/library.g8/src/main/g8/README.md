# $name$

A published Scala 3 library. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says
what to do.

```bash
jk test          # unit tier
jk build         # jar with git.properties, plus sources and javadoc jars, under target/
jk publish       # dry-run by default; see `jk skill`
```

The javadoc jar is scaladoc's site, built by the scaladoc release that matches the Scala
compiler, so the two move together; the sources jar holds the `.scala` files.

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
