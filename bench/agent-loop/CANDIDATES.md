# Candidate repositories

Every repository tried for the corpus, with the baseline outcome under its own tool (via
`jk mvn` / `jk gradle`, JDK 25) and under jk 0.13.7 after `jk import` with no hand edits. A repo
is accepted only when both are green; each reject names the first thing that broke, quoted from
the tool. Recorded 2026-09-15 against the commit the clone was pinned to on that day.

## Accepted (19)

| Repo | Source | Own tool | jk after import |
|---|---|---|---|
| gs-rest-service | spring-guides/gs-rest-service `complete/` | Gradle 23s, Maven 4s | 14s, 2 tests |
| gs-spring-boot | spring-guides/gs-spring-boot `complete/` | Gradle 14s, Maven 11s | 7s, 2 tests |
| gs-accessing-data-jpa | spring-guides/gs-accessing-data-jpa `complete/` | Gradle 11s, Maven 25s | 9s, 1 test |
| gs-accessing-data-r2dbc | spring-guides/gs-accessing-data-r2dbc `complete/` | Gradle 10s, Maven 9s | 7s, 1 test |
| gs-accessing-data-rest | spring-guides/gs-accessing-data-rest `complete/` | Gradle 9s, Maven 12s | 12s, 7 tests |
| gs-actuator-service | spring-guides/gs-actuator-service `complete/` | Gradle 12s, Maven 17s | 4s, 2 tests |
| gs-batch-processing | spring-guides/gs-batch-processing `complete/` | Gradle 17s, Maven 7s | 5s, 1 test |
| gs-consuming-rest | spring-guides/gs-consuming-rest `complete/` | Gradle 16s, Maven 4s | 3s, 1 test |
| gs-graphql-server | spring-guides/gs-graphql-server `complete/` | Gradle 11s (no `pom.xml`) | 6s, 2 tests |
| gs-handling-form-submission | spring-guides/gs-handling-form-submission `complete/` | Gradle 17s (Maven: see below) | 3s, 2 tests |
| gs-reactive-rest-service | spring-guides/gs-reactive-rest-service `complete/` | Gradle 12s, Maven 7s | 11s, 1 test |
| gs-securing-web | spring-guides/gs-securing-web `complete/` | Gradle 15s (Maven: see below) | 6s, 5 tests |
| gs-serving-web-content | spring-guides/gs-serving-web-content `complete/` | Gradle 12s (Maven: see below) | 5s, 3 tests |
| gs-testing-web | spring-guides/gs-testing-web `complete/` | Gradle 38s, Maven 6s | 10s, 5 tests |
| gs-uploading-files | spring-guides/gs-uploading-files `complete/` | Gradle 22s, Maven 5s | 4s, 12 tests |
| gs-validating-form-input | spring-guides/gs-validating-form-input `complete/` | Gradle 14s, Maven 5s | 20s, 5 tests |
| gs-rest-hateoas | spring-guides/gs-rest-hateoas `complete/` | Gradle 7s, Maven 4s | 7s, 2 tests |
| gs-scheduling-tasks | spring-guides/gs-scheduling-tasks | Gradle 19s (no `pom.xml`) | 14s, 2 tests |
| junit-starter-gradle | junit-team/junit-examples `junit-jupiter-starter-gradle/` | Gradle 5s | 1s, 5 tests |

Walls are the first cold-sandbox run with warm dependency caches. Three accepted guides carry a
`pom.xml` that is not in their tool list:

- **gs-handling-form-submission, gs-serving-web-content** — their `.mvn/wrapper` pins Maven 3.6.3
  and `jk mvn` refuses it: `maven distribution …/apache-maven-3.6.3-bin.zip cannot be verified: no
  .sha512 checksum is published beside it`. The repo's own wrapper works; jk's provisioning does not
  accept a distribution older than the checksum convention.
- **gs-securing-web** — green under Gradle, red under Maven with
  `SecuringWebApplicationTests.accessSecuredResourceAuthenticatedThenOk … jakarta.servlet.ServletException:
  Circular view path [hello]`; a genuine build-definition difference between the guide's two
  builds, not a tooling fault.

## Rejected: jk import or jk build (27)

The message is what `jk test` printed after `jk import`; `→` names the import gap.

