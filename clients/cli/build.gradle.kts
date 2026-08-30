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
    // below still sees a runtime classpath with no test code and no JUnit on it (JK-2443).
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

// JK-2139: the native client must not see the plugin SPI jar (codec is :host).
//
// JK-2443 added the test-fixtures entries. `:host` now carries a `testFixtures` source set (the
// tree's shared test primitives) and `:cli` consumes it as `testImplementation`, so the fixtures —
// and JUnit, which they compile against — are one wrong configuration keyword away from the native
// image. That mistake would not fail anything else: the image would just get bigger and start
// reaching reflective JUnit machinery that GraalVM cannot see. This is the arm that notices.
val checkCliRuntimeClasspath by tasks.registering {
    val runtime = configurations.named("runtimeClasspath")
    inputs.files(runtime)
    doLast {
        val files = runtime.get().incoming.artifacts.artifactFiles.files
        // Self-fail: an empty or unrecognisable classpath satisfies every ban below vacuously.
        if (files.none { it.name.startsWith("host") || it.name.startsWith("jk-host") }) {
            throw GradleException(
                    "The CLI runtime classpath guard resolved ${files.size} artifacts and none of them"
                            + " is the :host jar, so it is not looking at the native client's classpath."
                            + " Fix the guard before trusting a green run.")
        }
        val forbidden = files.filter { f ->
            val n = f.name
            n.startsWith("plugin-sdk")
                    || n.startsWith("jk-plugin-sdk")
                    || n.startsWith("maven-artifact")
                    || n.startsWith("plexus-utils")
                    || n.startsWith("jline")
                    || n.contains("-test-fixtures")
                    || n.startsWith("junit-")
                    || n.startsWith("assertj-")
                    || n.startsWith("opentest4j")
                    || n.startsWith("apiguardian")
        }
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                    "CLI runtimeClasspath must not contain plugin-sdk / maven-artifact / plexus-utils,"
                            + " nor any test-fixtures or test-framework jar: " + forbidden)
        }
    }
}

