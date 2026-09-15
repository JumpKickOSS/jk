# Assembly (fat) jar

`[application] assembly = true` makes `jk build` write a runnable **fat jar** beside the
thin one. This sample exists to prove the bundling actually happens, so it has a real
runtime dependency — Apache Commons Codec — and `main` cannot print its line without it.

```text
assembly-app/
  jk.toml                                # assembly = true; one bundled dep, one provided dep
  jk-lock.toml                           # committed; jk build never re-resolves
  src/main/java/demo/App.java            # prints sha256(text) via commons-codec
  src/test/java/demo/AppTest.java        # locks the digest and the printed line
```

The whole manifest:

```toml
group = "com.example"
name = "assembly-app"
version = "0.0.1"
java = 25

[application]
main     = "demo.App"
assembly = true # also writes target/assembly-app-0.0.1-all.jar

# Bundled into the fat jar — main hashes with it, so the jar cannot run without it.
[dependencies]
commons-codec = "1.22.1"

# Compile-time only (@NullMarked): present on javac's path, absent from every jar.
[provided-dependencies]
jspecify = "1.0.1"

[test-dependencies]
junit-jupiter = "6.1.3"
```

A bare version is an **exact** pin: `jk build` and `jk lock` never move it, while `"latest"`
would move under you — see [version strings](../../projects.md#version-strings).

Artifacts are **additive** — the thin jar is always written too:

| Artifact | Size here | Contains |
|----------|-----------|----------|
| `target/assembly-app-0.0.1.jar` | 2,212 B | project classes only |
| `target/assembly-app-0.0.1-all.jar` | 421,380 B | project classes **+** commons-codec |

## Run it

Output below is real, captured with `--no-ansi` so it pastes cleanly.

```console
$ jk build --no-ansi
jk: + Build > Build successful. Built target/assembly-app-0.0.1-all.jar - took 143ms

$ jk test --no-ansi
+ Test Successful: Passed 2 tests - took 47ms

$ jk assemble --no-ansi          # same work, named for the packaging step
jk: + Build > Build successful. Built target/assembly-app-0.0.1-all.jar - took 64ms

$ jk run --no-ansi
jk: > Run > Executing `java -jar target/assembly-app-0.0.1-all.jar`

sha256(assembly-app) = 7b3f920f1dd85d7741b976d4c7e8a075fc6447c3fee820cec387b93ea7499e03
```

`jk run` launches the fat jar itself. Standalone, with arguments, no classpath:

```console
$ java -jar target/assembly-app-0.0.1-all.jar
sha256(assembly-app) = 7b3f920f1dd85d7741b976d4c7e8a075fc6447c3fee820cec387b93ea7499e03

$ java -jar target/assembly-app-0.0.1-all.jar hello world
sha256(hello world) = b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9
```

Any `sha256sum` agrees, so the bundled codec is doing real work:

```console
$ printf 'assembly-app' | sha256sum
7b3f920f1dd85d7741b976d4c7e8a075fc6447c3fee820cec387b93ea7499e03  -
```

## The dependency is really inside the jar

```console
$ unzip -l target/assembly-app-0.0.1-all.jar | grep -E 'demo/App.class|DigestUtils.class'
     1348  02-01-1980 00:00   demo/App.class
    15573  02-01-1980 00:00   org/apache/commons/codec/digest/DigestUtils.class

$ unzip -l target/assembly-app-0.0.1-all.jar | grep -c 'org/apache/commons/codec/'
285
```

Two things are deliberately **not** in there:

```console
$ unzip -l target/assembly-app-0.0.1-all.jar | grep -c jspecify
0

$ unzip -l target/assembly-app-0.0.1-all.jar | grep -c 'META-INF/versions'
0
```

`jspecify` is a `[provided-dependencies]` entry — it supplies `@NullMarked` at compile time
and is packaged nowhere. And commons-codec's `META-INF/versions/9/module-info.class` is dropped,
because a JPMS descriptor inside a single classpath jar breaks it. Both are the documented
behaviour in [Packaging](../../packaging.md).

## The failure mode: the thin jar alone

The thin jar carries the same `Main-Class`, so it launches — and then cannot find the class
it never bundled. This is the whole reason the fat jar exists:

```console
$ java -jar target/assembly-app-0.0.1.jar
Exception in thread "main" java.lang.NoClassDefFoundError: org/apache/commons/codec/digest/DigestUtils
	at demo.App.describe(App.java:16)
	at demo.App.main(App.java:11)
Caused by: java.lang.ClassNotFoundException: org.apache.commons.codec.digest.DigestUtils
	at java.base/jdk.internal.loader.BuiltinClassLoader.loadClass(BuiltinClassLoader.java:580)
	at java.base/java.lang.ClassLoader.loadClass(ClassLoader.java:502)
	... 2 more
```

The thin jar is not broken — it is the artifact you publish, and its consumers resolve
commons-codec from the POM. It is only unusable *alone*.

```console
$ unzip -l target/assembly-app-0.0.1.jar
Archive:  target/assembly-app-0.0.1.jar
  Length      Date    Time    Name
---------  ---------- -----   ----
      122  02-01-1980 00:00   META-INF/MANIFEST.MF
        0  02-01-1980 00:00   demo/
     1348  02-01-1980 00:00   demo/App.class
      190  02-01-1980 00:00   demo/package-info.class
        0  02-01-1980 00:00   META-INF/
        0  02-01-1980 00:00   META-INF/sbom/
      687  02-01-1980 00:00   META-INF/sbom/application.cdx.json
---------                     -------
     2347                     7 files
```

## Regenerating the lock

`jk-lock.toml` is committed and `jk build` never re-resolves. After editing a version:

```console
$ jk lock --no-ansi
jk: + Lock > Lock successful. Resolved 11 dependencies - took 127ms
```

## Related

[Packaging](../../packaging.md) · [Projects](../../projects.md) ·
[Lockfile](../../lockfile.md) · [minified-cli](../minified-cli/) adds R8 on top of this.