| Repo | Own tool | jk after import |
|---|---|---|
| spring-projects/spring-petclinic | Maven red: `MySqlIntegrationTests` needs Docker (Testcontainers); `PetClinicConcurrencyTests` fails | `No versions of javax.cache:cache-api match unresolved` → parent/BOM-managed versions import as `version = "unresolved"` |
| spring-petclinic/spring-petclinic-rest | Maven red: `openapi-generator-maven-plugin … Failed to read artifact descriptor for com.github.joschi.jackson:jackson-datatype-threetenbp:jar:2.18.2` | `No versions of tools.jackson.core:jackson-core match unresolved` → BOM-managed versions |
| quarkusio/quarkus-quickstarts `getting-started/` | Maven green 6s | `No versions of io.quarkus:quarkus-rest match unresolved` → `quarkus-bom` import scope not applied to the deps it manages |
| jhy/jsoup | Maven green 19s | `No versions of io.netty:netty-codec-http match unresolved` → the `netty-bom` lands in `[platform-dependencies]` but the deps it manages still get `version = "unresolved"` |
| square/javapoet | Maven red on JDK 25: `bad class file: /modules/java.base/java/io/IOException.class … class file has wrong version 69.0, should be 53.0` (the pom compiles with `-source/-target 8`) | `test discovery exited 70` → JUnit 4 suite; no vintage engine is wired, the launcher throws `Cannot create Launcher without at least one TestEngine` |
| google/gson | Maven red: `test-jpms/src/test/java/module-info.java:[19,22] module not found: com.google.gson` under the reactor's test order on JDK 25 | `jdk = 8 is not supported — jk targets JDK 17 and above` → `maven.compiler.source` 8 imported as `jdk = "8"` / `java = 8` |
| apache/commons-cli | Maven green 9s | `No versions of org.junit.jupiter:junit-jupiter-api match unresolved` → `junit-bom` managed versions |
| apache/commons-csv | Maven green 8s | `Illegal character in path at index 26: org/openjdk/jmh/jmh-core/${commons.jmh.version}/…` → a `${property}` version imported literally |
| apache/commons-text | Maven green 39s | `No versions of org.junit.jupiter:junit-jupiter match unresolved` → BOM-managed versions |
| apache/commons-codec | Maven green 32s | `No versions of org.junit.jupiter:junit-jupiter-engine match unresolved` → BOM-managed versions |
| google/jimfs | Maven green 11s | `No versions of com.google.auto.service:auto-service-annotations match unresolved` → parent-managed versions (`jimfs-parent`) |
| DiUS/java-faker | Maven red on JDK 25: `Source option 6 is no longer supported. Use 8 or later.` | `test discovery exited 70` → JUnit 4 |
| datafaker-net/datafaker | Maven green 17s | `Compile Test Failure … Unresolved reference 'Faker'` in `src/test/kotlin/…/SchemaExampleTest.kt` → Kotlin test sources compiled without the main Java classes on the path (kotlin-maven-plugin `test-compile`) |
| mapstruct/mapstruct-examples `mapstruct-lombok/` | Maven red on JDK 25: `Fatal error compiling: java.lang.ExceptionInInitializerError` (old Lombok) | `cannot find symbol: method setTest(java.lang.String)` in `Main.java:15` → Lombok / MapStruct processors not wired from `annotationProcessorPaths` |
| awaitility/awaitility | Maven red 103s: `gmavenplus-plugin:1.7.1:compile … on project awaitility-groovy` | `No versions of org.hamcrest:hamcrest match unresolved` → parent-managed versions |
| junit-team/junit-examples `junit-jupiter-starter-maven/` | Maven green 2s | `No versions of org.junit.jupiter:junit-jupiter match unresolved` → `junit-bom` |
| TheAlgorithms/Java | Maven green 37s | `No versions of org.junit.jupiter:junit-jupiter match unresolved` → `junit-bom` |
| JodaOrg/joda-time | Maven red on JDK 25: `[options] bootstrap class path is not set in conjunction with -source 5` | `test discovery exited 70` → JUnit 4 |
| spring-petclinic/spring-petclinic-kotlin | Gradle green 42s | `environment references are not allowed here: runtime-dependencies.webjars-locator-lite.version (${webjarsLocatorLiteVersion})` → Gradle `${property}` / `$property` versions imported literally |
| junit-team/junit-examples `junit-jupiter-starter-gradle-kotlin/` | Gradle green 20s | `FAILED CalculatorTests.divisionByZeroError() … Expected java.lang.AssertionError to be thrown, but nothing was thrown` → the code under test uses Kotlin `assert()`; Gradle and Surefire run tests with `-ea`, jk's test JVM does not |
| charleskorn/kaml | Gradle red 67s (Kotlin/JDK 25 toolchain) | `jdk = 11 is not supported` → `jvmToolchain(11)` imported as a JDK pin |
| jillesvangurp/kotlin4example | Gradle red 55s | `No versions of io.github.microutils:kotlin-logging match _` → Gradle `"_"` (refreshVersions placeholder) imported as the version |
| junit-pioneer/junit-pioneer | Gradle red 27s | `POM not found in any declared repo: org.junit:junit-bom:$junitVersion` → `$property` in a `platform()` coordinate imported literally |
| mapstruct/mapstruct-examples `mapstruct-on-gradle/` | Gradle red 11s (old MapStruct on JDK 25) | `environment references are not allowed here: dependencies.mapstruct.version (${mapstructVersion})` → `${property}` |
| junit-team/junit-examples `junit-jupiter-extensions/` | Gradle green 5s | `package org.junit.jupiter.api.extension does not exist` in `CartesianProductContext.java:15` → a `compileOnly`/`api` split the import drops |
| spring-guides/gs-producing-web-service `complete/` | Gradle green 13s | `package io.spring.guides.gs_producing_web_service does not exist` → the `jaxb2` generated-source step (`genJaxb`) is not imported |
| spring-guides/tut-spring-boot-kotlin | Gradle red 40s: `compileKotlin … Compilation error` (Kotlin 2.2 on JDK 25) | `jk requires Kotlin 2.4.10 or newer, but the project targets 2.2.21` |

