// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
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
    // Shared JSONL reader for the engine/worker wire envelope (not the plugin SPI).
    implementation(project(":jsonl"))

    // JLine 4 FFM terminal provider for raw-mode TUI (jk init wizard).
    // FFM backend requires JDK 22+; the GraalVM-compiled binary embeds the
    // FFM downcalls natively. Reflection/resource hints live under
    // src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/.
    implementation(libs.jline.terminal.ffm)

    // ProcessProperties.getArgumentVectorProgramName for argv[0] `jkx` dispatch
    // (Argv0). compileOnly: inside the image the builder provides the implementation;
    // on a JVM every use is gated behind the imagecode property so the class never loads.
    compileOnly(libs.graalvm.nativeimage)

    // Test-only: EngineClientTest hosts an in-process EngineServer for protocol coverage
    // (not production dual-path). Command tests spawn the real shadow jar over the wire.
    testImplementation(project(":engine"))
    // GpgTestFixture (publish command tests).
    testImplementation(libs.bouncycastle.bcpg)
}

// JK-2139: the native client must not see the plugin SPI jar (codec is :jsonl).
val checkCliRuntimeClasspath by tasks.registering {
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    doLast {
        val forbidden = runtime.get().incoming.artifacts.artifactFiles.files.filter { f ->
            val n = f.name
            n.startsWith("plugin-sdk")
                    || n.startsWith("jk-plugin-sdk")
                    || n.startsWith("maven-artifact")
                    || n.startsWith("plexus-utils")
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                    "CLI runtimeClasspath must not contain plugin-sdk / maven-artifact / plexus-utils: "
                            + forbidden)
        }
    }
}

// JK-2151: native reachability — CLI main must not name parser / plugin-schema types.
val checkCliNoParseTypes by tasks.registering {
    val main = layout.projectDirectory.dir("src/main/java")
    inputs.dir(main)
    doLast {
        val banned = listOf(
            "JkBuildParser",
            "PluginDescriptor",
            "PluginTableRegistry",
            "LockFreshness",
            "LockManifestDigest",
            "PluginContributions",
            "Giter8LocalApply",
            "Giter8Apply",
            "Giter8ShortNames",
            "Giter8TemplateIndex",
            "org.stringtemplate",
            "org.antlr.runtime")
        val hits = fileTree(main) { include("**/*.java") }.files.flatMap { f ->
            val text = f.readText()
            banned.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("CLI main must not reference parser/plugin-schema types (JK-2151): $hits")
        }
    }
}
tasks.named("check") { dependsOn(checkCliRuntimeClasspath); dependsOn(checkCliNoParseTypes) }
tasks.named("jar") { dependsOn(checkCliRuntimeClasspath); dependsOn(checkCliNoParseTypes) }

// Thin JVM client (installDist) — no engine on the classpath. Spawns jk-engine.jar via EngineInstall
// / JK_ENGINE_EXE. Prefer the native image for production dist; this path is for Temurin-only CI.
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
val compatBridgeWorkerJar by configurations.creating {
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
    compatBridgeWorkerJar(project(":compat-bridge"))
    springBootWorkerJar(project(":spring-boot"))
    androidWorkerJar(project(":android"))
    javaCompilerWorkerJar(project(":java-compiler"))
}

// Unique short UDS state dir for this test task run. UDS sun_path is ~108 bytes;
// deep worktree paths under build/ overflow, so pin under /tmp with a per-run id.
val cliTestStateDir =
        layout.buildDirectory
                .dir("cli-test-state")
                .get()
                .asFile
                .also { it.mkdirs() }
// Prefer a short path when build dir is a deep worktree (UDS sun_path ~108 bytes).
val cliTestStateDirShort =
        file(
                "/tmp/jk-cli-${System.currentTimeMillis().toString(36)}-${(System.identityHashCode(project) and 0xffff).toString(16)}")

// @TempDir root for the integration tier. It MUST live outside the repo checkout: the shared
// convention points java.io.tmpdir at build/tmp (inside clients/cli, which has its own jk.toml),
// so @TempDir project dirs would find — and the "promote to workspace" tests would MUTATE — the
// real repo's jk.toml (JK-2329). A short /tmp path also keeps UDS socket paths under sun_path.
val cliTestTmpDirShort =
        file(
                "/tmp/jk-cli-tmp-${System.currentTimeMillis().toString(36)}-${(System.identityHashCode(project) and 0xffff).toString(16)}")

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
    // no coverage (JK-1970).
    environment("JK_NERD_FONT", "false")
    // Do not autoload EngineTestExtension (materialize + stop-after-every-class).
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    systemProperty(
            "junit.jupiter.tempdir.deletion.strategy.default",
            "cc.jumpkick.cli.engine.JkTempDirDeletionStrategy")
    systemProperty(
            "junit.jupiter.tempdir.factory.default",
            "cc.jumpkick.cli.engine.JkTempDirFactory")
}

