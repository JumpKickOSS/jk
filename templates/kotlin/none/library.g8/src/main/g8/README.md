# $name$

A published Kotlin library. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says
what to do.

```bash
jk test          # unit tier
jk build         # jar with git.properties, plus sources and javadoc jars, under target/
jk publish       # dry-run by default; see `jk manual`
```

The javadoc jar is Dokka's javadoc-format output, the form a Maven Central release expects;
`[dokka] version` names the Dokka release and `jk update` moves it.

`jk test` is the fast tier; `jk test --profile integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost.
