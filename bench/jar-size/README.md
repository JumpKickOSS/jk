# Fat-jar size fixtures

Four small applications, each buildable three ways over the same pinned dependency set:

| Fixture | Shape | `jk.toml` | Gradle | Maven |
|---|---|---|---|---|
| `plain-cli` | picocli, Jackson, Commons Text, Guava, slf4j-simple | `assembly = true` | Shadow | Shade |
| `kotlin-cli` | Kotlin stdlib, reflect, coroutines, okio | `assembly = true` | Kotlin + Shadow | kotlin-maven-plugin + Shade |
| `micronaut-http` | Micronaut HTTP server + Jackson, platform BOM | `assembly = true` | Shadow | Shade |
| `spring-boot-web` | Spring Boot MVC starter | `assembly = true` + `[spring-boot]` | Boot `bootJar` + Shadow | Boot `repackage` + Shade |

The bench (`JarSizeBenchTest` in `server/engine`, tier `bench`) copies each fixture out of the tree,
packages it with the installed `jk`, with `./gradlew -p` and with Maven, and reports the jars side
by side with every byte of difference attributed. Results and the deflate decision are in
[docs/user/packaging.md](../../docs/user/packaging.md#fat-jar-size-against-shadow-and-shade); the
banked sizes are in [`jar-size-baseline.toml`](../../jar-size-baseline.toml).

```bash
jk test --profile bench -m server/engine --class cc.jumpkick.compile.JarSizeBenchTest
```

Every fixture is version `0.1.0`, pins exact library versions (the committed `jk-lock.toml` is what
`jk build` uses), and spells the same versions in the Gradle build's `gradle/libs.versions.toml` and in `pom.xml`. Shadow 9 drops
duplicate entries before its transformers run, so each Gradle build sets
`duplicatesStrategy = INCLUDE` for the paths it merges; Shade uses the equivalent transformers. Both
merge the same `META-INF` files jk merges, and nothing else. Bump a version in all three files and
re-run `jk lock` in the fixture directory to re-pin.
