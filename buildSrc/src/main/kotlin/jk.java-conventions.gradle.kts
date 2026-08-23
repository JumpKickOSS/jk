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
    // JSpecify + Lombok: compile-time only; never on the runtime / native / fat-jar classpath.
    "compileOnly"(libs.findLibrary("jspecify").orElseThrow())
    "testCompileOnly"(libs.findLibrary("jspecify").orElseThrow())
    "compileOnly"(libs.findLibrary("lombok").orElseThrow())
    "annotationProcessor"(libs.findLibrary("lombok").orElseThrow())
    "testCompileOnly"(libs.findLibrary("lombok").orElseThrow())
    "testAnnotationProcessor"(libs.findLibrary("lombok").orElseThrow())
    "testImplementation"(libs.findLibrary("junit-jupiter").orElseThrow())
    "testImplementation"(libs.findLibrary("assertj-core").orElseThrow())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

// Two-tier tests (suite performance):
//   ./gradlew test              — unit/fast (exclude integration|slow|bench|network); target <5 min
//   ./gradlew integrationTest   — engine/e2e/network/worker suites
// Tag classes with @Tag("integration"), @Tag("slow"), @Tag("bench"), or @Tag("network").
val slowTags = listOf("integration", "slow", "bench", "network")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real product layout. JK_HOME is a single-tree
    // umbrella that mirrors the XDG shape: it relocates the five roots to
    // $JK_HOME/{bin,cache,config,data,state}, so the store is $JK_HOME/data/store and the
    // engine jar $JK_HOME/data/lib/jk-engine/. Managed JDKs default to the shared IntelliJ
    // root and are *not* relocated by JK_HOME — set JK_JDKS_DIR for hermetic JDK isolation.
    val testJkHome = layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath
    environment("JK_HOME", testJkHome)
    environment("JK_JDKS_DIR", "$testJkHome/jdks")
    // Same isolation for the Maven local repository (M2Dirs honours JK_M2_LOCAL):
    // tests that exercise the real fetch pipeline against a mock Maven server would
    // otherwise mirror their stub artifacts into the developer's real ~/.m2
    // overwriting genuine jars when a fixture reuses real coordinates (e.g. the
    // injected junit-jupiter test deps) and corrupting every later build on the
    // machine. The env var also reaches any jk subprocess a test forks.
    environment("JK_M2_LOCAL", layout.buildDirectory.dir("test-m2").get().asFile.absolutePath)
    // The resident engine queues a cache GC on its 12h feed tick — immediately on startup when
    // idle. In-process engines under test share the module's JK_HOME store, so that startup GC
    // races any test fixture staging prune-eligible files (.put-* temps) for its own explicit
    // prune request and eats them first (flaky counts). Explicit `jk cache prune` requests are
    // unaffected by this switch.
    environment("JK_AUTO_PRUNE", "false")
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
    // other test, and the record lands in the developer's real product layout.
    systemProperty("jk.http.cooldown.dir", layout.buildDirectory.dir("test-http-cooldown").get().asFile.absolutePath)
    // Default @TempDir (junit-*) follows java.io.tmpdir. Host /tmp is often a small-inode
    // tmpfs; leftover fixtures exhaust it and the next suite fails in TempDirFactory.
    // CLI UDS tests still bind under /tmp via JkTempDirFactory (path-length).
    val testTmp = layout.buildDirectory.dir("tmp")
    doFirst { testTmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    description = "Unit/fast tests (excludes @Tag integration|slow|bench|network)"
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
