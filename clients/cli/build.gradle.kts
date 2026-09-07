// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
    application
    alias(libs.plugins.graalvm.native)
}

description = "jk command-line entrypoint (slim wire-only client)"

dependencies {
    // The slim client's whole kernel surface (Stage 5): the jk-api model, the build-file/lockfile
    // readers, the thin client I/O slice (http, forge auth, credential files, CAS read/link), the
    // client-resident JDK/toolchain flow, and the engine wire contract. NO :engine, :io, :resolver,
    // or :toolchain — the compiler enforces that everything heavy reaches the engine over the wire
    // (EngineClient). No in-process engine seam on the production classpath.
    implementation(project(":jk-api"))
    implementation(project(":core"))
    implementation(project(":client-io"))
    implementation(project(":toolchain-jdk"))
    implementation(project(":wire"))
    // The host leaf: JSONL wire envelope, Hashing/PathUtil/Os, Exit (not the plugin SPI).
    implementation(project(":host"))
    implementation(project(":cli-terminal"))

    // ProcessProperties.getArgumentVectorProgramName for argv[0] `jkx` dispatch
    // (Argv0). compileOnly: inside the image the builder provides the implementation;
    // on a JVM every use is gated behind the imagecode property so the class never loads.
    compileOnly(libs.graalvm.nativeimage)

    // Test-only: EngineClientTest hosts an in-process EngineServer for protocol coverage
    // (not production dual-path). Command tests spawn the real shadow jar over the wire.
    testImplementation(project(":engine"))
    // GpgTestFixture (publish command tests).
    testImplementation(libs.bouncycastle.bcpg)
    // JkWireModel (compiled from the IntelliJ tree, see intellijParserSrc) uses JetBrains
    // nullness because the platform API does; compile-only, test scope, never shipped.
    testCompileOnly(libs.jetbrains.annotations)
    // The tree's shared test primitives (`cc.jumpkick.testing`): `Await`, `ShortTempDirs`,
    // `SysProps`, `LoopbackHttp`. A separate source set of :host, so `checkCliRuntimeClasspath`
    // below still sees a runtime classpath with no test code and no JUnit on it.
    testImplementation(testFixtures(project(":host")))
    testImplementation(testFixtures(project(":core")))
}

// The IntelliJ plugin is a standalone Gradle build no gate compiles (see checkIdeClientWiring),
// but its wire parser needs no platform SDK: JkWireModel imports only java.util/regex and
// org.jetbrains.annotations. Compiling that ONE file into this module's tests puts the regex
// parser itself in-gate — the parallel-array alignment and null-vs-empty rules G22 arm 4 can only
// approximate textually — single-sourced from the plugin's own tree, materialized per build.
val intellijParserSrc by tasks.registering(Sync::class) {
    from(rootProject.file("clients/intellij/src/main/java")) { include("**/JkWireModel.java") }
    into(layout.buildDirectory.dir("intellij-parser-src"))
}
sourceSets.test { java.srcDir(intellijParserSrc.map { it.destinationDir }) }


// Thin JVM client (installDist) — no engine on the classpath. Spawns jk-engine.jar via EngineInstall
// / JK_ENGINE_EXE. Preferred production dist is the native image; this path is the supported
// Windows client when Smart App Control blocks unsigned jk.exe, and Temurin-only CI.
application {
    mainClass.set("cc.jumpkick.cli.Jk")
    applicationName = "jk"
    applicationDefaultJvmArgs =
            listOf("-XX:+UseSerialGC", "-Xms24m", "-Xmx128m", "--enable-native-access=ALL-UNNAMED")
}

// Worker jars for integration tests that fork plugin JVMs (same wiring former :cli-engine used).
val kotlinWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val groovyWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val testRunnerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val auditorWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val publisherWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val imageBuilderWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val springBootWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val androidWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
val javaCompilerWorkerJar by configurations.creating {
    isCanBeConsumed = false; isCanBeResolved = true; isTransitive = false
}
dependencies {
    kotlinWorkerJar(project(":kotlin-compiler"))
    groovyWorkerJar(project(":groovy-compiler"))
    testRunnerJar(project(":test-runner"))
    auditorWorkerJar(project(":auditor"))
    publisherWorkerJar(project(":publisher"))
    imageBuilderWorkerJar(project(":image-builder"))
    springBootWorkerJar(project(":spring-boot"))
    androidWorkerJar(project(":android"))
    javaCompilerWorkerJar(project(":java-compiler"))
}

