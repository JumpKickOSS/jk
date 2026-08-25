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

### The GraalVM metadata repository

Most third-party libraries publish their native-image config to the shared **GraalVM
reachability-metadata repository** rather than shipping it in their jars. jk matches every
locked runtime dependency against that repository and passes the hits to `native-image`
as `-H:ConfigurationFileDirectories`.

Which release of the repository to use is a manifest key, in the same version grammar as a
dependency:

```toml
[native]
metadata-repository = "latest"   # the default when [native] is present
# metadata-repository = "^1"     # newest stable 1.x
# metadata-repository = "=1.1.4" # exactly this release
```

`jk lock` / `jk update` resolve it and write the answer — version plus the zip's SHA-256 —
into `jk-lock.toml`'s `[native]` table. `jk outdated` shows it alongside your dependencies,
and `jk sync` materializes it for offline builds. Builds read the lock and never re-resolve,
so two machines on the same lock hand `native-image` the same config.

A workspace has one lock and therefore one pin: if several members ask for different
releases, the lock fails rather than picking one. Every module that builds a native image
contributes — including one that enables it with `[application] native = true` and no
`[native]` table — while `[native] enabled = false` contributes nothing, because it builds
no image to configure.

The extracted repository lives in the **artifact store**
(`$JK_STORE_DIR/native/metadata-repository/<version>/`), not the cache. It is a download,
not a build output, so `jk cache nuke` leaves it alone; `jk storage` is what manages it.

A lock with no `[native]` pin — one written before this existed, or a project that declares
no `[native]` table — builds without repository metadata and says so. Run `jk lock`.

## Related

[Packaging](packaging.md) · [Images](images.md) · [JDK](jdk.md)
