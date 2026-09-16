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
| protobuf | `[protobuf]`: provisioned `protoc`, Java + Kotlin codegen | core | the native-binary generator |
| generator | `[generate.<name>]`: any JVM code generator as a cached generate-stage step | core | one worker behind every generator table |
| openapi | `[openapi]`: OpenAPI Generator over a contract, interface-only Spring by default | core | most Spring services ship a contract |
| android | `[android]`: resources, manifest, dex/R8, signing, APK/AAB, Hilt | **contrib** | not AGP parity; AGP moves monthly — keep `jk gradle` for full AGP |
| grails | `[grails]`: Grails 8 on the Groovy lane | **contrib** | tracks an 8.x milestone; `latest` would pick Grails 7 |
| Scala 3 | mixed Java/Scala modules through Zinc | **contrib** | compiles; no cross-building, Scala.js/Native or sbt parity |

The next core battery is whichever step most Spring and Kotlin services touch every day —
coverage in the results file, sources and javadoc jars, Central Portal publishing, lint as a
cached step — in the order set by [the 1.0 plan](../contributors/plan-1.0.md).

Framework how-tos: [Frameworks](frameworks.md). Generators: [Generate](generate.md). Format:
[Format](format.md).

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
declaration's digest, and a jar that disagrees with it is refused. The first-party plugins that
ship inside jk are pinned the other way round — their rows follow the running jk
([Lockfile](lockfile.md#what-else-the-lock-pins)).

The engine never classloads plugin code. Declarative `jk-plugin.toml` is applied in-process;
any code layer runs in a **forked worker**.

## Related

[Templates](templates.md) · [Build logic](build-logic.md) (project-local, not a plugin)
