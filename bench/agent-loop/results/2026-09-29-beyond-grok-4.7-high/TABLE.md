# Agent loop: turns, tokens and wall to green

Date: 2026-09-29 · jk 0.14.0 · host `1a8c211a203d` · 12th Gen Intel(R) Core(TM) i9-12900KF (24 logical CPUs, 16 GiB RAM), ubuntu-26.04, kernel 6.6.87.2-microsoft-standard-WSL2, WSL · drivers: grok · 57 (scenario × tool) runs on this host · subset beyond-guides

A run materialises one (repo × failure) for one tool, runs the tool once so the results file is red, then lets the agent loop through that tool's MCP server until the results say OK or the budget ends. A row is green only when the harness's own rerun after the agent stopped is green too. Turns under the cap are informational. The comparison is the green rate, the cost to green (sum of cost on green runs ÷ green runs), and the signal-quality columns: median turn of the first edit that touches the injection, median reads before that edit, median output+reasoning tokens, median uncached input tokens, median cache-read tokens, and the fix-quality counts (exact, equivalent, collateral, cheat). Median and p90 of turns, tokens, and wall are over this host's runs of the tool, red runs at their budget. The column rules are in the agent-loop README. Rows from another host are listed under their own heading and are not mixed into these numbers.

## `grok` · grok-4.7 · effort high · subset beyond-guides · budget 16 turns / 10 min

| Tool | Runs | Green | Green rate | Turns median | Turns p90 | Tokens median | Tokens p90 | Wall median | Wall p90 | Cost | First correct edit | Reads before fix | Output+reasoning | Input (uncached) | Cache read | Cost to green | Fix quality |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| jk | 27 | 27 | 100% | 7 | 10 | 98,056 | 216,483 | 40.6s | 170.6s | $1.19 | 5 | 6 | 3,826 | 22,042 | 57,344 | $0.0442 | 17 exact · 10 equivalent · 0 collateral · 0 cheat |
| mvn | 15 | 12 | 80% | 9 | 16 | 106,381 | 480,842 | 83.7s | 217.8s | $0.97 | 4 | 5 | 3,884 | 31,611 | 69,888 | $0.0597 | 11 exact · 3 equivalent · 0 collateral · 0 cheat |
| gradle | 15 | 15 | 100% | 8 | 13 | 119,088 | 295,552 | 55.7s | 138.0s | $0.81 | 5 | 9 | 5,487 | 28,519 | 84,864 | $0.0538 | 11 exact · 4 equivalent · 0 collateral · 0 cheat |

