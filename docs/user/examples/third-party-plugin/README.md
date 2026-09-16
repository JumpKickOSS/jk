# third-party-plugin

A jk build plugin authored **outside** the jk tree, compiled against the published SDK
coordinate `cc.jumpkick:jk-plugin-sdk:<version>` — the shape every plugin not shipped inside jk
takes. It owns the `[hello]` table, contributes a javac flag declaratively, and carries a code
layer jk forks as a worker.

```text
jk.toml                                   # depends on cc.jumpkick:jk-plugin-sdk:<version>
src/main/resources/jk-plugin.toml         # [plugin] id/table/jk-compat, [schema], [code], [[contribute.*]]
src/main/resources/META-INF/services/cc.jumpkick.plugin.Plugin
src/main/java/com/example/hello/HelloPlugin.java
```

Build it like any library — `jk lock && jk build` — and `target/lib/hello-plugin-0.1.0.jar` is the
plugin. A consumer pins it by content:

```toml
[plugins]
hello = { path = "vendor/hello-plugin-0.1.0.jar", sha256 = "<sha256sum of the jar>" }

[hello]
greeting = "hi"
```

`jk lock` materializes the manifest, validates `[hello]` against its schema, stores the jar's
bytes under their pin and adds the plugin's SDK floor (`jk-plugin-sdk`, `jk-host` at the running
jk's version) to the lock as `plugin`-scoped rows resolved from the consumer's `[repositories]`;
the worker forks with the jar plus those rows. The javac flag applies to the consumer's compile
and is part of its compile key. The code layer forks only after `jk trust plugin path:hello` — a path pin is trusted
by its `path:<alias>` coordinate, and jk refuses untrusted third-party code with that exact
remedy.

## Where the SDK comes from

The SDK resolves like any first-party coordinate: from `https://jumpkick.build/repo/` (and from
Maven Central) once the jk release that publishes it is out. Before that release, publish the
SDK the release script does — `jk publish --repo-url file:///tmp/sdk-repo` from `shared/host` and
`shared/plugin-sdk` of a jk checkout — and lock against it: `jk lock --repo-url
file:///tmp/sdk-repo`. The integration test that drives this sample end to end
(`ThirdPartyPluginExampleTest`) does exactly that, so the sample carries no committed lock until
the SDK is served publicly.

The `jk-compat` floor in `jk-plugin.toml` is the SDK release the plugin compiled against: jk
refuses to load the plugin on an older jk with an upgrade error instead of failing inside the
worker.
