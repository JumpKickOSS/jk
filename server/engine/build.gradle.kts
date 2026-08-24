// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.java-conventions")
    application
    id("com.gradleup.shadow") version "9.2.2"
}

// Must match cc.jumpkick.model.JkVersion.VERSION: the client only spawns an engine jar whose
// filename version equals its own baked-in version.
version = "0.12.0"

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
 * Materialize the freshly-built engine fat jar into the product lib, {@code <data>/lib/jk-engine/}
 * ({@code ~/.local/share/jk/lib/…} or {@code $JK_HOME/data/lib/…}), and bounce the resident daemon so local dogfood
 * picks up engine-side first-party plugin tables without a hand copy.
 *
 * Always the native client at {@code build/dist/jk} ({@code jk.exe} on Windows) from the root
 * {@code dist} task. Never the thin JVM {@code :cli:installDist} scripts ({@code jk.bat}).
 */
tasks.register("installLocal") {
    group = "distribution"
    description = "Materialize shadowJar into the product lib and restart the engine"
    dependsOn(tasks.named("shadowJar"))
    // Native client must exist before materialize. `dist` also copies this module's shadowJar
    // into build/dist/lib, so `./gradlew dist installLocal` does not race nativeCompile.
    dependsOn(":dist")
    doLast {
        val engineJar =
            tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile
                .get()
                .asFile
        val client =
            JkLayoutPaths.resolveClient(rootProject.projectDir)
                ?: throw GradleException(
                    "cannot find native jk client at build/dist/jk[.exe]. " +
                        "Build it first: ./gradlew dist")
        fun runJk(vararg args: String) {
            val cmd = listOf(client) + args.toList()
            val pb = ProcessBuilder(cmd)
            pb.inheritIO()
            pb.directory(rootProject.projectDir)
            val code =
                try {
                    pb.start().waitFor()
                } catch (e: java.io.IOException) {
                    throw GradleException(
                        "cannot run native jk client '$client' (${e.message}). " +
                            "Build it first: ./gradlew dist",
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
        val store = file("$home/data/store") // JK_HOME mirrors XDG: store is <data>/store
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
// it never uses (JK-2195). java-compiler/test-runner above stay on every Test task: they
// are direct engine collaborators and PluginLoaderTest assume-skips without them.
val integrationWorkerJars = listOf(
    "jk.spring-boot.plugin.jar" to ":spring-boot",
    "jk.grails.plugin.jar" to ":grails",
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
// separately rather than loading it out of the plugin jar (JK-1449).
val testApksig by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true
}
dependencies { testApksig("com.android.tools.build:apksig:8.7.3") }

tasks.named<Test>("integrationTest") {
    integrationWorkerJars.forEach { (prop, cfg) ->
        dependsOn(cfg)
        // inputs.files is what makes the up-to-date check see a rebuilt plugin. dependsOn only
        // orders the tasks, and a doFirst systemProperty is set at execution time — so without
        // this, editing a plugin's source left integrationTest UP-TO-DATE and Gradle replayed the
        // previous run's results. Revert checks against a plugin change came back green as
        // no-ops until `--rerun` was passed by hand (JK-2404).
        inputs.files(cfg).withPropertyName(prop).withPathSensitivity(PathSensitivity.NONE)
        doFirst { systemProperty(prop, cfg.singleFile.absolutePath) }
    }
    seedWorkerRepos(
            ":spring-boot",
            ":grails",
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
