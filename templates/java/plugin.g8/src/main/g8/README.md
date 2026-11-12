# $name$

A jk build plugin. It owns the `[$name$]` table of a consumer's `jk.toml` and adds one command,
`jk $name$`, that prints the table's `greeting`.

```text
jk.toml                                   # compiles against cc.jumpkick:jk-plugin-sdk
src/main/resources/jk-plugin.toml         # [plugin] id, table, sdk, jk-compat; [schema]; [code]
src/main/resources/META-INF/services/cc.jumpkick.plugin.Plugin
src/main/java/.../$name;format="Camel"$Plugin.java   # the code layer jk forks as a worker
```

```bash
jk test          # unit tier
jk build         # target/$name$-0.1.0.jar is the plugin
```

`jk-plugin.toml` is data jk reads itself: the schema validates a consumer's `[$name$]` table, and
`[[contribute.*]]` tables shape a build with no code running. The class registers steps,
packagers and commands; its protocol prefix must equal `[code] protocol-prefix`.

A consumer pins the jar by content and trusts its code layer before jk forks it:

```toml
[plugins]
$name$ = { path = "vendor/$name$-0.1.0.jar", sha256 = "<sha256sum of the jar>" }

[$name$]
greeting = "hi"
```

```bash
jk trust plugin sha256:<sha256sum of the jar>
jk $name$        # hi from the $name$ plugin
```

`sdk` in `jk-plugin.toml` and the `jk-plugin-sdk` pin in `jk.toml` name the same release: a
consumer's lock pins the worker's SDK at it. `jk-compat` is the oldest jk that loads the plugin.

Every package is `@NullMarked` and `[nullness]` checks it with NullAway at compile time: a type is
non-null unless it says `@Nullable`.