// Root for this task's sandboxes. It must be OUTSIDE the checkout (see cliTestTmpDirShort below);
// its length is no longer a constraint, because the tier binds no Unix domain socket — it speaks
// loopback TCP. The old `length <= 60` gate here was a budget against `sun_path` that
// was never derived from the suffix it had to leave room for: on macOS it admitted the 48-char
// per-user $TMPDIR, which composed a 103-byte socket path against the 102 the JDK will bind, and
// every engine-spawning test in this tier failed. The platform tmpdir is the right answer now, and
// the literal "/tmp" is wrong on Windows (<drive>:\tmp).
val shortTmpRoot: File = File(System.getProperty("java.io.tmpdir", "/tmp"))
val cliTestStateDirShort =
        shortTmpRoot.resolve(
                "jk-cli-${System.currentTimeMillis().toString(36)}-${(System.identityHashCode(project) and 0xffff).toString(16)}")

// @TempDir root for the integration tier. It MUST live outside the repo checkout: the shared
// convention points java.io.tmpdir at build/tmp (inside clients/cli, which has its own jk.toml),
// so @TempDir project dirs would find — and the "promote to workspace" tests would MUTATE — the
// real repo's jk.toml. That is the whole requirement now; path length is not part of it.
val cliTestTmpDirShort =
        shortTmpRoot.resolve(
                "jk-cli-tmp-${System.currentTimeMillis().toString(36)}-${(System.identityHashCode(project) and 0xffff).toString(16)}")

// Sandbox cleanup must run when the tier FAILS too — doLast is skipped on failure, and failed
// runs are exactly the ones that leave the most litter under the tmp root.
val cleanCliTestSandboxes by tasks.registering {
    doLast {
        Trees.deleteNoFollow(cliTestStateDirShort)
        Trees.deleteNoFollow(cliTestTmpDirShort)
    }
}

// Unit vs integration (suite performance):
// :cli:test — pure unit (TUI/args/jsonl); NO engine spawn tax
// :cli:integrationTest — Jk.execute + wire engine (serial, worker jars)
tasks.named<Test>("test") {
    // Engine spawn (PosixDetach setsid) + MemoryProbe FFM not needed for pure unit, but
    // keep native-access harmless for any accidental FFM use in TUI.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // No shadowJar / worker jar dependsOn — pure unit must not wait on fat packaging.
    // Deterministic TUI ANSI assertions (CI runners otherwise force TERM=dumb / NO_COLOR).
    environment("TERM", "xterm-256color")
    environment("CI", "false")
    environment("NO_COLOR", "")
    // Same reason, for glyphs: nerd-font defaults to "auto", which inspects TERM_PROGRAM and the
    // terminal's own config. Unpinned, the developer's terminal decides whether PUA caps appear and
    // TUI assertions differ between Ghostty, Terminal.app, and CI. Tests that exercise the glyphs
    // pass caps explicitly (withCaps / NerdFontCaps args), so pinning the ambient default off costs
    // no coverage.
    environment("JK_NERD_FONT", "false")
    // EngineTestExtension (materialize + stop-after-every-class) stays unloaded here: the unit
    // tier must not spawn engines. That is the conventions default for every tier,
    // so this tier states nothing; `integrationTest` below is the one that overrides it.
    systemProperty(
            "junit.jupiter.tempdir.deletion.strategy.default",
            "cc.jumpkick.cli.engine.JkTempDirDeletionStrategy")
    systemProperty(
            "junit.jupiter.tempdir.factory.default",
            "cc.jumpkick.cli.engine.JkTempDirFactory")
}