## Rejected: no usable baseline or no tests (6)

| Repo | Why |
|---|---|
| spring-guides/gs-async-method `complete/` | green everywhere, but `complete/` has no test sources (`No tests`) — nothing to break |
| spring-guides/gs-managing-transactions `complete/` | same, no test sources |
| spring-guides/gs-caching `complete/` | same, no test sources |
| spring-guides/gs-relational-data-access `complete/` | same, no test sources |
| spring-guides/tut-rest `links/`, `evolution/` | Maven-only steps with `spring-boot-starter-parent` → BOM-managed versions (`unresolved`) |
| micronaut-projects/* | no small public Micronaut example with a real test suite: `micronaut-examples` is empty, `micronaut-guides` is a 3 GB monorepo |

## What the rejects say about `jk import`

Ordered by how many candidates each gap cost:

1. **Versions managed by a parent or an imported BOM** (`<dependencyManagement>` with
   `<scope>import</scope>`, `spring-boot-starter-parent`, `junit-bom`, `netty-bom`, `quarkus-bom`):
   the dep is written with `version = "unresolved"` even when the BOM itself is written into
   `[platform-dependencies]`. Twelve candidates, every modern Maven project among them. The same
   pom imports from the Gradle side of the guide fine because the Boot plugin manages versions.
2. **Gradle `$property` / `${property}` versions** imported literally, then rejected by jk's own
   parser as an environment reference. Five candidates.
3. **JUnit 4 suites**: no vintage engine, `test discovery exited 70`. Three candidates.
4. **`maven.compiler.source` / `jvmToolchain` below 17** becomes a hard `jdk =` pin jk refuses,
   where `java = 17` on the host JDK would compile them. Two candidates.
5. **Test JVM assertions**: Gradle and Surefire run tests with `-ea`; jk does not. One candidate.
6. **Annotation-processor paths**, **generated-source steps**, **Kotlin test sources in a Java
   Maven build**: one candidate each.

Not import gaps but worth knowing: `jk import --report <relative path>` writes the report under
`~/.jk/state/engine/` (the path is resolved against the engine's cwd), and a Gradle import names the
project after the directory rather than `settings.gradle`'s `rootProject.name`.
