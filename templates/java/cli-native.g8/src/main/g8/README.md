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
enabled = "always"
```

then build the binary (jk provisions/uses a GraalVM JDK):

```bash
jk native
```

JLine ships GraalVM reachability metadata, and the shell falls back to a dumb terminal
where a native terminal provider is unavailable, so the native build works without extra
configuration. With `enabled = "always"`, plain `jk build` (and `jk run`) also run the
native-image step — keep it off while iterating on the JVM if you prefer faster builds.
GraalVM must be available (`GRAALVM_HOME`, a project JDK with `native-image`, or the
client-resolved Graal install used by `jk native` / `jk install`).

[JLine]: https://github.com/jline/jline3