// JK-2151: native reachability — CLI main must not name parser / plugin-schema types.
//
// JK-2489 closed the hole this guard was written for and could not previously state: the ban listed
// twelve type names but not `org.tomlj` itself, because `BuildLogicTaskScan` named `TomlValues` and
// `org.tomlj.TomlTable` to read `[build].logic` — one violation, in the guard's own module, for as
// long as the guard has existed. `TomlScan` reads that key now (see `BuildLogicToml`), so the two
// entries below are enforceable. This is the rule the guard registry reserved as G4 and never
// landed; it belongs here rather than as a new letter, because it is the same reachability control.
//
// Measured 2026-08-24 across 274 files under clients/cli/src/main/java: 0 violations for all
// fourteen entries. `TomlScan` and `MinimalToml` are deliberately absent from the list — they are
// the line scanner and the scalar codec the native image is *supposed* to reach.
val checkCliNoParseTypes by tasks.registering {
    val main = layout.projectDirectory.dir("src/main/java")
    inputs.dir(main)
    doLast {
        val banned = listOf(
            "JkBuildParser",
            "PluginDescriptor",
            "PluginTableRegistry",
            "org.tomlj",
            "TomlValues",
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

// ---------------------------------------------------------------------------
// JK-2453: a test that names the ambient state root must declare it throwaway.
//
// `clients/cli/build/test-jk-home` is ONE JK_HOME shared by all of the tier's parallel forks and by
// every run before this one — no task cleans it, so `state/` is ambient input. A test that resolves
// `JkDirs.state()` and then asserts on what it finds is asserting against the last run, not against
// itself: `EngineAotCommandTest` planted an `engine-<version>-<16hex>.aot` fixture in the shared
// `state/aot`, and a sibling fork's engine-AOT key sweep deleted it mid-assertion — three greens and
// a red from the same bytes, for as long as the tier has existed.
//
// The rule is not "never touch the root", it is "name the root, declare the isolation":
// `@IsolatedState` hands the class a per-method temp root through the `jk.env.*` seam in JkDirs.
// File granularity is deliberate — the annotation is class-level, so a file that mentions the root
// and not the annotation is exactly the violation.
//
// Scope is the STATE root only. The artifact store has a second spelling (`JkStores.store()`) and
// two suites that deliberately prime the shared store rather than isolate it (JK-2451); ratcheting
// that root needs those declared first, so this guard does not pretend to cover it.
// ---------------------------------------------------------------------------
// Guard G31 (JK-2453).
val checkTestRootsDeclared by tasks.registering {
    group = "verification"
    description = "Fail when a :cli test reads the ambient state root without @IsolatedState"
    val testJava = fileTree(layout.projectDirectory.dir("src/test/java")) { include("**/*.java") }
    inputs.files(testJava).withPropertyName("testJava")
    val stamp = layout.buildDirectory.file("guards/test-roots-declared.ok")
    outputs.file(stamp)
    doLast {
        val accessors = listOf("JkDirs.state()", "JkDirs.builds()")
        val hits = testJava.files.sorted().flatMap { f ->
            val text = f.readText()
            if (text.contains("@IsolatedState")) emptyList()
            else accessors.filter { text.contains(it) }.map { "${f.name}: $it" }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                    "The tier's JK_HOME is shared across forks and across runs, so a test that reads the "
                            + "ambient state root inherits state instead of establishing it (JK-2453). "
                            + "Annotate the class with @IsolatedState: " + hits)
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkTestRootsDeclared) }
tasks.named("test") { dependsOn(checkTestRootsDeclared) }

// ---------------------------------------------------------------------------
// G22 — JK-2449: the IDE clients are wired to jk by string, and nothing checked the strings.
//
// `clients/intellij` and `clients/vscode` are not modules of this build (the IntelliJ plugin is a
// standalone Gradle build; the VS Code extension is JavaScript), so no compiler ever looks at
// them. That is how a P0 survived 24 days: commit 45eb5ec9 sed'd `jk.lock` -> `jk-lock.toml`
// tree-wide and rewrote a VS Code *command id* in `package.json`, leaving the palette entry
// pointing at a command `extension.js` never registers. Nothing about that is a compile error —
// it is valid JSON naming a handler that does not exist — so adopting the two clients as modules
// with a `check` would have produced a green gate over the live defect. The gate has to be a
// cross-file wiring check, and it has to be cheap: an IntelliJ plugin build needs a whole IDE
// distribution (`intellijIdeaCommunity("2024.1.7")`, fetched from JetBrains' own repositories,
// which this build's `dependencyResolutionManagement` does not carry) and a VS Code extension
// needs a Node toolchain. `checkAll` now depends on every module's `check`, so adopting either
// would put a large third-party download on the critical path of every gate — a worse trade than
// no check, and the reason `checkNoBareManifestName` already records `clients/intellij` as
// deliberately out of this build.
//
// So the clients are gated from here — `:cli` already owns `IdeCommand`, the IDE generators, BSP,
// and the `IdeWireModel` they parse — with five arms, all reading their expectations from the
// owning file rather than restating them:
//
//   1. VS Code command ids, BOTH directions: `contributes.commands[].command` in package.json is
//      exactly the set registered by `vscode.commands.registerCommand` in extension.js. One
//      direction alone is half a guard — a registered command missing from the palette is
//      unreachable in exactly the same way as a palette entry with no handler.
//   2. Every jk verb either client shells out to exists, checked against the names and aliases
//      declared by the `:cli` command classes themselves.
//   3. Every implementation class `plugin.xml` names under `cc.jumpkick.idea` has a source file.
//   4. Every wire field `JkWireModel` reads is emitted by `IdeWireModel.encode()`.
//   5. The IntelliJ plugin declares no `untilBuild` upper bound. A pin here is a time bomb on
//      JetBrains' cadence: "252.*" made the plugin refuse to install on IDEA 2025.3 (build 253).
//
// Measured 2026-08-24 against 6 VS Code command ids (6 registered handlers), 6 shelled verbs
// (bsp/build/ide/lock/sync/test) resolved against 110 owner-declared command names and aliases,
// 7 plugin.xml implementation classes and 10 wire fields — 0 violations after the `jk.lock` and
// `untilBuild` fixes in this change, 2 before. Each arm also fails loudly if its own scan comes
// back empty (or, for the verb owner, implausibly small), so a shape change cannot quietly turn
// an arm into a green no-op.
// ---------------------------------------------------------------------------
val checkIdeClientWiring by tasks.registering {
    group = "verification"
    description = "Fail when clients/intellij or clients/vscode names a jk verb, command id, class or wire field that does not exist"
    val vscodeManifest = rootProject.file("clients/vscode/package.json")
    val vscodeSource = rootProject.file("clients/vscode/extension.js")
    val ideaPluginXml = rootProject.file("clients/intellij/src/main/resources/META-INF/plugin.xml")
    val ideaBuildScript = rootProject.file("clients/intellij/build.gradle.kts")
    val ideaJava = rootProject.file("clients/intellij/src/main/java")
    val wireModel = rootProject.file("shared/wire/src/main/java/cc/jumpkick/engine/protocol/IdeWireModel.java")
    val cliCommands = fileTree(layout.projectDirectory.dir("src/main/java/cc/jumpkick/command")) {
        include("**/*Command.java")
    }
    inputs.file(vscodeManifest).withPropertyName("vscodeManifest")
    inputs.file(vscodeSource).withPropertyName("vscodeSource")
    inputs.file(ideaPluginXml).withPropertyName("ideaPluginXml")
    inputs.file(ideaBuildScript).withPropertyName("ideaBuildScript")
    inputs.dir(ideaJava).withPropertyName("ideaJava")
    inputs.file(wireModel).withPropertyName("wireModel")
    inputs.files(cliCommands).withPropertyName("cliCommands")
    val stamp = layout.buildDirectory.file("guards/ide-client-wiring.ok")
    outputs.file(stamp)
    doLast {
        val problems = mutableListOf<String>()

        // --- 1. VS Code palette entries and registered handlers are the same set --------------
        val manifestText = vscodeManifest.readText()
        val extensionText = vscodeSource.readText()
        val declared = Regex("\"command\"\\s*:\\s*\"([^\"]+)\"").findAll(manifestText)
            .map { it.groupValues[1] }.toSortedSet()
        val registered = Regex("registerCommand\\(\\s*\"([^\"]+)\"").findAll(extensionText)
            .map { it.groupValues[1] }.toSortedSet()
        (declared - registered).forEach {
            problems += "package.json contributes command '$it' that extension.js never registers"
        }
        (registered - declared).forEach {
            problems += "extension.js registers command '$it' that package.json never contributes"
        }

        // --- 2. Every shelled-out verb is a real jk command (names read from the owners) -------
        val verbs = sortedSetOf<String>()
        cliCommands.files.forEach { f ->
            val text = f.readText()
            Regex("String name\\(\\)\\s*\\{\\s*return \"([a-z][a-z0-9-]*)\";").findAll(text)
                .forEach { verbs += it.groupValues[1] }
            Regex("List<String> aliases\\(\\)\\s*\\{[^}]*?return List\\.of\\(([^)]*)\\);", RegexOption.DOT_MATCHES_ALL)
                .findAll(text)
                .forEach { m ->
                    Regex("\"([a-z][a-z0-9-]*)\"").findAll(m.groupValues[1]).forEach { verbs += it.groupValues[1] }
                }
        }
        if (verbs.size < 40) {
            problems += "verb owner scan found only ${verbs.size} jk commands under src/main/java/cc/jumpkick/command" +
                    " — the name()/aliases() shape moved and arm 2 is blind"
        }
        val shelled = sortedSetOf<String>()
        Regex("runJk\\(\\[\\s*\"([a-z][a-z0-9-]*)\"").findAll(extensionText)
            .forEach { shelled += it.groupValues[1] }
        val jkCliAction = File(ideaJava, "cc/jumpkick/idea/JkCliAction.java")
        val jkSyncService = File(ideaJava, "cc/jumpkick/idea/JkSyncService.java")
        Regex("super\\(\\s*\"[^\"]*\"\\s*,\\s*\"([a-z][a-z0-9-]*)\"").findAll(jkCliAction.readText())
            .forEach { shelled += it.groupValues[1] }
        Regex("JkCliRunner\\.run\\([^,]+,\\s*List\\.of\\(\"([a-z][a-z0-9-]*)\"").findAll(jkSyncService.readText())
            .forEach { shelled += it.groupValues[1] }
        if (shelled.isEmpty()) {
            problems += "found no jk verbs invoked by either IDE client — the call shape moved and arm 2 is blind"
        }
        (shelled - verbs).forEach {
            problems += "an IDE client shells out to 'jk $it', which is not a name or alias of any :cli command"
        }

        // --- 3. plugin.xml names classes that exist -------------------------------------------
        val xml = ideaPluginXml.readText()
        val ideaClasses = Regex("(?:class|implementation)=\"(cc\\.jumpkick\\.idea\\.[A-Za-z0-9_.$]+)\"")
            .findAll(xml).map { it.groupValues[1] }.toSortedSet()
        if (ideaClasses.isEmpty()) {
            problems += "plugin.xml declares no cc.jumpkick.idea implementation class — arm 3 is blind"
        }
        ideaClasses.forEach { fqcn ->
            // Inner classes (JkCliAction$Sync) live in the outer class's file.
            val outer = fqcn.substringBefore('$')
            val source = File(ideaJava, outer.replace('.', '/') + ".java")
            if (!source.isFile) {
                problems += "plugin.xml names $fqcn but ${source.name} does not exist"
            } else if (fqcn.contains('$')) {
                val inner = fqcn.substringAfter('$')
                if (!source.readText().contains("class $inner")) {
                    problems += "plugin.xml names $fqcn but ${source.name} declares no class $inner"
                }
            }
        }

        // --- 4. Every wire field the plugin reads is one the engine emits ----------------------
        val jkWireModel = File(ideaJava, "cc/jumpkick/idea/JkWireModel.java").readText()
        val read = sortedSetOf<String>()
        Regex("str(?:Field|Array)\\(\\s*(?:body|json)\\s*,\\s*\"([A-Za-z][A-Za-z0-9]*)\"")
            .findAll(jkWireModel).forEach { read += it.groupValues[1] }
        // `workspace` is sniffed as a raw \"key\": literal rather than through the field helpers.
        Regex("\\\\\"([A-Za-z][A-Za-z0-9]*)\\\\\"\\s*:").findAll(jkWireModel)
            .forEach { read += it.groupValues[1] }
        if (read.isEmpty()) {
            problems += "JkWireModel reads no recognised wire field — the parse shape moved and arm 4 is blind"
        }
        val emitted = wireModel.readText()
        read.forEach { field ->
            if (!emitted.contains("\\\"$field\\\":")) {
                problems += "JkWireModel reads wire field '$field' that IdeWireModel.encode() does not emit"
            }
        }

        // --- 5. No untilBuild upper bound on the IntelliJ plugin -------------------------------
        val ideaBuild = ideaBuildScript.readText()
        Regex("untilBuild\\s*(?:=|\\.set\\()\\s*\"([^\"]+)\"").find(ideaBuild)?.let {
            problems += "clients/intellij pins untilBuild to \"${it.groupValues[1]}\" — a pinned upper bound makes" +
                    " the plugin uninstallable the moment JetBrains ships the next build line; unset it with" +
                    " `untilBuild = provider { null }`"
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                    "The IDE clients are not compiled by this build, so their wiring to jk is only ever checked"
                            + " here (JK-2449):\n        " + problems.sorted().joinToString("\n        "))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkIdeClientWiring) }

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
// loopback TCP (JK-1065). The old `length <= 60` gate here was a budget against `sun_path` that
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
// real repo's jk.toml (JK-2329). That is the whole requirement now; path length is not part of it.
val cliTestTmpDirShort =
        shortTmpRoot.resolve(
                "jk-cli-tmp-${System.currentTimeMillis().toString(36)}-${(System.identityHashCode(project) and 0xffff).toString(16)}")

// Sandbox cleanup must run when the tier FAILS too — doLast is skipped on failure, and failed
// runs are exactly the ones that leave the most litter under the tmp root.
val cleanCliTestSandboxes by tasks.registering {
    doLast {
        cliTestStateDirShort.deleteRecursively()
        cliTestTmpDirShort.deleteRecursively()
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
    // no coverage (JK-1970).
    environment("JK_NERD_FONT", "false")
    // EngineTestExtension (materialize + stop-after-every-class) stays unloaded here: the unit
    // tier must not spawn engines. That is the conventions default for every tier since JK-2447,
    // so this tier states nothing; `integrationTest` below is the one that overrides it.
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
            ":android:stageWorkerRepo")
    environment("TERM", "xterm-256color")
    environment("CI", "false")
    environment("NO_COLOR", "")
    // Loopback TCP, on every platform, for the one tier that spawns real engines. Two reasons,
    // and the second is the bigger one:
    //   * a TCP port has no `sun_path` budget, so the sandbox root's length stops being load-
    //     bearing — a macOS per-user $TMPDIR composed a 103-byte socket path against the JDK's
    //     102-byte limit and every engine-spawning test in this tier failed to bind (JK-1065);
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
        val store = file("$testJkHome/data/store") // JK_HOME mirrors XDG: store is <data>/store
        listOf(
                        ":kotlin-compiler",
                        ":groovy-compiler",
                        ":java-compiler",
                        ":test-runner",
                        ":auditor",
                        ":publisher",
                        ":image-builder",
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


// ---------------------------------------------------------------------------
// Guard G27 (JK-2432): the terminal is handed to a child in exactly one place.
//
// `CliOutput.handOffTerminal(pb)` restores the terminal out of whatever mode jk put it in and then
// starts the child on inherited stdio. Those two steps are a pair, and spelling them separately is
// how three of the ten handoff sites came to skip the restore: `jk gradle`, `jk mvn` and `jk self
// update`'s engine takeover called `inheritIO().start()` with jk's raw mode still installed, so an
// interactive child's own line editing did not work. `Interactivity.restoreForChildProcess` was a
// second public wrapper for the restore half with zero production callers and one test asserting it
// does not throw; it is gone.
//
// The pattern is `inheritIO`, not `new ProcessBuilder`: this module forks plenty of children that
// must NOT inherit stdio (the engine daemon spawn redirects to files, `jk bsp serve` keeps stdout
// for its own JSON-RPC frames — see IdeEngineClient). Inheriting is the thing that needs the pair.
//
// Measured 2026-08-25: one occurrence across 285 files under src/main/java, inside the owner.
// No allowlist. Self-failing: the owner must still contain it, or the guard is pointing at nothing.
// ---------------------------------------------------------------------------
/** [src] with comments blanked (newlines kept) so a scan matches code, not prose about the code. */
fun handoffGuardCode(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
            src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2)
                val stop = if (end < 0) src.length else end + 2
                while (i < stop) { out.append(if (src[i] == '\n') '\n' else ' '); i++ }
            }
            else -> { out.append(src[i]); i++ }
        }
    }
    return out.toString()
}

val checkOneTerminalHandoff by tasks.registering {
    group = "verification"
    description = "Fail when a CLI command inherits stdio outside CliOutput.handOffTerminal"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    val ownerName = "CliOutput.java"
    inputs.files(mainJava).withPropertyName("mainJava")
    val stamp = layout.buildDirectory.file("guards/one-terminal-handoff.ok")
    outputs.file(stamp)
    doLast {
        val files = mainJava.files.sorted()
        if (files.size < 200) {
            throw GradleException("The terminal-handoff guard scanned ${files.size} files under"
                    + " src/main/java; it was measured against 285. The include pattern has stopped"
                    + " seeing the module — fix it before trusting a green run.")
        }
        val owner = files.singleOrNull { it.name == ownerName }
                ?: throw GradleException("$ownerName is not under src/main/java any more, so this guard"
                        + " has lost the owner it exempts. Point it at the new owner or retire it.")
        if (!handoffGuardCode(owner.readText()).contains("inheritIO()")) {
            throw GradleException("$ownerName no longer calls inheritIO(), so handOffTerminal has stopped"
                    + " being the handoff and this guard is exempting a file that does nothing.")
        }
        val hits = files.filter { it != owner && handoffGuardCode(it.readText()).contains("inheritIO(") }
                .map { "  ${it.name}" }
        if (hits.isNotEmpty()) {
            throw GradleException("Handing this terminal to a child means restoring it out of jk's mode"
                    + " first, and that pair has one owner: CliOutput.handOffTerminal(pb). These files"
                    + " inherit stdio themselves:\n" + hits.joinToString("\n")
                    + "\n  A child that inherits jk's raw mode gets no line editing of its own.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkOneTerminalHandoff) }
tasks.named("jar") { dependsOn(checkOneTerminalHandoff) }
