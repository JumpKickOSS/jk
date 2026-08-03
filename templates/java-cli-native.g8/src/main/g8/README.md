# $name$

Interactive Java CLI built on [JLine]. Scaffolded from the jk `java-cli-native` template.

```bash
jk build          # JVM build — no GraalVM required
jk run            # start the interactive shell
jk test           # unit tests for the command evaluator
```

## Native image

Native builds are opt-in per project. Enable them in `jk.toml`:

```toml
[native]
always = true
```

then build the binary (jk provisions/uses a GraalVM JDK):

```bash
jk native
```

JLine ships GraalVM reachability metadata, and the shell falls back to a dumb terminal
where a native terminal provider is unavailable, so the native build works without extra
configuration. Note `always = true` also makes plain `jk build` produce the native
binary — keep it off while iterating on the JVM if you prefer faster builds.

[JLine]: https://github.com/jline/jline3
