// SPDX-License-Identifier: Apache-2.0

import java.io.File
import java.time.Duration
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension

plugins {
    id("jk.java")
    jacoco
}

val slowTags = TestTiers.slowTags

fun TestTier.applyTo(spec: org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions) {
    if (include.isNotEmpty()) spec.includeTags(*include.toTypedArray())
    if (exclude.isNotEmpty()) spec.excludeTags(*exclude.toTypedArray())
}

fun tier(name: String): TestTier = TestTiers.all.first { it.task == name }

fun treeExceeds(root: File, capBytes: Long): Boolean {
    if (!root.isDirectory) return false
    var total = 0L
    root.walkTopDown().forEach { f ->
        if (f.isFile) {
            total += f.length()
            if (total > capBytes) return true
        }
    }
    return false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real product layout. JK_HOME relocates the whole tree.
    val testJkHome = layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath
    environment("JK_HOME", testJkHome)
    environment("JK_JDKS_DIR", "$testJkHome/jdks")
    // M2Dirs honours JK_M2_LOCAL so mock-Maven tests cannot overwrite ~/.m2.
    val testM2 = layout.buildDirectory.dir("test-m2").get().asFile.absolutePath
    environment("JK_M2_LOCAL", testM2)
    doFirst {
        val home = File(testJkHome)
        val stamp = File(home, ".wiped-at")
        val weekMs = 7L * 24 * 60 * 60 * 1000
        val capBytes = 1L shl 30
        val stale = stamp.isFile && System.currentTimeMillis() - stamp.lastModified() > weekMs
        if (stale || treeExceeds(home, capBytes)) {
            home.deleteRecursively()
            File(testM2).deleteRecursively()
        }
        if (!stamp.isFile) {
            home.mkdirs()
            stamp.writeText("Sweep stamp for the warm test home; see jk.testing.\n")
        }
    }
    environment("JK_AUTO_PRUNE", "false")
    environment("JK_HTTP_ENABLED", "false")
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
    systemProperty("jk.http.cooldown.dir", layout.buildDirectory.dir("test-http-cooldown").get().asFile.absolutePath)
    val testTmp = layout.buildDirectory.dir("tmp")
    doFirst { testTmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.get().asFile.absolutePath)
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    val shuffle = providers.gradleProperty("jk.test.shuffle").orNull
    if (shuffle != null) {
        val seed = if (shuffle.isBlank() || shuffle == "true") System.nanoTime().toString() else shuffle
        systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$Random")
        systemProperty("junit.jupiter.execution.order.random.seed", seed)
        doFirst {
            logger.lifecycle(
                "jk: $path shuffling test classes serially, seed=$seed"
                    + "  (replay with -Pjk.test.shuffle=$seed)")
        }
    }
    val declared = ExternalTestRuntimes.table["${project.path}:$name"].orEmpty()
    if (declared.isNotEmpty()) {
        val probeCache = rootProject.layout.buildDirectory.dir("external-tool-probe").get().asFile
        val searchPath = providers.environmentVariable("PATH").getOrElse("")
        declared.forEach { (tool, envOverride) ->
            val override = envOverride?.let { providers.environmentVariable(it).orNull }
            inputs.property(
                "externalRuntime.$tool",
                ExternalToolVersions.identity(probeCache, tool, searchPath, override))
        }
    }
    // Agent off unless `-Pjk.coverage`: the inventory is nightly, not the branch gate.
    val coverage = project.hasProperty("jk.coverage")
    inputs.property("jk.coverage", coverage)
    extensions.configure<JacocoTaskExtension> {
        isEnabled = name == "test" && coverage
    }
}

tasks.named<Test>("test") {
    description = "Unit/fast tests (excludes @Tag " + slowTags.joinToString("|") + ")"
    useJUnitPlatform { tier(TestTiers.UNIT).applyTo(this) }
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    timeout.set(Duration.ofMinutes(8))
}

fun slowTier(tierName: String, budget: Duration, describe: String) {
    tasks.register<Test>(tierName) {
        description = describe
        group = "verification"
        val testSourceSet = sourceSets["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { tier(tierName).applyTo(this) }
        shouldRunAfter(tasks.named("test"))
        systemProperty("junit.jupiter.execution.timeout.default", "300s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        timeout.set(budget)
    }
}

slowTier(
    TestTiers.INTEGRATION,
    Duration.ofMinutes(45),
    "Integration tests (@Tag integration). Part of checkAll, not of check.")

slowTier(
    TestTiers.SLOW,
    Duration.ofMinutes(30),
    "Framework/language e2e suites (@Tag slow). Nightly and on demand — never in checkAll.")

slowTier(
    TestTiers.NETWORK,
    Duration.ofMinutes(30),
    "Tests that talk to a real remote (@Tag network). Nightly only — never in checkAll.")

slowTier(
    TestTiers.BENCH,
    Duration.ofMinutes(30),
    "Microbenchmarks (@Tag bench). Prints medians, gates nothing — run on demand.")

tasks.register("checkAll") {
    group = "verification"
    description = "Unit test + integrationTest for this module"
    dependsOn(TestTiers.gating.map { tasks.named(it) })
}

if (providers.gradleProperty("jk.test.shuffle").isPresent) {
    afterEvaluate { tasks.withType<Test>().configureEach { maxParallelForks = 1 } }
}