| Repo | Failure | jk | mvn | gradle |
|---|---|---|---|---|
| commons-cli | compile-error | green · 6t · 17.7s · 63,024 tok | green · 7t · 33.8s · 81,871 tok | · |
| commons-cli | failing-assertion | green · 7t · 30.9s · 100,582 tok | green · 11t · 43.7s · 201,723 tok | · |
| commons-cli | missing-dependency | green · 8t · 56.4s · 142,880 tok | **red** · 16t · 112.9s · 480,842 tok | · |
| gs-multi-module | compile-error | green · 6t · 170.6s · 64,914 tok | green · 8t · 36.3s · 93,646 tok | green · 7t · 32.7s · 78,816 tok |
| gs-multi-module | failing-assertion | green · 6t · 470.1s · 74,441 tok | green · 8t · 174.4s · 106,381 tok | green · 8t · 129.2s · 119,088 tok |
| gs-multi-module | missing-resource | green · 7t · 141.1s · 87,532 tok | · | green · 10t · 55.7s · 135,324 tok |
| gs-multi-module | version-conflict | green · 8t · 72.2s · 140,686 tok | green · 7t · 38.3s · 92,559 tok | green · 8t · 67.8s · 108,460 tok |
| junit-starter-gradle-kotlin | compile-error | green · 5t · 20.5s · 51,251 tok | · | green · 6t · 38.1s · 66,866 tok |
| junit-starter-gradle-kotlin | failing-assertion | green · 5t · 27.9s · 54,095 tok | · | green · 6t · 24.2s · 71,913 tok |
| junit-starter-gradle-kotlin | missing-dependency | green · 7t · 44.2s · 98,056 tok | · | green · 7t · 46.2s · 93,102 tok |
| junit-starter-maven | compile-error | green · 5t · 16.6s · 50,476 tok | green · 6t · 17.1s · 58,552 tok | · |
| junit-starter-maven | failing-assertion | green · 5t · 30.4s · 50,970 tok | green · 8t · 20.0s · 81,996 tok | · |
| junit-starter-maven-kotlin | compile-error | green · 5t · 21.7s · 50,268 tok | **red** · 10t · 600.2s | · |
| junit-starter-maven-kotlin | failing-assertion | green · 5t · 25.3s · 56,033 tok | green · 16t · 173.2s · 241,996 tok | · |
| junit-starter-maven-kotlin | missing-dependency | green · 6t · 34.2s · 78,019 tok | **red** · 16t · 181.5s · 348,823 tok | · |
| spring-petclinic | compile-error | green · 6t · 35.2s · 66,936 tok | green · 6t · 59.8s · 68,385 tok | · |
| spring-petclinic | failing-assertion | green · 8t · 107.4s · 105,428 tok | green · 9t · 83.7s · 133,816 tok | · |
| spring-petclinic | missing-dependency | green · 8t · 157.4s · 121,080 tok | green · 16t · 160.7s · 434,412 tok | · |
| spring-petclinic | missing-resource | green · 14t · 225.5s · 575,930 tok | green · 13t · 217.8s · 624,609 tok | · |
| spring-petclinic-kotlin | compile-error | green · 7t · 40.0s · 100,960 tok | · | green · 7t · 46.2s · 102,089 tok |
| spring-petclinic-kotlin | failing-assertion | green · 7t · 37.2s · 112,319 tok | · | green · 8t · 59.0s · 142,938 tok |
| spring-petclinic-kotlin | missing-dependency | green · 11t · 115.7s · 254,475 tok | · | green · 10t · 168.3s · 231,408 tok |
| spring-petclinic-kotlin | missing-resource | green · 10t · 188.7s · 391,860 tok | · | green · 13t · 138.0s · 626,403 tok |
| tut-spring-boot-kotlin | compile-error | green · 6t · 18.1s · 61,279 tok | · | green · 6t · 29.7s · 59,482 tok |
| tut-spring-boot-kotlin | failing-assertion | green · 9t · 51.1s · 136,455 tok | · | green · 14t · 88.7s · 295,552 tok |
| tut-spring-boot-kotlin | missing-dependency | green · 8t · 40.6s · 111,373 tok | · | green · 10t · 46.2s · 188,772 tok |
| tut-spring-boot-kotlin | missing-resource | green · 11t · 95.8s · 216,483 tok | · | green · 9t · 77.8s · 196,984 tok |

### Findings

What the results file did not say, per run: the oracle records where it needed more than the file, and the LLM drivers record why they stopped.

| Repo | Failure | Tool | Outcome | Fix source | Finding |
|---|---|---|---|---|---|
| commons-cli | compile-error | jk | green | — | agent stopped: The build is green. The unit suite passed: **994 tests**.

`stripLeadingHyphens` in `src/main/java/org/apache/commons/cli/Util.java` was missing a semicolon after `return str.substring(2)`. Adding tha |
| commons-cli | compile-error | mvn | green | — | agent stopped: The build is green. `mvn test` finishes with exit code 0: 933 tests passed and 61 skipped.

The compiler stopped on a missing semicolon in `Util.stripLeadingHyphens`:

