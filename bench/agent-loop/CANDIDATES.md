# Candidate repositories

Every repository tried for the corpus, with the baseline outcome under its own tool (the pinned
Maven or Gradle through `wrappers/`, JDK 25) and under jk after `jk import` with no hand edits. A
repo is accepted only when both are green; each reject names the first thing that broke, quoted
from the tool. The Spring guides were recorded 2026-09-15 (jk 0.13.7); everything else, and the
re-tries of the earlier rejects, 2026-09-29 (jk 0.14.0 at `8457c2286`) against the latest commit
of the default branch that day.

## Accepted (27)

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
| gs-multi-module | spring-guides/gs-multi-module `complete/` (2 modules; jk imports the pom) | Maven 8s, Gradle 20s | 4s, 2 tests |
| junit-starter-maven | junit-team/junit-examples `junit-jupiter-starter-maven/` | Maven 4s | 2s, 5 tests |
| commons-cli | apache/commons-cli | Maven 21s | 8s, 994 tests |
| spring-petclinic | spring-projects/spring-petclinic (jk imports the pom) | Maven 55s | 34s, 79 tests |
| junit-starter-maven-kotlin | junit-team/junit-examples `junit-jupiter-starter-maven-kotlin/` | Maven 10s | 10s, 6 tests |
| junit-starter-gradle-kotlin | junit-team/junit-examples `junit-jupiter-starter-gradle-kotlin/` | Gradle 25s | 18s, 6 tests |
| spring-petclinic-kotlin | spring-petclinic/spring-petclinic-kotlin | Gradle 58s | 60s, 41 tests |
| tut-spring-boot-kotlin | spring-guides/tut-spring-boot-kotlin | Gradle 45s | 44s, 7 tests |

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

Not taken although green on both sides: datafaker-net/datafaker (Maven with Kotlin tests; 21,773
tests make one scenario cost minutes).

## Rejected: red under jk (10)

Green under their own tool; the first jk error after `jk import` + `jk test`.

| Repo | jk's first failure | Gap |
|---|---|---|
| junit-team/junit-examples `junit-multiple-engines/` (Gradle) | `E compile-test: release must be >= 8, got: 0` | the import writes release 0 |
| apache/commons-csv (Maven) | `CSVBenchmark.java:63 package org.skife.csv does not exist` | test dependencies declared in a profile are not imported |
| apache/commons-codec (Maven) | `test runner ran out of heap at 128 MiB and again at 256 MiB` | an unlearned test JVM starts small and retries once |
| apache/commons-text (Maven) | `test runner ran out of heap at 192 MiB and again at 384 MiB` | same |
| jhy/jsoup (Maven) | `Could not load org.jsoup.helper.HttpClientExecutor$ProxyWrap` (+145 tests) | the multi-release `java21` sources are not built |
| google/jimfs (Maven, 2 modules) | `RegularFileTestRunner has no public constructor TestCase(String name)` | a JUnit 3 suite runner |
| spring-petclinic/spring-petclinic-rest (Maven) | `plugin-generate-openapi` failed with only the generator's log | the OpenAPI generator step |
| quarkusio/quarkus-quickstarts `getting-started/` (Maven) | `no launchable main method found` in the Quarkus test model | a Quarkus app without a `main` |
| junit-pioneer/junit-pioneer (Gradle) | `cannot find symbol org.junitpioneer.jupiter.ReportEntry` | a source set the import drops |
| spring-guides/gs-producing-web-service `complete/` (Gradle) | resolve: `jakarta.xml.ws-api 4.0.3 depends on jakarta.xml.bind-api [4.0.5,+∞)` | the import pins below what the graph requires |

## Rejected: red under their own tool (7)

| Repo | Why |
|---|---|
| google/gson (Maven) | `test-jpms` module fails under the reactor on JDK 25 |
| awaitility/awaitility (Maven) | `gmavenplus-plugin` fails in `awaitility-groovy` |
| spring-guides/tut-rest `links/` (Maven) | unresolvable parent model |
| square/javapoet (Maven) | `-source 8` / JDK 25 compile failure |
| jillesvangurp/kotlin4example (Gradle) | Gradle 9.8.0 build failure |
| charleskorn/kaml (Gradle) | Gradle 9.8.0 build failure (Kotlin toolchain 11) |
| mapstruct/mapstruct-examples `mapstruct-on-gradle/` (Gradle) | Gradle 9.8.0 build failure (old MapStruct) |

## Rejected: no usable baseline or no tests (6)

| Repo | Why |
|---|---|
| spring-guides/gs-async-method `complete/` | green everywhere, but `complete/` has no test sources (`No tests`) — nothing to break |
| spring-guides/gs-managing-transactions `complete/` | same, no test sources |
| spring-guides/gs-caching `complete/` | same, no test sources |
| spring-guides/gs-relational-data-access `complete/` | same, no test sources |
| spring-guides/tut-rest `links/`, `evolution/` | Maven-only steps with `spring-boot-starter-parent` → BOM-managed versions (`unresolved`) |
| micronaut-projects/* | no small public Micronaut example with a real test suite: `micronaut-examples` is empty, `micronaut-guides` is a 3 GB monorepo |
