# Plugin census: what Maven and Gradle projects actually plug in

**Audience:** maintainers deciding which battery is next and which tier it enters. **Status:**
measured 2026-09-16; re-run with each release so the [batteries register](../user/plugins.md)
cites a current count. The Maven import matrix in [Migration](../user/migration.md) is the same
idea over the cloned corpus; this page ranks by the whole of GitHub instead.

## The question

Batteries included only pays off if the batteries are the ones people need. The register lists
what ships and in which tier; this census asks the other question: of the plugins a Maven or
Gradle project declares, how much is covered by a core battery or a built-in behaviour, how much
is planned, how much is a gap, and how much is deliberately out.

## Method

One GitHub code-search query per plugin and file kind, counting files that name the plugin:
`pom.xml` for a Maven `artifactId`; `build.gradle` (GitHub's `Gradle` language) and
`build.gradle.kts` for a Gradle plugin id, the two summed. 156 plugin ids were queried, chosen
from the Maven import matrix, the Gradle plugin portal's well-known ids and the frameworks jk
targets. `scripts/plugin-census.sh` runs the queries and writes the raw table; the classification
in the tables below is by hand.

Read the counts as a ranking, not a measurement:

- They count files, not repositories. Tutorial and template repositories inflate some rows.
- A bare id such as `jacoco`, `antlr` or `pmd` also matches non-plugin mentions.
- The candidate list is finite. A plugin nobody thought to query is absent, not zero.
- GitHub code search returns an estimate above a few thousand hits.

**Coverage classes.** *core*: a core battery or a `jk` command does the job. *built in*: the
behaviour needs no plugin under jk (a BOM table, JDK provisioning, the build-logic hatch).
*planned*: a battery on the board. *contrib*: a contrib battery. *gap*: nothing yet. *no*: a
deliberate non-goal.

## Headline

| Ecosystem | Core + built in | Planned | Gap | No | Contrib |
|---|--:|--:|--:|--:|--:|
| Maven, top 50 | 89.6% | 1.9% | 2.1% | 6.1% | 0.3% |
| Gradle, top 50 | 29.4% | 1.4% | 0.9% | 1.2% | 67.1% |
| Gradle without the Android ids, top 50 | 89.1% | 4.1% | 3.2% | 3.6% | — |

Two thirds of all Gradle plugin use is Android; `com.android.application` alone has four times
the files of the Spring Boot plugin. jk's Android battery is contrib by the register's own
statement (not AGP parity), so that share is a known, measured exclusion rather than a target.
On the server side the two ecosystems ask for the same twenty-odd jobs under different names, and
the core set plus the planned batteries covers about ninety percent of each.

## Decisions the census supports

**The core set.** Everything the register already marks core, plus four planned batteries: the
lint step (Checkstyle, PMD, SpotBugs and detekt, which outranks SpotBugs and PMD on the Gradle
side), the generator presets (Avro, ANTLR, JAXB, jOOQ), database migrations (Flyway, Liquibase)
and TestNG. The generator presets enter as core: each is code-free sugar over the generator
worker, the tool version is the user's pin, and the promise is the invocation shape, so the
maintenance cost is a gated example per preset.

**Gaps worth closing**, each with more files than several current core rows:

| Gap | Count | Tier | Why |
|---|--:|---|---|
| JavaFX module path for compile, run and packaging | 65k Maven, 12k Gradle | contrib | desktop; larger than the Quarkus plugin, outside the daily-loop target |
| Hibernate bytecode enhancement | 8k Gradle, 2k Maven | contrib | a post-compile transformer; behaviour differs silently without it |

**Recipes, not tables.** GraphQL code generation is in the long tail on both sides (a few hundred
files for DGS codegen, under a hundred each for graphql-java-codegen and the Maven plugins;
Apollo's count is Android client code), and Spring for GraphQL, the most common JVM server, needs
no build step. It is a documented `[generate.<name>]` recipe with an example, not a battery. The
same holds for WSDL (`cxf-codegen`), MyBatis generator, jsonschema2pojo and Wire.

**Deliberate non-goals, confirmed by the count.** WAR and EAR packaging, Maven site, OSGi
bundles, Sonar upload, Jetty and Tomcat run goals, Asciidoctor, Maven plugin development, Kotlin
Multiplatform, Compose Multiplatform, IntelliJ plugin development and Develocity. Together they
are about six percent of Maven use and under four percent of server-side Gradle use. The release
flow (version bump, tag, changelog: `maven-release-plugin`, `net.researchgate.release`,
`jreleaser`, `axion-release`) is the one entry in this bucket with a real count and no jk story;
it is outer-loop work and is designed after 1.0.

## Tables

### Maven, top 50 plugin artifactIds in pom.xml

| # | Plugin | Files | Coverage | Where it lands in jk |
|--:|---|--:|---|---|
| 1 | `spring-boot-maven-plugin` | 1,695,744 | core | spring-boot battery |
| 2 | `maven-compiler-plugin` | 1,253,376 | core | [javac] |
| 3 | `maven-surefire-plugin` | 472,064 | core | jk test |
| 4 | `maven-jar-plugin` | 271,872 | core | [manifest] |
| 5 | `maven-resources-plugin` | 165,376 | core | fixed layout; filtering partial |
| 6 | `maven-assembly-plugin` | 160,768 | core | jar-with-dependencies; other descriptors no |
| 7 | `maven-war-plugin` | 146,432 | no | war packaging Tier-3 |
| 8 | `maven-shade-plugin` | 145,664 | core | [application] assembly; relocations partial |
| 9 | `exec-maven-plugin` | 144,896 | core | jk run / build-logic hatch |
| 10 | `maven-javadoc-plugin` | 135,936 | core | javadoc jar |
| 11 | `maven-deploy-plugin` | 135,424 | core | jk publish |
| 12 | `maven-clean-plugin` | 134,656 | core | jk clean |
| 13 | `maven-dependency-plugin` | 124,416 | built in | jk tree / jk why / jk sync |
| 14 | `maven-source-plugin` | 121,856 | core | sources jar |
| 15 | `jacoco-maven-plugin` | 117,760 | core | jk test --coverage |
| 16 | `maven-install-plugin` | 110,080 | core | jk install |
| 17 | `maven-failsafe-plugin` | 106,496 | core | integration suite |
| 18 | `maven-site-plugin` | 102,144 | no | site generation |
| 19 | `build-helper-maven-plugin` | 75,520 | core | extra-src; other goals row |
| 20 | `maven-gpg-plugin` | 70,144 | core | jk publish --sign |
| 21 | `maven-checkstyle-plugin` | 69,888 | planned | lint step |
| 22 | `maven-bundle-plugin` | 66,560 | no | OSGi |
| 23 | `maven-enforcer-plugin` | 65,664 | core | java =, jk deny, guards |
| 24 | `maven-antrun-plugin` | 65,536 | built in | build-logic hatch |
| 25 | `javafx-maven-plugin` | 65,280 | gap | JavaFX |
| 26 | `maven-release-plugin` | 45,440 | gap | release flow (bump, tag) |
| 27 | `nexus-staging-maven-plugin` | 35,968 | core | jk publish --central |
| 28 | `quarkus-maven-plugin` | 34,624 | core | quarkus battery |
| 29 | `kotlin-maven-plugin` | 29,184 | core | kotlin-compiler |
| 30 | `jib-maven-plugin` | 26,752 | core | jk image |
| 31 | `sonar-maven-plugin` | 24,608 | no | SaaS analysis |
| 32 | `maven-pmd-plugin` | 24,064 | planned | lint step |
| 33 | `docker-maven-plugin` | 22,016 | core | jk image |
| 34 | `jetty-maven-plugin` | 20,768 | no | app-server run (jk run covers embedded servers) |
| 35 | `frontend-maven-plugin` | 19,552 | core | [dev.sidecars] + resource module |
| 36 | `spotless-maven-plugin` | 19,008 | core | jk format |
| 37 | `tomcat7-maven-plugin` | 17,888 | no | app-server run |
| 38 | `protobuf-maven-plugin` | 17,792 | core | protobuf battery |
| 39 | `os-maven-plugin` | 17,536 | core | host-detected properties |
| 40 | `spotbugs-maven-plugin` | 16,896 | planned | lint step |
| 41 | `scala-maven-plugin` | 16,480 | contrib | Scala 3 via Zinc |
| 42 | `central-publishing-maven-plugin` | 15,840 | core | jk publish --central |
| 43 | `license-maven-plugin` | 15,264 | gap | licence headers/report |
| 44 | `versions-maven-plugin` | 12,672 | core | jk outdated / update |
| 45 | `git-commit-id-plugin` | 11,536 | core | `[build-info]`: git.properties resource |
| 46 | `liquibase-maven-plugin` | 10,912 | planned | migrations battery |
| 47 | `native-maven-plugin` | 10,352 | core | jk native |
| 48 | `dockerfile-maven-plugin` | 10,176 | core | jk image |
| 49 | `asciidoctor-maven-plugin` | 10,176 | no | docs rendering |
| 50 | `maven-plugin-plugin` | 10,144 | no | Maven plugin development |

| Coverage | Share of file hits |
|---|--:|
| core | 86.7% |
| no | 6.1% |
| built in | 2.9% |
| gap | 2.1% |
| planned | 1.9% |
| contrib | 0.3% |

### Gradle, top 50 plugin ids in build.gradle and build.gradle.kts

| # | Plugin | Files | Coverage | Where it lands in jk |
|--:|---|--:|---|---|
| 1 | `com.android.application` | 1,714,176 | contrib | android battery |
| 2 | `org.jetbrains.kotlin.android` | 474,880 | contrib | android battery |
| 3 | `org.springframework.boot` | 408,576 | core | spring-boot battery |
| 4 | `io.spring.dependency-management` | 340,608 | built in | [platform-dependencies] BOMs |
| 5 | `com.google.gms.google-services` | 330,496 | contrib | android; not covered |
| 6 | `com.android.library` | 313,344 | contrib | android battery |
| 7 | `maven-publish` | 132,800 | core | jk publish |
| 8 | `com.google.devtools.ksp` | 62,592 | core | KSP via kotlin-compiler |
| 9 | `jacoco` | 53,952 | core | jk test --coverage |
| 10 | `com.google.dagger.hilt.android` | 53,408 | contrib | android battery (Hilt) |
| 11 | `org.jetbrains.kotlin.jvm` | 49,728 | core | kotlin-compiler battery, [kotlin] |
| 12 | `com.github.johnrengelman.shadow` | 38,576 | core | [application] assembly = true (fat jar); relocation/minimize partial |
| 13 | `androidx.navigation.safeargs` | 32,928 | contrib | android; not covered |
| 14 | `org.jetbrains.kotlin.plugin.compose` | 32,184 | contrib | android battery (Compose compiler plugin) |
| 15 | `org.gradle.toolchains.foojay-resolver-convention` | 28,844 | built in | jk jdk provisioning |
| 16 | `org.jetbrains.kotlin.plugin.serialization` | 26,960 | core | [[kotlin-plugins]] |
| 17 | `checkstyle` | 24,264 | planned | lint step |
| 18 | `com.google.protobuf` | 22,672 | core | protobuf battery |
| 19 | `org.jetbrains.compose` | 20,815 | no | Compose Multiplatform desktop; not a goal |
| 20 | `com.diffplug.spotless` | 18,232 | core | jk format |
| 21 | `com.google.firebase.crashlytics` | 17,776 | contrib | android; not covered |
| 22 | `com.gradleup.shadow` | 15,248 | core | same as shadow |
| 23 | `org.jetbrains.dokka` | 14,296 | core | `[dokka]`: the javadoc jar of a Kotlin module is Dokka output |
| 24 | `org.jetbrains.kotlin.kapt` | 14,024 | core | kapt via kotlin-compiler |
| 25 | `org.openjfx.javafxplugin` | 11,956 | gap | JavaFX module path/run; module path compile exists |
| 26 | `org.jetbrains.intellij` | 11,376 | no | IDE plugin development |
| 27 | `org.sonarqube` | 11,208 | no | SaaS analysis; report upload is CI's job |
| 28 | `com.vanniktech.maven.publish` | 11,168 | core | jk publish --central |
| 29 | `org.jlleitschuh.gradle.ktlint` | 10,960 | core | jk format (ktlint) |
| 30 | `io.freefair.lombok` | 10,936 | core | processor discovery on classpath |
| 31 | `com.github.ben-manes.versions` | 9,008 | core | jk outdated / jk update |
| 32 | `io.gitlab.arturbosch.detekt` | 8,824 | planned | lint step (detekt joins it) |
| 33 | `org.hibernate.orm` | 7,932 | gap | Hibernate bytecode enhancement step |
| 34 | `com.github.spotbugs` | 7,836 | planned | lint step |
| 35 | `pmd` | 7,764 | planned | lint step |
| 36 | `org.graalvm.buildtools.native` | 7,516 | core | jk native, [native] |
| 37 | `org.jetbrains.kotlin.plugin.spring` | 6,452 | core | [[kotlin-plugins]] allopen preset |
| 38 | `antlr` | 6,260 | planned | ANTLR generator preset |
| 39 | `org.flywaydb.flyway` | 5,776 | planned | migrations battery |
| 40 | `com.google.cloud.tools.jib` | 5,600 | core | jk image, image-builder battery |
| 41 | `io.quarkus` | 5,556 | core | quarkus battery |
| 42 | `org.asciidoctor.jvm.convert` | 5,298 | no | docs rendering; not a build-tool concern |
| 43 | `de.undercouch.download` | 5,100 | built in | tool provisioning / build-logic hatch |
| 44 | `org.jetbrains.kotlin.multiplatform` | 4,468 | no | KMP is a non-goal; JVM target only |
| 45 | `io.ktor.plugin` | 4,328 | gap | fat jar exists via [application] assembly; Ktor-specific packaging/docker no |
| 46 | `java-test-fixtures` | 3,784 | core | fixtures = true edges |
| 47 | `com.github.node-gradle.node` | 3,535 | core | [dev.sidecars] + resource module (frontend) |
| 48 | `org.beryx.jlink` | 3,500 | gap | jlink runtime image; jk native/image cover the ship path differently |
| 49 | `org.jetbrains.kotlin.plugin.jpa` | 3,428 | core | [[kotlin-plugins]] noarg preset |
| 50 | `io.github.gradle-nexus.publish-plugin` | 3,292 | core | jk publish --central |

| Coverage | Share of file hits |
|---|--:|
| contrib | 67.1% |
| core | 20.9% |
| built in | 8.5% |
| planned | 1.4% |
| no | 1.2% |
| gap | 0.9% |

### Gradle without the Android ids, top 50

| # | Plugin | Files | Coverage | Where it lands in jk |
|--:|---|--:|---|---|
| 1 | `org.springframework.boot` | 408,576 | core | spring-boot battery |
| 2 | `io.spring.dependency-management` | 340,608 | built in | [platform-dependencies] BOMs |
| 3 | `maven-publish` | 132,800 | core | jk publish |
| 4 | `com.google.devtools.ksp` | 62,592 | core | KSP via kotlin-compiler |
| 5 | `jacoco` | 53,952 | core | jk test --coverage |
| 6 | `org.jetbrains.kotlin.jvm` | 49,728 | core | kotlin-compiler battery, [kotlin] |
| 7 | `com.github.johnrengelman.shadow` | 38,576 | core | [application] assembly = true (fat jar); relocation/minimize partial |
| 8 | `org.gradle.toolchains.foojay-resolver-convention` | 28,844 | built in | jk jdk provisioning |
| 9 | `org.jetbrains.kotlin.plugin.serialization` | 26,960 | core | [[kotlin-plugins]] |
| 10 | `checkstyle` | 24,264 | planned | lint step |
| 11 | `com.google.protobuf` | 22,672 | core | protobuf battery |
| 12 | `org.jetbrains.compose` | 20,815 | no | Compose Multiplatform desktop; not a goal |
| 13 | `com.diffplug.spotless` | 18,232 | core | jk format |
| 14 | `com.gradleup.shadow` | 15,248 | core | same as shadow |
| 15 | `org.jetbrains.dokka` | 14,296 | core | `[dokka]`: the javadoc jar of a Kotlin module is Dokka output |
| 16 | `org.jetbrains.kotlin.kapt` | 14,024 | core | kapt via kotlin-compiler |
| 17 | `org.openjfx.javafxplugin` | 11,956 | gap | JavaFX module path/run; module path compile exists |
| 18 | `org.jetbrains.intellij` | 11,376 | no | IDE plugin development |
| 19 | `org.sonarqube` | 11,208 | no | SaaS analysis; report upload is CI's job |
| 20 | `com.vanniktech.maven.publish` | 11,168 | core | jk publish --central |
| 21 | `org.jlleitschuh.gradle.ktlint` | 10,960 | core | jk format (ktlint) |
| 22 | `io.freefair.lombok` | 10,936 | core | processor discovery on classpath |
| 23 | `com.github.ben-manes.versions` | 9,008 | core | jk outdated / jk update |
| 24 | `io.gitlab.arturbosch.detekt` | 8,824 | planned | lint step (detekt joins it) |
| 25 | `org.hibernate.orm` | 7,932 | gap | Hibernate bytecode enhancement step |
| 26 | `com.github.spotbugs` | 7,836 | planned | lint step |
| 27 | `pmd` | 7,764 | planned | lint step |
| 28 | `org.graalvm.buildtools.native` | 7,516 | core | jk native, [native] |
| 29 | `org.jetbrains.kotlin.plugin.spring` | 6,452 | core | [[kotlin-plugins]] allopen preset |
| 30 | `antlr` | 6,260 | planned | ANTLR generator preset |
| 31 | `org.flywaydb.flyway` | 5,776 | planned | migrations battery |
| 32 | `com.google.cloud.tools.jib` | 5,600 | core | jk image, image-builder battery |
| 33 | `io.quarkus` | 5,556 | core | quarkus battery |
| 34 | `org.asciidoctor.jvm.convert` | 5,298 | no | docs rendering; not a build-tool concern |
| 35 | `de.undercouch.download` | 5,100 | built in | tool provisioning / build-logic hatch |
| 36 | `org.jetbrains.kotlin.multiplatform` | 4,468 | no | KMP is a non-goal; JVM target only |
| 37 | `io.ktor.plugin` | 4,328 | gap | fat jar exists via [application] assembly; Ktor-specific packaging/docker no |
| 38 | `java-test-fixtures` | 3,784 | core | fixtures = true edges |
| 39 | `com.github.node-gradle.node` | 3,535 | core | [dev.sidecars] + resource module (frontend) |
| 40 | `org.beryx.jlink` | 3,500 | gap | jlink runtime image; jk native/image cover the ship path differently |
| 41 | `org.jetbrains.kotlin.plugin.jpa` | 3,428 | core | [[kotlin-plugins]] noarg preset |
| 42 | `io.github.gradle-nexus.publish-plugin` | 3,292 | core | jk publish --central |
| 43 | `org.openapi.generator` | 3,130 | core | openapi battery |
| 44 | `io.micronaut.application` | 3,082 | core | micronaut battery |
| 45 | `com.adarshr.test-logger` | 3,032 | built in | jk test output |
| 46 | `com.gorylenko.gradle-git-properties` | 3,012 | core | `[build-info]`: git.properties resource (Boot info endpoint) |
| 47 | `org.jetbrains.kotlinx.kover` | 2,544 | core | jk test --coverage (JaCoCo); Kover itself not needed |
| 48 | `org.owasp.dependencycheck` | 2,501 | core | jk audit (OSV) |
| 49 | `net.ltgt.errorprone` | 2,459 | core | [javac.plugins.ErrorProne] |
| 50 | `net.researchgate.release` | 2,426 | gap | version bump + tag release flow |

| Coverage | Share of file hits |
|---|--:|
| core | 63.5% |
| built in | 25.6% |
| planned | 4.1% |
| no | 3.6% |
| gap | 3.2% |

### Below the top 50: the batteries in question

| Ecosystem | Plugin | Files | Coverage |
|---|---|--:|---|
| gradle | `nu.studer.jooq` | 1,890 | planned |
| maven | `jooq-codegen-maven` | 3,624 | planned |
| gradle | `com.github.davidmc24.gradle.plugin.avro` | 1,154 | planned |
| maven | `avro-maven-plugin` | 6,864 | planned |
| maven | `antlr4-maven-plugin` | 8,992 | planned |
| maven | `jaxb2-maven-plugin` | 4,544 | planned |
| gradle | `org.flywaydb.flyway` | 5,776 | planned |
| maven | `flyway-maven-plugin` | 9,760 | planned |
| gradle | `org.liquibase.gradle` | 1,765 | planned |
| maven | `liquibase-maven-plugin` | 10,912 | planned |
| gradle | `org.openapi.generator` | 3,130 | core |
| maven | `openapi-generator-maven-plugin` | 8,432 | core |
| maven | `git-commit-id-maven-plugin` | 6,768 | core |
| gradle | `com.gorylenko.gradle-git-properties` | 3,012 | core |
| gradle | `org.hibernate.orm` | 7,932 | gap |
| maven | `hibernate-enhance-maven-plugin` | 1,880 | gap |
| maven | `aspectj-maven-plugin` | 6,048 | gap |
| maven | `mybatis-generator-maven-plugin` | 6,496 | gap |
| maven | `cxf-codegen-plugin` | 4,096 | gap |
| maven | `moditect-maven-plugin` | 2,604 | gap |
| gradle | `me.champeau.jmh` | 2,086 | gap |
| gradle | `net.researchgate.release` | 2,426 | gap |
| gradle | `org.jreleaser` | 1,323 | gap |
| gradle | `pl.allegro.tech.build.axion-release` | 929 | gap |
| gradle | `com.netflix.dgs.codegen` | 574 | gap |
| gradle | `com.apollographql.apollo` | 1,960 | gap |
| gradle | `com.expediagroup.graphql` | 170 | gap |
| gradle | `io.github.kobylynskyi.graphql.codegen` | 88 | gap |
| maven | `graphql-maven-plugin` | 107 | gap |
| maven | `graphql-codegen-maven-plugin` | 57 | gap |
## Re-running

```bash
gh auth status                       # any logged-in account; code search is read-only
scripts/plugin-census.sh target/plugin-census.tsv
```

About half an hour at the code-search rate limit; an interrupted run resumes. Update the tables
above from the new counts, keep the classification column current with the register, and move
any row whose class changed. A plugin that climbs into a top 50 with the class *gap* is a ticket.

## Related

- [Plugins](../user/plugins.md) — the batteries register this census informs
- [Migration](../user/migration.md) — the Maven import matrix over the cloned corpus
- [The 1.0 plan](plan-1.0.md) — the ordering rule for the next battery