```80:80:src/main/java/org/apach |
| commons-cli | failing-assertion | jk | green | — | agent stopped: The build is green. All 994 tests pass.

`UtilTest.testStripLeadingAndTrailingQuotes` expected `fo` from `stripLeadingAndTrailingQuotes("\"foo\"")`. That method removes one matching pair of surroundin |
| commons-cli | failing-assertion | mvn | green | — | agent stopped: The build is green. All 994 tests pass (933 passed, 61 skipped).

`UtilTest.testStripLeadingAndTrailingQuotes` expected `fo` from `Util.stripLeadingAndTrailingQuotes("\"foo\"")`. That method only remo |
| commons-cli | missing-dependency | jk | green | — | agent stopped: The test compile is green again. Test sources import `org.apache.commons.lang3` and `org.apache.commons.text`, and those packages come from `org.apache.commons:commons-text`, which was missing from th |
| commons-cli | missing-dependency | mvn | red | — | agent stopped (error_max_turns) |
| gs-multi-module | compile-error | jk | green | — | agent stopped: The build is green. `MyService.message()` was missing a semicolon after `getMessage()`, which stopped the library module from compiling.

`OK test gs-multi-module · 2 tests · 1.3s` |
| gs-multi-module | compile-error | mvn | green | — | agent stopped: The build is green. `MyService.message()` was missing the semicolon after `getMessage()`, so the library module failed to compile and the rest of the reactor was skipped.

`mvn test` now exits 0. Both |
| gs-multi-module | compile-error | gradle | green | — | agent stopped: The build is green. `MyService.message()` was missing the semicolon after `return this.serviceProperties.getMessage()`, so `:library:compileJava` failed before either module could test.

Gradle `test` |
| gs-multi-module | failing-assertion | jk | green | — | agent stopped: The build is green. Both tests pass.

`MyServiceTest` sets `service.message=Hello`, and `MyService.message()` returns that property as-is. The assertion expected `"Hello, World"`, which is only the ap |
| gs-multi-module | failing-assertion | mvn | green | — | agent stopped: The build is green. Both modules pass: `MyServiceTest` and `DemoApplicationTest`.

`MyServiceTest` sets `service.message=Hello`, so `MyService.message()` returns `"Hello"`. The assertion still expecte |
| gs-multi-module | failing-assertion | gradle | green | — | agent stopped: The build is green. Both modules pass.

`MyServiceTest` sets `service.message` to `Hello`, and `MyService.message()` returns that property as-is. The test was asserting `"Hello, World"`, which is the  |
| gs-multi-module | missing-resource | jk | green | — | agent stopped: The build is green. `DemoApplicationTest` was failing because `MyService.message()` was null: the application module had no `service.message` property.

I added `application/src/main/resources/applica |
| gs-multi-module | missing-resource | gradle | green | — | agent stopped: The build is green. Both modules pass, 2 tests out of 2.

`DemoApplicationTest.contextLoads()` failed because `myService.message()` was null. `MyService` reads that string from the `service.message` c |
| gs-multi-module | version-conflict | jk | green | — | agent stopped: The build is green. `OK test gs-multi-module · 2 tests · 307ms`.

The application module pinned `org.springframework:spring-core` at exactly 6.0.0. Spring Boot 3.5.11 manages the rest of Spring Framew |
| gs-multi-module | version-conflict | mvn | green | — | agent stopped: The build is green. `mvn test` exits 0, and both tests pass.

`application/pom.xml` pinned `spring-core` to 6.0.0, which overrode the version managed by Spring Boot 3.5.11. The Boot test stack calls ` |
| gs-multi-module | version-conflict | gradle | green | — | agent stopped: The build is green. Both modules pass.

`application/build.gradle` forced `spring-core` to exactly 6.0.0. Spring Boot 3.5.11's test support calls `CollectionUtils.newLinkedHashSet(int)`, which that ol |
| junit-starter-gradle-kotlin | compile-error | jk | green | — | agent stopped: The build is green. `Calculator.add` referenced an undeclared `c`, so the Kotlin compile failed. It now returns `a + b`, and `jk test` passed all 6 tests. |
| junit-starter-gradle-kotlin | compile-error | gradle | green | — | agent stopped: The build is green. `Calculator.add` was returning `a + c`, and `c` is not defined, so Kotlin compilation failed with an unresolved reference on line 16.

It now returns `a + b`. `./gradlew test` comp |
| junit-starter-gradle-kotlin | failing-assertion | jk | green | — | agent stopped: The test suite is green: **6 tests passed**.

`CalculatorTests.add` expected `49 + 51` to equal 101. `Calculator.add` returns `a + b`, which is 100, so the CSV expectation was updated to 100. |
| junit-starter-gradle-kotlin | failing-assertion | gradle | green | — | agent stopped: The build is green. All 6 tests pass.

The parameterized add test expected `49 + 51` to equal 101. `Calculator.add` already returns `a + b`, which is 100, so the CSV expected value was wrong. I change |
| junit-starter-gradle-kotlin | missing-dependency | jk | green | — | agent stopped: The build is green. `OK test junit-starter-gradle-kotlin · 6 tests · 249ms`.

Test compilation failed because `CalculatorTests.kt` uses JUnit Jupiter (`Test`, `ParameterizedTest`, `CsvSource`, `assert |
| junit-starter-gradle-kotlin | missing-dependency | gradle | green | — | agent stopped: The build is green. All 6 tests pass.

Test compilation failed because `build.gradle.kts` only pulled in the JUnit BOM and the platform launcher. The Jupiter API, parameterized tests, and engine were  |
| junit-starter-maven | compile-error | jk | green | — | agent stopped: The build is green. `Calculator.add` was missing a semicolon after `return a + b`, which stopped compilation.

