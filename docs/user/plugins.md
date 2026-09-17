# Using plugins

A **plugin** teaches JumpKick a new `jk.toml` table (`[spring-boot]`, `[quarkus]`,
`[android]`, `[protobuf]`, …) and shapes the standard commands around it. You declare
*what*; JumpKick owns *when* and whether work can be skipped (action cache).

First-party plugins ship with JumpKick (Spring Boot, Quarkus, Grails, Micronaut, Android,
protobuf, generator, openapi, formatter, test-runner, publisher, image-builder, minified, auditor, …).
Enable them by using their table and/or a [template](templates.md).

## Batteries and their tiers

Batteries come in two tiers. **Core** batteries ship with every release, are exercised by jk's
own self-host build, and a regression in one blocks a release. **Contrib** batteries are
best-effort: they ship in the same binary, their tests run, but a framework release that breaks
one does not hold jk back, and their docs say so. Nothing moves tier silently; this table is the
register.

| Battery | Owns | Tier | Why that tier |
|---|---|---|---|
| java-compiler | `javac` / Zinc incremental compile, annotation processors, AOT-warmed worker | core | every build |
| kotlin-compiler | `[kotlin]`, K2, KSP, `[[kotlin-plugins]]` | core | every Kotlin build |
| groovy-compiler | `[groovy]`, joint compilation | core | Grails and Spock paths |
| test-runner | JUnit Platform suites (Jupiter; JUnit 4 via Vintage), tags, profiles (tag filters, test-JVM args), `-ea` test JVMs, coverage (`--coverage` / `[test] coverage`: JaCoCo XML + HTML per module, Coverage block in `jk-results.md`), launcher failures as failed steps | core | every `jk test` |
| formatter | `jk format` | core | closes the agent edit loop |
| publisher | `jk publish`: Maven layout, GPG, Sigstore, SLSA, CycloneDX/SPDX | core | the ship path for libraries |
| image-builder | `jk image`: OCI images, JRE base, AOT cache | core | the ship path for services |
| auditor | `jk audit` (OSV), `jk deny` | core | supply-chain defaults |
| minified | `[minified]`: R8 classfile-mode slim jar | core | `jk native` and image size |
| spring-boot | `[spring-boot]`: Boot jar, platform BOM, AOT step | core | the largest server segment |
| quarkus | `[quarkus]`: augmentation, fast-jar, native via Quarkus | core | second server segment |
| micronaut | `[micronaut]`: platform BOM, AOT | core | third server segment; Test Resources is not part of it before 1.0 |
| protobuf | `[protobuf]`: provisioned `protoc`, Java + Kotlin codegen, protoc plugins (`[protobuf.grpc-java]`) | core | the native-binary generator |
| generator | `[generate.<name>]`: any JVM code generator as a cached generate-stage step | core | one worker behind every generator table |
| openapi | `[openapi]`: OpenAPI Generator over a contract, interface-only Spring by default | core | most Spring services ship a contract |
| build-info | `[build-info]`: `git.properties` and Boot's `build-info.properties` as cached resources in the jar | core | Boot's `/info` endpoint on every imported service; the git-commit-id plugins' consumers |
| android | `[android]`: resources, manifest, dex/R8, signing, APK/AAB, Hilt | **contrib** | not AGP parity; AGP moves monthly — keep `jk gradle` for full AGP |
| grails | `[grails]`: Grails 8 on the Groovy lane | **contrib** | tracks an 8.x milestone; `latest` would pick Grails 7 |
| Scala 3 | mixed Java/Scala modules through Zinc | **contrib** | compiles; no cross-building, Scala.js/Native or sbt parity |

The next core battery is whichever step most Spring and Kotlin services touch every day —
coverage in the results file, sources and javadoc jars, Central Portal publishing, lint as a
cached step — in the order set by [the 1.0 plan](../contributors/plan-1.0.md).

Which batteries matter is measured, not guessed: the [plugin census](../contributors/plugin-census.md)
ranks the Maven and Gradle plugins GitHub projects declare and classifies each against this
register. Core plus the planned batteries covers about ninety percent of Maven plugin use and of
server-side Gradle use; the census names the gaps, the recipes and the deliberate non-goals.

Framework how-tos: [Frameworks](frameworks.md). Generators: [Generate](generate.md). Format:
[Format](format.md).

## More than one plugin in a module

A module runs every plugin whose table it declares; each plugin with a code layer forks its own
worker, and their steps join one plan. What a module may hold is decided by capability:

| Capability | Per module | Examples |
|---|---|---|
| Steps and generators | any number | `[openapi]`, `[generate]`, `[protobuf]` beside a framework table |
| Packager replacing the main artifact | exactly one at most | `[spring-boot]`, `[quarkus]`, `[grails]`, `[android]` |
| Packager writing beside the main artifact | any number | `[minified]` |

So `[openapi]` plus `[spring-boot]` in one module builds — the generated interface compiles into
the Boot jar ([examples/openapi-spring](examples/openapi-spring/)) — while `[spring-boot]` plus
`[quarkus]` is refused by name: two jars would each claim to be the module's one output. Step
and command names are one namespace across a module's plugins; two plugins registering the same
name is refused the same way.

## Third-party and vendored plugins

The plugin SDK, `cc.jumpkick:jk-plugin-sdk`, is a published coordinate: every jk release ships it
(with `jk-host`, its one dependency) to `https://jumpkick.build/repo/` and to Maven Central through
`jk publish --central` ([releases](../contributors/releases.md)), at the release's own version. A
plugin authored outside the jk tree depends on that coordinate and declares the jk it needs with a
`jk-compat` floor — [examples/third-party-plugin](examples/third-party-plugin/) is the complete
shape, and the [contributor plugin guide](../contributors/plugins.md) the reference. Pre-1.0 the
SPI is still additive-only by intent rather than by contract, and there is **no** public plugin
marketplace: a consumer pins a plugin jar by content (path or Maven coordinate + required
`sha256`).

```toml
# example shape — see the contributor doc for the current keys
[plugins]
# my-plugin = { path = "tools/my-plugin.jar", sha256 = "…" }
```

A private jar keeps its `sha256` pin across jk upgrades: the lock's `[[plugin]]` row is the
declaration's digest, and a jar that disagrees with it is refused. Its worker forks with the SDK
it compiled against: `jk lock` adds `jk-plugin-sdk` and `jk-host` at the version the plugin's
manifest names (`[plugin] sdk`) to the lock as `plugin`-scoped rows, resolved from your
`[repositories]`; a manifest that names none is pinned at the running jk's version, and the lock
says so in a note. That scope is the lock's own — a `[plugin-dependencies]` table in `jk.toml`
is refused. The first-party plugins that
ship inside jk are pinned the other way round — their rows follow the running jk
([Lockfile](lockfile.md#what-else-the-lock-pins)).

The engine never classloads plugin code. Declarative `jk-plugin.toml` is applied in-process;
any code layer runs in a **forked worker**.

## Related

[Templates](templates.md) · [Build logic](build-logic.md) (project-local, not a plugin)