// The tier and the curated lane, configured once: the lane runs a subset of these classes, so it
// needs the same worker jars, sandbox roots and transport or it is exercising a different product.
// The nightly tiers spawn the same real engine: a network-tagged smoke that scaffolds a project and
// builds it needs the engine jar, the worker repo and the isolated home exactly as the branch lane does.
val engineSpawningTiers = CuratedIntegration.integrationTasks + setOf(TestTiers.NETWORK, TestTiers.SLOW)
tasks.withType<Test>().matching { it.name in engineSpawningTiers }.configureEach {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // As :cli:test — keep ambient terminal detection out of rendered-output assertions.
    environment("JK_NERD_FONT", "false")
    // Single fork: one resident engine / JK_STATE_DIR per suite.
    maxParallelForks = 1
    dependsOn(
            ":engine:shadowJar",
            kotlinWorkerJar, groovyWorkerJar, testRunnerJar, auditorWorkerJar, publisherWorkerJar,
            imageBuilderWorkerJar, springBootWorkerJar, androidWorkerJar,
            javaCompilerWorkerJar,
            ":kotlin-compiler:stageWorkerRepo",
            ":groovy-compiler:stageWorkerRepo",
            ":java-compiler:stageWorkerRepo",
            ":test-runner:stageWorkerRepo",
            ":auditor:stageWorkerRepo",
            ":publisher:stageWorkerRepo",
            ":image-builder:stageWorkerRepo",
            ":spring-boot:stageWorkerRepo",
            ":android:stageWorkerRepo",
            ":micronaut:stageWorkerRepo")
    environment("TERM", "xterm-256color")
    environment("CI", "false")
    environment("NO_COLOR", "")
    // Loopback TCP, on every platform, for the one tier that spawns real engines. Two reasons,
    // and the second is the bigger one:
    //   * a TCP port has no `sun_path` budget, so the sandbox root's length stops being load-
    //     bearing — a macOS per-user $TMPDIR composed a 103-byte socket path against the JDK's
    //     102-byte limit and every engine-spawning test in this tier failed to bind;
    //   * Windows is otherwise the only user of this lane, so it was carried by two forced-property
    //     tests. Now the whole tier exercises it, everywhere.
    // Environment, not -D: EngineSpawn's child inherits the environment, not our properties.
    environment("JK_ENGINE_TRANSPORT", "tcp")
    // Fail fast if the engine stops streaming (default is 60 minutes — freezes the full suite).
    environment("JK_STREAM_IDLE_MS", "45000")
    systemProperty(
            "jk.test.cache.dir",
            layout.buildDirectory.dir("test-shared-cache").get().asFile.absolutePath)
    // Real engine over the wire — never jk.test.noEngine.
    // EngineTestExtension autodetection: materialize jar + stop engine after each class.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
    systemProperty(
            "junit.jupiter.tempdir.deletion.strategy.default",
            "cc.jumpkick.cli.engine.JkTempDirDeletionStrategy")
    systemProperty(
            "junit.jupiter.tempdir.factory.default",
            "cc.jumpkick.cli.engine.JkTempDirFactory")
    // Override the shared build/tmp (inside the repo) so @TempDir lands outside the checkout.
    systemProperty("java.io.tmpdir", cliTestTmpDirShort.absolutePath)
    doFirst {
        cliTestTmpDirShort.mkdirs()
        cliTestStateDirShort.mkdirs()
        environment("JK_STATE_DIR", cliTestStateDirShort.absolutePath)
        val testJkHome = layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath
        environment("JK_HOME", testJkHome)
        environment("JK_JDKS_DIR", "$testJkHome/jdks")
        environment("JK_JDK_PROBES", "java-home,jk")
        val store = file("$testJkHome/store")
        listOf(
                        ":kotlin-compiler",
                        ":groovy-compiler",
                        ":java-compiler",
                        ":test-runner",
                        ":auditor",
                        ":publisher",
                        ":image-builder",
                        ":spring-boot",
                        ":android",
                        ":micronaut")
                .forEach { p ->
                    val src = project(p).layout.buildDirectory.dir("worker-repo").get().asFile
                    if (src.isDirectory) src.copyRecursively(store, overwrite = true)
                }

        val engineJar = project(":engine").tasks.named("shadowJar", org.gradle.jvm.tasks.Jar::class.java)
                .get().archiveFile.get().asFile
        systemProperty("jk.engine.jar", engineJar.absolutePath)
        systemProperty("jk.kotlin.plugin.jar", kotlinWorkerJar.singleFile.absolutePath)
        systemProperty("jk.groovy.plugin.jar", groovyWorkerJar.singleFile.absolutePath)
        systemProperty("jk.java.plugin.jar", javaCompilerWorkerJar.singleFile.absolutePath)
        systemProperty("jk.test.runner.jar", testRunnerJar.singleFile.absolutePath)
        systemProperty("jk.auditor.plugin.jar", auditorWorkerJar.singleFile.absolutePath)
        systemProperty("jk.publisher.plugin.jar", publisherWorkerJar.singleFile.absolutePath)
        systemProperty("jk.image-builder.plugin.jar", imageBuilderWorkerJar.singleFile.absolutePath)
        systemProperty("jk.spring-boot.plugin.jar", springBootWorkerJar.singleFile.absolutePath)
        systemProperty("jk.android.plugin.jar", androidWorkerJar.singleFile.absolutePath)
    }
    finalizedBy(cleanCliTestSandboxes)
}