`OK test junit-jupiter-starter-maven · 5 tests · 259ms` |
| junit-starter-maven | compile-error | mvn | green | — | agent stopped: The build is green. `Calculator.add` was missing a semicolon after `return a + b`, which stopped compilation at `Calculator.java:16`.

Maven `test` now exits 0, and all 5 tests pass. |
| junit-starter-maven | failing-assertion | jk | green | — | agent stopped: The build is green. All 5 tests pass.

`addsTwoNumbers` expected `3` from `calculator.add(1, 1)`, while `Calculator.add` correctly returns `2`. The assertion message and display name already said the  |
| junit-starter-maven | failing-assertion | mvn | green | — | agent stopped: The build is green. All 5 tests pass.

`CalculatorTests.addsTwoNumbers` asserted that `1 + 1` equals `3`, while the test name, the failure message, and `Calculator.add` all treat the sum as `2`. The e |
| junit-starter-maven-kotlin | compile-error | jk | green | — | agent stopped: The build is green. `Calculator.add` was adding an undefined `c` instead of the parameter `b`, so compilation failed with an unresolved reference.

```15:17:src/main/kotlin/com/example/project/Calcula |
| junit-starter-maven-kotlin | compile-error | mvn | red | — | time budget exhausted (600s); the tree is green although the agent did not report it |
| junit-starter-maven-kotlin | failing-assertion | jk | green | — | agent stopped: The test suite is green: **6 tests passed**.

`Calculator.add` already returns `a + b`, so `49 + 51` is `100`. The parameterized case in `CalculatorTests` expected `101`. That expectation is now `100` |
| junit-starter-maven-kotlin | failing-assertion | mvn | green | — | agent stopped (error_max_turns) |
| junit-starter-maven-kotlin | missing-dependency | jk | green | — | agent stopped: The build is green. All 6 tests pass.

Test compilation failed because `CalculatorTests` uses `ParameterizedTest` and `CsvSource`, which live in `org.junit.jupiter:junit-jupiter-params`, and that arti |
| junit-starter-maven-kotlin | missing-dependency | mvn | red | — | agent stopped (error_max_turns); the tree is green although the agent did not report it |
| spring-petclinic | compile-error | jk | green | — | agent stopped: The build is green. `Owner.setAddress` was missing a semicolon after `this.address = address`, so compilation failed before any tests ran.

`jk test` now passes: 79 tests in 820ms. |
| spring-petclinic | compile-error | mvn | green | — | agent stopped: The build is green. Maven `test` finished with exit code 0, and all 79 tests passed.

`Owner.setAddress` was missing a semicolon after the assignment, so compilation failed at `Owner.java:77` with `'; |
| spring-petclinic | failing-assertion | jk | green | — | agent stopped: The build is green. All 79 unit tests pass.

`PetTypeFormatterTests#testPrint` expected `print` to return `"Hamster!"`, but `PetTypeFormatter.print` returns the pet type name as stored. The assertion  |
| spring-petclinic | failing-assertion | mvn | green | — | agent stopped: The build is green. All 79 tests pass.

`PetTypeFormatterTests.testPrint` expected `Hamster!`, but `PetTypeFormatter.print` returns the pet type name as stored, which is `Hamster`. The assertion now e |
| spring-petclinic | missing-dependency | jk | green | — | agent stopped: The build is green. `CacheConfiguration` imports `javax.cache.configuration.MutableConfiguration`, and `javax.cache:cache-api` was missing from this module.

That dependency is now declared in `jk.tom |
| spring-petclinic | missing-dependency | mvn | green | — | agent stopped: The build is green. All 79 tests passed.

`CacheConfiguration` uses the JCache API (`javax.cache.configuration.MutableConfiguration` and `javax.cache.CacheManager`), but that library was not on the cl |
| spring-petclinic | missing-resource | jk | green | — | agent stopped: The build is green. All 79 tests pass.

The H2 profile loads `db/h2/schema.sql` before `db/h2/data.sql`, and that schema file was missing, so the seed inserts ran against an empty database and failed  |
| spring-petclinic | missing-resource | mvn | green | — | agent stopped: The build is green. All 79 tests passed.

The H2 profile initializes the database from `classpath*:db/h2/schema.sql` and then loads `data.sql`. That schema file was missing, so the first statement in  |
| spring-petclinic-kotlin | compile-error | jk | green | — | agent stopped: The build is green. `jk test` reports **OK** — 41 tests passed in 2.8s.

`Vet.getNrOfSpecialties()` was reading `specialties.count`. On a Kotlin collection, `count` is a function, so the compiler reje |
| spring-petclinic-kotlin | compile-error | gradle | green | — | agent stopped: The build is green. All 41 tests passed.

`Vet.getNrOfSpecialties()` was reading `specialties.count` as a property. In Kotlin, `count` is a function, so the compiler stopped with `Function invocation  |
| spring-petclinic-kotlin | failing-assertion | jk | green | — | agent stopped: The test suite is green: 41 tests passed.

