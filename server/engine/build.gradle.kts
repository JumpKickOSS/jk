// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.nullmarked-conventions")
    application
    id("com.gradleup.shadow") version "9.2.2"
}

// Must match cc.jumpkick.model.JkVersion.VERSION: the client only spawns an engine jar whose
// filename version equals its own baked-in version.
version = "0.13.1"

description = "jk build engine: EngineMain, BuildPlan/Task scheduler, and build pipeline. " +
        "Server-only — never links the CLI. Ships as jk-engine-<version>.jar."

dependencies {
    // The client<->engine wire contract (protocol codec, EnginePaths, build DTOs) — api so a
    // caller of BuildService sees the DTO types (slim-client Stage 5).
    api(project(":wire"))
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":plugin-sdk"))
    implementation(project(":resolver"))
    implementation(project(":toolchain"))
    implementation(project(":guard"))
    // The engine writes a guard suite's GuardConfig and forks the launcher that runs it; it never
    // runs JUnit itself, so guard-api's JUnit API — an `api` dependency for the suites that compile
    // against @Guard — stays off the engine classpath and out of the fat jar.
    implementation(project(":guard-api")) {
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
    implementation(project(":dynamic-surface"))
    // The web dashboard's static assets ride the engine's runtime classpath as /web/* (served by
    // StaticContent) and get bundled into the jk-engine fat jar. Kept resources-only + runtimeOnly
    // so the assets never touch the compile classpath and the native CLI never links them.
    runtimeOnly(project(":web"))
    // ASM — used by the incremental Java compiler's dependency extraction
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)

    // JGit is the in-process fallback git backend (used when no `git` command is found) and also
    // builds the git fixtures in tests. The native CLI (:cli) does not depend on :engine, so this
    // never enters a native image — only the engine fat jar.
    implementation(libs.jgit)
    // Giter8 apply. Must not leak onto the native CLI (this module never does).
    implementation(libs.st4)
    // XZ inflate for release client binaries (`EngineMain --inflate-xz`). The native CLI must
    // not link this — it shells out to the engine jar.
    implementation(libs.tukaani.xz)

    // The tree's one poll-until-true helper (`cc.jumpkick.testing.Await`), so this module's tests
    // do not carry a private copy of the loop. Test-only source set: nothing here reaches `main`,
    // the fat jar or a worker.
    testImplementation(testFixtures(project(":host")))
}

// Production engine has no flattened catalog — BuiltInPluginJars reads each
// plugin jar's root jk-plugin.toml. Tests still parse PluginTableRegistry.
tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    pluginManifestResources(rootProject)
}

tasks.named<Jar>("jar") {
    doLast { assertJarHasNoFlattenedPluginCatalog(archiveFile.get().asFile) }
}

application {
    mainClass.set("cc.jumpkick.engine.EngineMain")
    applicationName = "jk-engine"
    // PosixDetach setsid(2) FFM; heap/GC for a long-lived engine are set on the spawn line by the
    // client (EngineClient) for the resident daemon — installDist/run defaults stay modest.
    applicationDefaultJvmArgs = listOf(
            "-XX:+UseSerialGC",
            "-XX:MinHeapFreeRatio=10",
            "-XX:MaxHeapFreeRatio=25",
            "-XX:-ShrinkHeapInSteps",
            "-Xms32m",
            "-Xmx256m",
            "--enable-native-access=ALL-UNNAMED")
}

// The engine artifact of the native dist (docs/architecture.md "Two artifacts"): this module's
// runtime classpath rolled up into a single fat jar, jk-engine-<version>.jar. Never a native image.
tasks.shadowJar {
    archiveBaseName.set("jk-engine")
    archiveClassifier.set("")
    manifest {
        attributes("Main-Class" to "cc.jumpkick.engine.EngineMain")
    }
    mergeServiceFiles()
    doLast { assertJarHasNoFlattenedPluginCatalog(archiveFile.get().asFile) }
}

/**
 * Materialize the freshly-built engine fat jar into the product lib, {@code <home>/lib/jk-engine/}
 * ({@code ~/.jk/lib/…}), and bounce the resident daemon so local dogfood
 * picks up engine-side first-party plugin tables without a hand copy.
 *
 * Runs through a client that reports the fat jar's own version — {@code :cli:nativeCompile} output
 * first, then the thin JVM {@code :cli:installDist} launcher ({@code jk.bat} / {@code jk}), then a
 * ship-layout {@code build/dist/jk[.exe]}. Windows Smart App Control blocks unsigned {@code
 * jk.exe}, so the thin client is a supported dogfood path there. Fails when none matches, rather
 * than handing a new engine to an old client (which refuses it, quietly, from the installer's
 * point of view).
 */
