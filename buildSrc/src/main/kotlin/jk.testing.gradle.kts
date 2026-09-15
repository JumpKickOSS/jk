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

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real product layout. JK_HOME relocates the whole tree.
    //
    // Not inside the checkout: a git command handed a path in the source tree that has stopped
    // existing resolves to the repository enclosing it, and a test sandbox full of jk's own layout is
    // a plausible thing to hand one. The require() states the property where it cannot drift.
    val testHomeDir = JkLayoutPaths.testHomeFor(rootDir, project.path)
    require(!testHomeDir.absoluteFile.normalize().startsWith(rootDir.absoluteFile.normalize())) {
        "test JK_HOME must be outside the checkout, was $testHomeDir under $rootDir"
    }
    val testJkHome = testHomeDir.absolutePath
    // A hosted runner sets CI, and jk's own forked test JVMs never see it (WorkerEnv passes an
    // allow-list). The guard freezer refuses to write under CI, so a suite that exercises it must
    // not learn from the environment which machine it is on; the two builds' test JVMs agree here.
    environment.remove("CI")
    environment.remove("GITHUB_ACTIONS")
    environment("JK_HOME", testJkHome)
    environment("JK_JDKS_DIR", "$testJkHome/jdks")
    // The probe chain is the machine's unless narrowed: sdkman, mise, IntelliJ, /usr/lib/jvm. A jdk
    // verb under test would list, default to, write pointers at or uninstall the developer's own
    // installs. Only the JDK this build runs on and jk's own root are visible to a test.
    environment("JK_JDK_PROBES", "java-home,jk")
    // M2Dirs honours JK_M2_LOCAL so mock-Maven tests cannot overwrite ~/.m2. It travels with the home
    // for the same reason, and the sweep below deletes the pair together.
    val testM2 = File(testHomeDir.parentFile, "${testHomeDir.name}-m2").absolutePath
    environment("JK_M2_LOCAL", testM2)
    // The warm home is a feature (two suites prime the store on purpose) and a liability when it is
    // unbounded: clients/cli and server/engine each reach a gibibyte. A week or a gibibyte, whichever
    // comes first, wipes the whole root and re-stamps; every suite tolerates a cold first run.
    doFirst {
        val home = File(testJkHome)
        val stamp = File(home, ".wiped-at")
        val weekMs = 7L * 24 * 60 * 60 * 1000
        val capBytes = 1L shl 30
        val stale = stamp.isFile && System.currentTimeMillis() - stamp.lastModified() > weekMs
        // The home holds stable JDK pointers that are links to installs made by other tools, and a
        // sweep that walks through a link empties the install it points at. Neither walk here follows one.
        if (stale || Trees.exceedsNoFollow(home, capBytes)) {
            Trees.deleteNoFollow(home)
            Trees.deleteNoFollow(File(testM2))
        }
        if (!stamp.isFile) {
            home.mkdirs()
            stamp.writeText("Sweep stamp for the warm test home; see jk.testing.\n")
        }
    }
    environment("JK_AUTO_PRUNE", "false")
    environment("JK_HTTP_ENABLED", "false")
    // No suite may prompt. A worker with a console attached — every Windows one — otherwise counts
    // as promptable, so a command that confirms takes the raw keystroke path and reads that console
    // instead of the stdin the test injected. Nobody types into it, and a native console read
    // answers no interrupt, so the per-test timeout above cannot end it either: the whole task sits
    // until its own cap and reports no result. A CI environment already forces this off; a
    // developer machine now agrees with it.
    environment("JK_NONINTERACTIVE", "1")
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

// The per-module XML the coverage inventory (coverageReport) aggregates; produced only when the agent ran.
tasks.named<org.gradle.testing.jacoco.tasks.JacocoReport>("jacocoTestReport") {
    reports {
        xml.required.set(true)
        html.required.set(false)
        csv.required.set(false)
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
        // The bench tier's ratchet reads the banked medians here (cc.jumpkick.testing.BenchBand).
        systemProperty("jk.wallBaseline", rootProject.layout.projectDirectory.file("wall-baseline.toml").asFile.absolutePath)
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
    "Framework/language e2e suites (@Tag slow). On demand — never in checkAll.")

slowTier(
    TestTiers.NETWORK,
    Duration.ofMinutes(30),
    "Tests that talk to a real remote (@Tag network). On demand only — never in checkAll.")

slowTier(
    TestTiers.BENCH,
    Duration.ofMinutes(30),
    "Microbenchmarks (@Tag bench). Prints medians, gates nothing — run on demand.")

// The curated lane. Not a tier: the same integration filters, narrowed to the classes
// `curated-integration.txt` names for this module, so a class runs here AND in the nightly
// integration tier. Registered only where the registry has entries — a Test task with no matching
// class is a green report about nothing.
val curatedRegistry = rootProject.layout.projectDirectory.file(CuratedIntegration.REGISTRY)
val curatedHere = CuratedIntegration.forModule(curatedRegistry.asFile.readText(), project.path)
if (curatedHere.isNotEmpty()) {
    tasks.register<Test>(CuratedIntegration.TASK) {
        group = "verification"
        description = "Curated integration classes for this module"
        val testSourceSet = sourceSets["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { tier(TestTiers.INTEGRATION).applyTo(this) }
        // A renamed or deleted class must fail the lane rather than shrink it silently. The guard
        // catches that from the source tree; this catches it from the runtime.
        filter {
            curatedHere.forEach { includeTestsMatching(it.fqcn) }
            isFailOnNoMatchingTests = true
        }
        inputs.file(curatedRegistry).withPropertyName("curatedRegistry")
        shouldRunAfter(tasks.named("test"))
        // One JVM per class. The lane is a subset, so it puts classes next to each other that the
        // full tier never does, and a class that runs an engine in-process leaves process-wide
        // state behind — enough to make a later class's worker fork die. A fresh JVM per class
        // costs a second each and makes the verdict independent of who else is in the registry.
        forkEvery = 1
        systemProperty("junit.jupiter.execution.timeout.default", "300s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        timeout.set(Duration.ofMinutes(20))
    }
}

tasks.register("checkAll") {
    group = "verification"
    description = "Unit test + integrationTest for this module"
    dependsOn(TestTiers.gating.map { tasks.named(it) })
}

if (providers.gradleProperty("jk.test.shuffle").isPresent) {
    afterEvaluate { tasks.withType<Test>().configureEach { maxParallelForks = 1 } }
}