`VetTest.testSerialization` was comparing the deserialized first name to the last name. After a round trip, `firstName` is still `"Zaphod"` and `lastName` is |
| spring-petclinic-kotlin | failing-assertion | gradle | green | — | agent stopped: The build is green. All 41 tests pass.

`VetTest.testSerialization()` was comparing the deserialized vet's first name to the original last name. Serialization kept `firstName` as `Zaphod`, so the asse |
| spring-petclinic-kotlin | missing-dependency | jk | green | — | agent stopped: The build is green. `jk test` passes: **41 tests, 9.0s**.

`CacheConfig.kt` uses the JCache types `javax.cache.CacheManager`, `Configuration`, and `MutableConfiguration`, but `javax.cache:cache-api` w |
| spring-petclinic-kotlin | missing-dependency | gradle | green | — | agent stopped: The build is green. All 41 tests passed.

`CacheConfig` programs a JCache `CacheManager`, but the JCache API and a provider were missing from the classpath. `build.gradle.kts` now includes `javax.cach |
| spring-petclinic-kotlin | missing-resource | jk | green | — | agent stopped: The test suite is green: 41 tests passed.

H2 was loading `db/h2/data.sql` with no schema, so the first insert (`INSERT INTO vets ...`) failed with `Table "VETS" not found`. `application.properties` p |
| spring-petclinic-kotlin | missing-resource | gradle | green | — | agent stopped: The build is green. All 41 tests pass.

The H2 profile loads `classpath*:db/h2/schema.sql` before `data.sql`, and that schema file was missing. Seed inserts such as `INSERT INTO vets` then ran against |
| tut-spring-boot-kotlin | compile-error | jk | green | — | agent stopped: The build is green. `jk test` reports **OK** — 7 tests passed.

`getOrdinal` in `src/main/kotlin/com/example/blog/Extensions.kt` appended an undeclared `x` to the 11th–13th case (`"${n}th" + x`), so K |
| tut-spring-boot-kotlin | compile-error | gradle | green | — | agent stopped: The build is green. All 7 tests passed.

`getOrdinal` in `src/main/kotlin/com/example/blog/Extensions.kt` appended an undefined `x` when formatting the 11th–13th, which failed Kotlin compilation with  |
| tut-spring-boot-kotlin | failing-assertion | jk | green | — | agent stopped: The build is green. All 7 tests pass.

`IntegrationTests` expected the blog heading `<h1>Blog!</h1>`, but `blog.title` is `Blog` and the page renders `<h1>Blog</h1>`. The assertion now matches that he |
| tut-spring-boot-kotlin | failing-assertion | gradle | green | — | agent stopped: The build is green. All 7 tests pass.

`IntegrationTests` expected the blog page to contain `<h1>Blog!</h1>`. The page renders `<h1>Blog</h1>` from `blog.title=Blog` in `application.properties`, which |
| tut-spring-boot-kotlin | missing-dependency | jk | green | — | agent stopped: The build is green. `jk test` reports **OK test blog · 7 tests · 1.2s**.

The controller tests in `HttpControllersTests.kt` use `@MockkBean` and `every { … }`, but SpringMockK was not on the test clas |
| tut-spring-boot-kotlin | missing-dependency | gradle | green | — | agent stopped: The test build is green. All 7 tests passed.

`HttpControllersTests` uses `@MockkBean` and MockK's `every`, but those libraries were not on the test classpath. I added the SpringMockK dependency the t |
| tut-spring-boot-kotlin | missing-resource | jk | green | — | agent stopped: The build is green. All 7 tests passed.

Spring Data JDBC does not create tables, and `src/main/resources/schema.sql` was missing. `spring.sql.init.mode=always` was already set, so startup had nothing |
| tut-spring-boot-kotlin | missing-resource | gradle | green | — | agent stopped: The build is green. All 7 tests passed.

Spring Data JDBC was inserting into `users` before any tables existed. `application.properties` already sets `spring.sql.init.mode=always`, but `src/main/resou |
