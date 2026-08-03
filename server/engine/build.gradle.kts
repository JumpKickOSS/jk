// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.java-conventions")
    application
    id("com.gradleup.shadow") version "9.2.2"
}

// Must match cc.jumpkick.model.JkVersion.VERSION: the client only spawns an engine jar whose
// filename version equals its own baked-in version.
version = "0.11.0"

description = "jk build engine: EngineMain, Pipeline/Step scheduler, and build pipeline. " +
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
}

application {
    mainClass.set("cc.jumpkick.engine.EngineMain")
    applicationName = "jk-engine"
    // PosixDetach setsid(2) FFM; heap/GC for a long-lived engine are set on the spawn line by the
    // client (EngineClient) for the resident daemon — installDist/run defaults stay modest.
    applicationDefaultJvmArgs =
            listOf("-XX:+UseSerialGC", "-Xms32m", "-Xmx256m", "--enable-native-access=ALL-UNNAMED")
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
}

/**
 * JK-1194: materialize the freshly-built engine fat jar into the versioned data layout
 * (`…/share/jk/versions/<v>/` or `$JK_HOME/versions`) and bounce the resident daemon so local
 * dogfood picks up engine-side first-party plugin tables without a hand copy.
 *
 * Client resolution (first hit wins): `:cli:installDist` bin, `build/dist/jk`, platform bin dir
 * (`~/.local/bin/jk`), then PATH `jk`.
 */
tasks.register("installLocal") {
    group = "distribution"
    description =
        "Materialize shadowJar into the versioned layout and restart the engine (JK-1194 dogfood)"
    dependsOn(tasks.named("shadowJar"))
    doLast {
        val engineJar =
            tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile
                .get()
                .asFile
        val installDistJk =
            rootProject.project(":cli").layout.buildDirectory.file("install/jk/bin/jk").get().asFile
        val client =
            JkLayoutPaths.resolveClient(rootProject.projectDir, installDistJk)
                ?: "jk"
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
                        "cannot run jk client '$client' (${e.message}). " +
                            "Build a client first: ./gradlew :cli:installDist  or  ./gradlew dist  " +
                            "or install to ${JkLayoutPaths.binDir()}",
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
tasks.withType<Test>().configureEach {
    // MemoryProbe's host_statistics64 FFM downcall (macOS memory read).
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    dependsOn(javaCompilerWorkerJar, testRunnerJarCfg)
    doFirst {
        systemProperty("jk.java.plugin.jar", javaCompilerWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar", testRunnerJarCfg.singleFile.absolutePath)
    }
}

// Pass the spring-boot worker jar to tests (the plugin build runtime forks it for Boot projects).
val testSpringBootWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testSpringBootWorkerJar(project(":spring-boot")) }
tasks.withType<Test>().configureEach {
    dependsOn(testSpringBootWorkerJar)
    doFirst { systemProperty("jk.spring-boot.plugin.jar", testSpringBootWorkerJar.singleFile.absolutePath) }
}

// Pass the grails worker jar to tests (the Grails e2e integration test forks it).
val testGrailsWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testGrailsWorkerJar(project(":grails")) }
tasks.withType<Test>().configureEach {
    dependsOn(testGrailsWorkerJar)
    doFirst { systemProperty("jk.grails.plugin.jar", testGrailsWorkerJar.singleFile.absolutePath) }
}

// Pass the android worker jar to tests (the Android-spike integration test forks it).
val testAndroidWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testAndroidWorkerJar(project(":android")) }
tasks.withType<Test>().configureEach {
    dependsOn(testAndroidWorkerJar)
    doFirst { systemProperty("jk.android.plugin.jar", testAndroidWorkerJar.singleFile.absolutePath) }
}

// Pass the protobuf worker jar to tests (the protoc codegen integration test forks it).
val testProtobufWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testProtobufWorkerJar(project(":protobuf")) }
tasks.withType<Test>().configureEach {
    dependsOn(testProtobufWorkerJar)
    doFirst { systemProperty("jk.protobuf.plugin.jar", testProtobufWorkerJar.singleFile.absolutePath) }
}

// Pass the shrink worker jar to tests (the R8 shrunk-jar integration test forks it).
val testShrinkWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testShrinkWorkerJar(project(":shrink")) }
tasks.withType<Test>().configureEach {
    dependsOn(testShrinkWorkerJar)
    doFirst { systemProperty("jk.shrink.plugin.jar", testShrinkWorkerJar.singleFile.absolutePath) }
}

// Pass the kotlin-compiler worker jar to tests (the KSP/Room/Hilt gate compiles Kotlin).
val testKotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testKotlinWorkerJar(project(":kotlin-compiler")) }
tasks.withType<Test>().configureEach {
    dependsOn(testKotlinWorkerJar)
    doFirst { systemProperty("jk.kotlin.plugin.jar", testKotlinWorkerJar.singleFile.absolutePath) }
}

// Pass the groovy-compiler worker jar to tests (the Groovy compile integration test forks it).
val testGroovyWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testGroovyWorkerJar(project(":groovy-compiler")) }
tasks.withType<Test>().configureEach {
    dependsOn(testGroovyWorkerJar)
    doFirst { systemProperty("jk.groovy.plugin.jar", testGroovyWorkerJar.singleFile.absolutePath) }
}

// Pass the auditor jar path to tests (EngineServerTest's hosted jk audit round-trip forks it).
val testAuditorWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies { testAuditorWorkerJar(project(":auditor")) }
tasks.withType<Test>().configureEach {
    dependsOn(testAuditorWorkerJar)
    doFirst { systemProperty("jk.auditor.plugin.jar", testAuditorWorkerJar.singleFile.absolutePath) }
}
