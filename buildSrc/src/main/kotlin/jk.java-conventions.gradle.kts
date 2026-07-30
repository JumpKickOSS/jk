// SPDX-License-Identifier: Apache-2.0

import java.time.Duration

plugins {
    java
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Keep in lock-step with jk's own default lint policy (cc.jumpkick.compile.JavacLint),
    // so `gradle build` and `jk build` surface the same javac warnings.
    options.compilerArgs.add("-Xlint:deprecation,unchecked")
}

// Reach the version catalog from a convention plugin without the buildSrc
// classpath hack: read it through VersionCatalogsExtension on the project.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    "testImplementation"(libs.findLibrary("junit-jupiter").orElseThrow())
    "testImplementation"(libs.findLibrary("assertj-core").orElseThrow())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

// ---------------------------------------------------------------------------
// Two-tier tests (JK-1123 / suite performance):
//   ./gradlew test              — unit/fast (exclude integration|slow|bench); target <5 min
//   ./gradlew integrationTest   — engine/e2e/network/worker suites
//
// Tag classes with @Tag("integration"), @Tag("slow"), or @Tag("bench").
// ---------------------------------------------------------------------------
val slowTags = listOf("integration", "slow", "bench")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real ~/.jk. JkDirs.home() honours
    // JK_HOME, and everything derived from it (the downloaded global library
    // catalog, cache, credentials, …) follows — so without this a machine
    // that has run `jk library update` would feed its real
    // ~/.jk/cache/libs.global.toml into tests and shadow the bundled layer
    // (e.g. LibrarySearchCommandTest). Point JK_HOME at a throwaway per-module
    // dir to keep tests hermetic.
    environment("JK_HOME", layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath)
    // Same isolation for the Maven local repository (M2Dirs honours JK_M2_LOCAL):
    // tests that exercise the real fetch pipeline against a mock Maven server would
    // otherwise mirror their stub artifacts into the developer's real ~/.m2 —
    // overwriting genuine jars when a fixture reuses real coordinates (e.g. the
    // injected junit-jupiter test deps) and corrupting every later build on the
    // machine. The env var also reaches any jk subprocess a test forks.
    environment("JK_M2_LOCAL", layout.buildDirectory.dir("test-m2").get().asFile.absolutePath)
    // The embedded HTTP server is on by default (docs/http.md). Tests must not open listening
    // sockets as a side effect: engines spawned by integration tests would race the developer's
    // real engine (and each other, across parallel checkouts) for port 8910. The env var reaches
    // any jk subprocess a test forks; suites that exercise HTTP construct HttpEngineServer (or
    // pass an explicit JkHttpConfig) directly.
    environment("JK_HTTP_ENABLED", "false")
    // Fail hung methods instead of multi-hour freezes (override per-task if needed).
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
    // Per-host rate-limit cooldowns are keyed by host, and every in-process HTTP test serves from
    // 127.0.0.1 — so without a throwaway store one test's simulated 429 cools down loopback for every
    // other test, and the record lands in the developer's real ~/.jk (JK-1276, and the JK-1292 lesson).
    systemProperty("jk.http.cooldown.dir", layout.buildDirectory.dir("test-http-cooldown").get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    description = "Unit/fast tests (excludes @Tag integration|slow|bench)"
    useJUnitPlatform {
        excludeTags(*slowTags.toTypedArray())
    }
    // Parallel forks for pure unit modules. CLI overrides to 1 for integration only.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // Suite budget: hang becomes a fail, not a 20+ min stall.
    timeout.set(Duration.ofMinutes(8))
}

// Same classpath/sources as test; different tag filter + longer budget.
val integrationTest by tasks.registering(Test::class) {
    description = "Integration/slow tests (@Tag integration|slow). Not part of check by default."
    group = "verification"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration", "slow")
        excludeTags("bench")
    }
    shouldRunAfter(tasks.named("test"))
    systemProperty("junit.jupiter.execution.timeout.default", "300s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
    timeout.set(Duration.ofMinutes(45))
    // Inherit hermetic env from withType<Test> configureEach above.
}

// Optional: full verification including integration (nightly / merge gates).
tasks.register("checkAll") {
    group = "verification"
    description = "Unit test + integrationTest for this module"
    dependsOn(tasks.named("test"), integrationTest)
}