tasks.named<Test>("integrationTest") {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // As :cli:test — keep ambient terminal detection out of rendered-output assertions (JK-1970).
    environment("JK_NERD_FONT", "false")
    // Single fork: one resident engine / JK_STATE_DIR per suite.
    maxParallelForks = 1
    dependsOn(
            ":engine:shadowJar",
            kotlinWorkerJar, groovyWorkerJar, testRunnerJar, auditorWorkerJar, publisherWorkerJar,
            imageBuilderWorkerJar, compatBridgeWorkerJar, springBootWorkerJar, androidWorkerJar,
            javaCompilerWorkerJar,
            ":kotlin-compiler:stageWorkerRepo",
            ":groovy-compiler:stageWorkerRepo",
            ":java-compiler:stageWorkerRepo",
            ":test-runner:stageWorkerRepo",
            ":auditor:stageWorkerRepo",
            ":publisher:stageWorkerRepo",
            ":image-builder:stageWorkerRepo",
            ":compat-bridge:stageWorkerRepo",
            ":spring-boot:stageWorkerRepo",
            ":android:stageWorkerRepo")
    environment("TERM", "xterm-256color")
    environment("CI", "false")
    environment("NO_COLOR", "")
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
        val store = file("$testJkHome/store")
        listOf(
                        ":kotlin-compiler",
                        ":groovy-compiler",
                        ":java-compiler",
                        ":test-runner",
                        ":auditor",
                        ":publisher",
                        ":image-builder",
                        ":compat-bridge",
                        ":spring-boot",
                        ":android")
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
        systemProperty("jk.compat-bridge.plugin.jar", compatBridgeWorkerJar.singleFile.absolutePath)
        systemProperty("jk.spring-boot.plugin.jar", springBootWorkerJar.singleFile.absolutePath)
        systemProperty("jk.android.plugin.jar", androidWorkerJar.singleFile.absolutePath)
    }
    doLast {
        cliTestStateDirShort.deleteRecursively()
        cliTestTmpDirShort.deleteRecursively()
    }
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
        // JLine 4 FFM's signal handler uses Arena.ofShared, gated behind this
        // flag in GraalVM 25. Without it the wizard crashes on Signal.INT setup.
        buildArgs.add("-H:+SharedArenaSupport")
        // Silence the FFM "restricted method" runtime warning. Without this,
        // every wizard invocation prints a 4-line WARNING block before the UI.
        buildArgs.add("--enable-native-access=ALL-UNNAMED")
        // (No engine code in this image: the engine role — and its setsid(2)
        // downcall — lives in the JVM-hosted engine, shipped as jars by :engine.)
        // Push heavy deps to lazy init. Build-time <clinit> is faster at
        // runtime but blows up .svm_heap with cached objects we may never
        // touch. The crypto/SBOM/git/Jib closures (bouncycastle, sigstore,
        // grpc, cyclonedx, spdx, jgit, com.google) live in forked workers, not
        // on the binary's classpath, so jline is the only contributor left:
        // its FFM Linker/Arena lookups must run at image-runtime regardless.
        buildArgs.add("--initialize-at-run-time=org.jline")
        // jline-native ships a resource-config with a broad "org/jline/nativ/.*"
        // pattern that embeds ALL platform native libs (Windows DLLs, Linux/macOS/
        // FreeBSD .so/.dylib for every arch) as image resources. jk uses the FFM
        // terminal provider exclusively; the JNI/JNA fallback (JLineNativeLoader,
        // CLibrary, Kernel32, etc.) is reachable via jline-terminal's AbstractPty
        // but never exercised at runtime. Exclude those cross-platform binaries
        // with -H:ExcludeResources so they are not baked into the image heap.
        buildArgs.add("-H:ExcludeResources=org/jline/nativ/.*")
    }

}

// JLine 4 FFM terminal provider ships native-image hints; we supplement them
// at src/main/resources/META-INF/native-image/org.jline/jline-terminal-ffm/
// with reflection-config.json and resource-config.json bootstrapped via the
// GraalVM tracing agent against the JVM wizard (see plan §8d).

