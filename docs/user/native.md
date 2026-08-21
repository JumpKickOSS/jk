# Native images

```bash
jk native
```

Builds a GraalVM **native-image** of the application using JumpKick’s JDK/toolchain
management ([JDK](jdk.md)). Prefer modules with `[native]` enabled.

```toml
[native]
enabled = "always"     # native-image on jk build and jk native
# enabled = true       # jk native only (bare [native] is the same)
# args = ["--verbose"]
```

`jk native` builds only native-eligible modules plus their dependency closure. With tests
on, the cone includes test/dev workspace deps so a dirty harness is rebuilt first.
`--skip-tests` uses production scopes only. `-m` further restricts which native targets
are considered.

Quarkus native is owned by the Quarkus plugin: JumpKick runs Quarkus’s own native-image
command with jk’s GraalVM toolchain; `[native] args` still apply. See
[Frameworks](frameworks.md#quarkus).

## Reachability

Reflection, JNI, resources, and friends: [Dynamic surface](dynamic-surface.md).
`jk train` observes a real run; **`jk build` never trains.**

## Related

[Packaging](packaging.md) · [Images](images.md) · [JDK](jdk.md)
