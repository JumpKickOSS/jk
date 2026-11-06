# $name$

An application in Scala 3. The house rules come from the `library` starter pack (`jk-guards.toml`,
`[guards] extends`): `jk guard explain` lists them, and a guard failure's `Instead:` line says what
to do.

```bash
jk test          # unit tier
jk run           # Hello from $name$
jk build         # the jar under target/
$if(native.truthy)$
jk native        # a native executable under target/
$else$
jk image         # a container image with a JRE and an AOT cache
$endif$
```

Every package is `@NullMarked` (JSpecify): Scala cannot annotate a package, so its
`package-info.java` sits in the Java sources.

`jk test` is the fast tier; `jk test -p integration` (or `network`, `slow`, `bench`) runs the
tests tagged with that cost. `jk add <library>` adds a dependency at its newest stable release.