tasks.register("installLocal") {
    group = "distribution"
    description = "Materialize shadowJar into the product lib and restart the engine"
    dependsOn(tasks.named("shadowJar"))
    // Thin launcher is always cheap, and is the one client this task can guarantee is current.
    dependsOn(":cli:installDist")
    // Do not probe/exec a native binary a concurrent Sync/native-image write still has open
    // (ETXTBSY / "Text file busy" on Linux). Order only — do not dependsOn, so plain
    // installLocal stays cheap when dist is not requested.
    mustRunAfter(rootProject.tasks.named("dist"))
    mustRunAfter(":cli:nativeCompile")
    doLast {
        val engineJar =
            tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile
                .get()
                .asFile
        // The client materializes under its own version, and refuses a jar that is not it, so the
        // one that matches this jar is the only one that can install it. Position is not enough:
        // a build/dist (or nativeCompile output) from an older day is runnable and wrong.
        val engineVersion =
            JkLayoutPaths.engineJarVersion(engineJar)
                ?: throw GradleException("not a jk-engine-<version>.jar: $engineJar")
        val probes = JkLayoutPaths.probeClients(rootProject.projectDir)
        val client =
            (JkLayoutPaths.pickClient(probes, engineVersion)
                    ?: throw GradleException(
                        "no jk client reports version $engineVersion, so ${engineJar.name} cannot be " +
                            "materialized. Clients found:\n" +
                            JkLayoutPaths.describeProbes(probes) +
                            "\nBuild a matching one: ./gradlew :cli:nativeCompile  or  " +
                            "./gradlew :cli:installDist  (an older build/dist is ignored, not used)"))
                .absolutePath
        fun runJk(vararg args: String) {
            val cmd = JkLayoutPaths.launchCommand(client, *args)
            val pb = ProcessBuilder(cmd)
            pb.inheritIO()
            pb.directory(rootProject.projectDir)
            val code =
                try {
                    pb.start().waitFor()
                } catch (e: java.io.IOException) {
                    throw GradleException(
                        "cannot run jk client '$client' (${e.message}). " +
                            "On Windows, unsigned jk.exe may be blocked by Smart App Control — " +
                            "use the thin client: ./gradlew :cli:installDist",
                        e)
                }
            if (code != 0) {
                throw GradleException("command failed ($code): ${cmd.joinToString(" ")}")
            }
        }
        logger.lifecycle("jk self materialize {} {}", client, engineJar)
        runJk("self", "materialize", client, engineJar.absolutePath)
        // Best-effort bounce — ignore failures if no daemon was running.
        try {
            runJk("engine", "stop", "--force")
        } catch (_: Exception) {
            logger.lifecycle("engine stop skipped (not running or client unavailable)")
        }
        try {
            runJk("engine", "start")
        } catch (_: Exception) {
            logger.lifecycle("engine start skipped (will spawn on next command)")
        }
    }
}

// ---------------------------------------------------------------------------
// Worker jar paths for tests (WorkerJavacTest, KotlinWorkerSetupTest, etc.)
val testRunnerJarCfg by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
dependencies {
    testRunnerJarCfg(project(":test-runner"))
}

val javaCompilerWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { javaCompilerWorkerJar(project(":java-compiler")) }

val quarkusPluginJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { quarkusPluginJar(project(":quarkus")) }
fun Test.seedWorkerRepos(vararg projects: String) {
    projects.forEach { dependsOn("$it:stageWorkerRepo") }
    doFirst {
        val home = environment["JK_HOME"] as? String ?: return@doFirst
        val store = file("$home/store")
        projects.forEach { p ->
            val src = project(p).layout.buildDirectory.dir("worker-repo").get().asFile
            if (src.isDirectory) src.copyRecursively(store, overwrite = true)
        }
    }
}

