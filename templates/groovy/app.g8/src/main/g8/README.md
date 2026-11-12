# $name$

An application in Groovy. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says what
to do.

```bash
jk test          # unit tier
jk run           # Hello from $name$
jk build         # the jar under target/
jk image         # a container image with a JRE and an AOT cache
```

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost. `jk add <library>` adds a dependency at its newest stable release.
