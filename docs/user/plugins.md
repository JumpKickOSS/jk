# Using plugins

A **plugin** teaches JumpKick a new `jk.toml` table (`[spring-boot]`, `[quarkus]`,
`[android]`, `[protobuf]`, …) and shapes the standard commands around it. You declare
*what*; JumpKick owns *when* and whether work can be skipped (action cache).

First-party plugins ship with JumpKick (Spring Boot, Quarkus, Grails, Micronaut, Android,
protobuf, formatter, test-runner, publisher, image-builder, minified, auditor, …).
Enable them by using their table and/or a [template](templates.md).

Framework how-tos: [Frameworks](frameworks.md). Format: [Format](format.md).

## Private / vendored plugins

Pre-1.0 there is **no** public plugin marketplace and `jk-plugin-sdk` is **not** published
to Maven Central. You can still pin a **private** jar (path or Maven coordinate + required
`sha256`). Authoring that jar: [contributor plugin guide](../contributors/plugins.md).

```toml
# example shape — see the contributor doc for the current keys
[plugins]
# my-plugin = { path = "tools/my-plugin.jar", sha256 = "…" }
```

The engine never classloads plugin code. Declarative `jk-plugin.toml` is applied in-process;
any code layer runs in a **forked worker**.

## Related

[Templates](templates.md) · [Build logic](build-logic.md) (project-local, not a plugin)