tasks.withType<Test>().configureEach {
    // MemoryProbe's host_statistics64 FFM downcall (macOS memory read).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    seedWorkerRepos(":java-compiler")
    dependsOn(
            javaCompilerWorkerJar,
            testRunnerJarCfg,
            quarkusPluginJar,
            ":java-compiler:writeWorkerPom",
            ":test-runner:writeWorkerPom")
    doFirst {
        systemProperty("jk.java.plugin.jar", javaCompilerWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar", testRunnerJarCfg.singleFile.absolutePath)
        systemProperty("jk.quarkus.plugin.jar", quarkusPluginJar.singleFile.absolutePath)
    }
}

// Worker jars only integration/slow tests fork (Boot/Grails/Android/protobuf/R8/Kotlin/
// Groovy e2e, EngineServerTest's audit round-trip). Scoped to integrationTest so the unit
// `test` task — the PR gate — stops building eight plugin projects and downloading apksig
// it never uses. java-compiler/test-runner above stay on every Test task: they
// are direct engine collaborators and PluginLoaderTest assume-skips without them.
val integrationWorkerJars = listOf(
    "jk.spring-boot.plugin.jar" to ":spring-boot",
    "jk.grails.plugin.jar" to ":grails",
    "jk.micronaut.plugin.jar" to ":micronaut",
    "jk.android.plugin.jar" to ":android",
    "jk.protobuf.plugin.jar" to ":protobuf",
    "jk.minified.plugin.jar" to ":minified",
    "jk.kotlin.plugin.jar" to ":kotlin-compiler",
    "jk.groovy.plugin.jar" to ":groovy-compiler",
    "jk.auditor.plugin.jar" to ":auditor",
).map { (prop, projPath) ->
    val cfg = configurations.create("integrationWorkerJar" + projPath.removePrefix(":").replace("-", "")) {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }
    dependencies.add(cfg.name, project(projPath))
    prop to cfg
}

// apksig for the spike test's APK verification. The worker jar is deliberately non-transitive
// (the plugin resolves its own deps from the store at run time), so the test needs apksig
// separately rather than loading it out of the plugin jar.
val testApksig by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true
}
dependencies { testApksig(libs.apksig) }

// networkTest and slowTest get the same wiring: the shipped-template scaffold-and-build tests and
// the framework e2e suites fork the same plugin workers, and a nightly run of either builds nothing
// else first — without the dependsOn the jars are simply absent there and the tier skips its way
// green. slowTest joined the list when @Tag("slow") moved off the gate; AndroidSpikeTest
// does not skip, it asserts the property is non-blank, so the tier failed on a missing jar rather
// than quietly covering nothing.
// The curated lane is in the list for the same reason: it runs a subset of integrationTest's
// classes, so it needs the same plugin worker jars and seeded repos or it covers something else.
val integrationTiers = CuratedIntegration.integrationTasks + listOf("networkTest", "slowTest")
tasks.withType<Test>().matching { it.name in integrationTiers }.configureEach {
    integrationWorkerJars.forEach { (prop, cfg) ->
        dependsOn(cfg)
        // inputs.files is what makes the up-to-date check see a rebuilt plugin. dependsOn only
        // orders the tasks, and a doFirst systemProperty is set at execution time — so without
        // this, editing a plugin's source left integrationTest UP-TO-DATE and Gradle replayed
        // the previous run's results. Revert checks against a plugin change came back green as
        // no-ops until `--rerun` was passed by hand.
        inputs.files(cfg).withPropertyName(prop).withPathSensitivity(PathSensitivity.NONE)
        doFirst { systemProperty(prop, cfg.singleFile.absolutePath) }
    }
    seedWorkerRepos(
            ":spring-boot",
            ":grails",
            ":micronaut",
            ":android",
            ":protobuf",
            ":minified",
            ":kotlin-compiler",
            ":groovy-compiler",
            ":auditor")
    dependsOn(testApksig)
    inputs.files(testApksig).withPropertyName("apksigClasspath").withPathSensitivity(PathSensitivity.NONE)
    doFirst { systemProperty("jk.android.apksig.classpath", testApksig.asPath) }
}


// `McpDocParityTest` diffs docs/user/mcp.md's tool/resource/prompt tables against the MCP
// registries, and the doc is not otherwise an input of `:engine:test` — without this a doc-only
// edit leaves the task UP-TO-DATE and the parity is silently unchecked (the ActionTreeTest
// pattern; see shared/host/build.gradle.kts).
tasks.named<Test>("test") {
    inputs.file(rootProject.layout.projectDirectory.file("docs/user/mcp.md"))
            .withPropertyName("mcpDoc")
            .withPathSensitivity(PathSensitivity.NONE)
}