graalvmNative {
    binaries.named("main") {
        imageName.set("jk")
        mainClass.set("cc.jumpkick.cli.Jk")
        // The plugin's "main" binary is supposed to default to executable,
        // but the 0.10.4 / GraalVM 25 combination defaults to shared library
        // on this host. Force the executable mode explicitly.
        sharedLibrary.set(false)
        // Slim classpath only (Stage 5) — never link :engine.
        classpath(tasks.named("jar"), configurations.runtimeClasspath)

        // Size-first build args. The jk binary's primary UX budget is its download +
        // on-disk size and shell-integration startup latency; per-verb CPU work is
        // shrinking as the CLI delegates the heavy lifting (hashing, compiling,
        // packaging) to the resident engine and its forked workers.
        // -Os Optimize for size. (History: was -O3 + -march=x86-64-v3, tuned when
        // the CLI process itself did the CAS/ClasspathFingerprint SHA-256
        // work — the SIMD -march bought ≈1.5x on no-op builds then. Since the
        // Stage 5 split that hashing lives in the jk-engine jar, which
        // re-tunes for speed independently — see :engine shadowJar.)
        // --gc=serial
        // Generational serial GC. Small/fast for short verbs and a ≤256 MiB
        // engine heap alike, and — unlike epsilon — it actually reclaims, so
        // verbs that stream data don't accumulate every transient byte until
        // the process dies.
        // -R:MaxHeapSize=134217728
        // Hard 128 MiB max heap for the CLI process. jk's own work is tiny;
        // the cap turns any runaway allocation into a fast, loud OOM instead
        // of dragging the machine into swap. Heavy work runs in the engine
        // (spawned with its own -Xms/-Xmx, which override this baked default)
        // and in forked worker JVMs tuned via JvmOptions.
        // -R:MinHeapSize=25165824
        // 24 MiB initial heap — sized to what a trivial verb actually uses
        // (`jk --help` measured ~19 MiB RSS), so the smallest commands fit in
        // the floor without a growth step, while anything bigger still grows
        // lazily toward the 128 MiB cap.
        buildArgs.add("-Os")
        buildArgs.add("--gc=serial")
        buildArgs.add("-R:MaxHeapSize=134217728")
        buildArgs.add("-R:MinHeapSize=25165824")
        // Silence the FFM "restricted method" runtime warning. Without this,
        // every wizard invocation prints a 4-line WARNING block before the UI.
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        // WindowsUtf8 binds Kernel32 via FFM at first enable() — keep that off the
        // image-build heap so downcalls resolve against the running process.
        buildArgs.add("--initialize-at-run-time=cc.jumpkick.terminal.windows.WindowsUtf8")
    }

}


