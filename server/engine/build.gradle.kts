// SPDX-License-Identifier: Apache-2.0
// Absorbs :engine and :runtime (Phase 5 module reorg).

plugins {
    id("jk.java-conventions")
    application
    id("com.gradleup.shadow") version "9.2.2"
}

// Must match cc.jumpkick.model.JkVersion.VERSION: the client only spawns an engine jar whose
// filename version equals its own baked-in version.
version = "0.13.0"

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
dependencies { testApksig("com.android.tools.build:apksig:8.7.3") }

// networkTest and slowTest get the same wiring: the shipped-template scaffold-and-build tests and
// the framework e2e suites fork the same plugin workers, and a nightly run of either builds nothing
// else first — without the dependsOn the jars are simply absent there and the tier skips its way
// green. slowTest joined the list when @Tag("slow") moved off the gate; AndroidSpikeTest
// does not skip, it asserts the property is non-blank, so the tier failed on a missing jar rather
// than quietly covering nothing.
listOf("integrationTest", "networkTest", "slowTest").forEach { tier ->
    tasks.named<Test>(tier) {
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
}

// ---------------------------------------------------------------------------
// Guard G14: a forecast key must hash the same facts as the build key.
//
// `jk explain` re-derives every cache key the build computes. When the two derivations disagree the
// forecast either reports a phantom rebuild or blesses a stale artifact.
//
// Three arms, all plain text scans over this module's `src/main/java`:
//
//   A. `ActionKey.forArtifact` token bags. Every site in the module must appear below in exactly
//      one of three tables, so a new key cannot be added without deciding how it is forecast:
//
//        - `forArtifactPairs`   two bodies, compared. Whole keys legitimately differ (the two sides
//                               run at different times over different inputs); the set of
//                               `"<prefix>:"` literals may not, because a prefix on one side only
//                               means one side hashes a fact the other ignores.
//        - `forArtifactShared`  ONE body, called by the build and by the forecast. Prefix-set
//                               equality cannot see a value drift behind an agreed prefix, and
//                               values legitimately differ per module, so the guard checks that the
//                               owner really has both callers. A "shared" key with one caller is
//                               an unpaired key wearing the word.
//        - `forArtifactUnpaired` no forecast key, with the reason — AND whether the forecast emits a
//                               *step* for that task at all. Those are different claims. The flag
//                               is checked against what TaskForecaster actually constructs.
//
//   B. `CompileRequest` builder chains, restricted to the fields `ActionKey.forJavac` actually
//      reads — including `javaHome`, which it hashes. The scan follows the WHOLE chain: the fluent
//      primary chain plus every later statement on the same builder variable, up to its `build()`.
//      Both keyed build sites carry a conditional Scala continuation; the forecast must hash those
//      fields too.
//
//   C. `compileRequestShared` — the compile-main request has one body (`PlannerCompile
//      .mainCompileRequest`) that the build step and the forecast both call, for the same reason
//      `forArtifactShared` exists: its classpath, its javac options and its Scala fields are
//      derived values, and arm B can only see which fields are set, not what is in them.
// ---------------------------------------------------------------------------

// A build site and its forecast twin, addressed as `<file>|<key variable>`: every bag site is
// written `String <var> = ActionKey.forArtifact(...)` and the variable is unique within its file.
val forArtifactPairs = listOf(
        "package-jar" to ("PlannerPackage.java|pkgKey" to "TaskForecaster.java|pkgKey"))

// One body, both callers. Value: the label, and the `<file>|<literal>` reach points that prove each
// side goes through the owner rather than round it.
val forArtifactShared = mapOf(
        "PackagingKeys.java|asmKey" to ("package-assembly" to listOf(
                "PlannerTails.java|PackagingKeys.assembly(",
                "TaskForecaster.java|PackagingKeys.assemblyActionCached(")),
        "PackagingKeys.java|pkgKey" to ("plugin packager" to listOf(
                "PlannerPlugin.java|PackagingKeys.pluginPackager(",
                "TaskForecaster.java|PackagingKeys.pluginPackagerStep(")))

// forArtifact sites with no forecast twin. Triple(task name, does the forecast emit a step for that
// task, why there is nothing to compare). The boolean is checked against TaskForecaster so an
// unpaired key cannot claim "not forecast" for a task the forecast does step.
val forArtifactUnpaired = mapOf(
        "PlannerTails.java|key" to Triple(
                "package-sources", false, "explain does not forecast the sources jar at all"),
        "PlannerNative.java|nKey" to Triple(
                "native-image", true, "the forecast probes the task pointer, not a token bag"),
        "PlannerPlugin.java|actionKey" to Triple(
                "plugin-<step>", false, "plugin steps are not forecast (no step, no key)"),
        "ImageWrite.java|imgKey" to Triple(
                "write-image",
                true,
                "the image tail is an unconditional side-effect step — always RUN, never keyed"),
        "BuildLogicSupport.java|key" to Triple(
                "build-logic", false, "build-logic compile is not forecast"))

// `compileStep` is the one forecast helper that names its step from a parameter; both call sites
// pass a literal, and those literals are scanned. Any OTHER unresolvable step name is a step the
// scan cannot see — the exact blind spot arm A3 exists to close — so it fails the build.
val forecastStepIndirections = setOf("String name", "name")

// A keyed CompileRequest chain, addressed as `<file>|<marker>`. The marker must occur exactly once
// in its file; the chain is the `CompileRequest.builder()` nearest to it, followed to its build().
val compileRequestPairs = listOf(
        "compile-test" to
                ("TestSupport.java|qualifiedTaskId(taskId, outputDir)"
                        to "TaskForecaster.java|TaskNames.COMPILE_TEST, testOut)"))

// A CompileRequest with ONE body that both sides call: `<owner file>|<owner marker>` to the
// `<file>|<literal>` reach points.
val compileRequestShared = mapOf(
        "compile-main" to ("PlannerCompile.java|public static CompileRequest mainCompileRequest(" to listOf(
                "PlannerCompile.java|mainCompileRequest(new MainCompile(",
                "TaskForecaster.java|PlannerCompile.mainCompileRequest(")),
        "compile-test-fixtures" to ("PlannerFixtures.java|public static CompileRequest fixturesCompileRequest(" to listOf(
                "PlannerFixtures.java|CompileRequest request = fixturesCompileRequest(",
                "PlannerFixtures.java|CompileRequest fxReq = fixturesCompileRequest(")))

// Every `CompileRequest.builder()` site in the module and how many times it appears, so a new chain
// has to be declared as keyed (above) or unkeyed (here) before the build will run.
val compileRequestSites = mapOf(
        "PlannerCompile.java" to 1, // shared: the one compile-main body, build + forecast
        "PlannerFixtures.java" to 1, // shared: compile-test-fixtures, build + forecast
        "TestSupport.java" to 1, // keyed: compile-test build
        "TaskForecaster.java" to 1, // keyed: compile-test forecast
        "LocalProjectBuilder.java" to 1, // unkeyed: source-dependency build calls JavacRunner directly
        "ScriptPlans.java" to 1) // unkeyed: jk run <script> calls JavacRunner directly

val checkForecastKeyParity = registerGuard("checkForecastKeyParity") {
    group = "verification"
    description = "Fail the build when a forecast key hashes a different fact set than the build key"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    // TaskNames lives in :jk-api. Arm A3 resolves a `TaskNames.X` step name to the string the
    // forecast really emits rather than guessing it from the constant's spelling.
    val taskNamesFile =
            project(":jk-api").layout.projectDirectory.file("src/main/java/cc/jumpkick/run/TaskNames.java")
    inputs.file(taskNamesFile).withPropertyName("taskNames")
    val pairs = forArtifactPairs
    val shared = forArtifactShared
    val unpaired = forArtifactUnpaired
    val indirections = forecastStepIndirections
    val requestPairs = compileRequestPairs
    val requestShared = compileRequestShared
    val requestSites = compileRequestSites
    // The declarations above are inputs too: editing a table without touching a source file still
    // has to re-run the check, or the ratchet can be loosened by an up-to-date task.
    inputs.property(
            "declarations",
            listOf(pairs, shared, unpaired, indirections, requestPairs, requestShared, requestSites).toString())
    val stamp = layout.buildDirectory.file("guards/forecast-key-parity.ok")
    outputs.file(stamp)
    doLast {
        val taskNamesSource = taskNamesFile.asFile.readText()
        val sources = mainJava.files.sorted()
        val byName = sources.groupBy { it.name }

        // Comments are blanked (to spaces, so every offset below still lines up) before any scan
        // runs. The scanners honour string and char literals, and an apostrophe in English prose —
        // "forKotlinc's jdk: token" — reads as a char literal that never closes, which made the
        // brace walk report an unbalanced method. A probe's answer is bounded by what the probe can
        // see, and a comment is not code.
        fun stripComments(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            var inStr = false
            var inChar = false
            var esc = false
            while (i < text.length) {
                val c = text[i]
                val next = if (i + 1 < text.length) text[i + 1] else ' '
                if (esc) {
                    esc = false
                    out.append(c)
                } else if (inStr || inChar) {
                    if (c == '\\') esc = true
                    else if (inStr && c == '"') inStr = false
                    else if (inChar && c == '\'') inChar = false
                    out.append(c)
                } else if (c == '/' && next == '/') {
                    while (i < text.length && text[i] != '\n') {
                        out.append(' ')
                        i++
                    }
                    continue
                } else if (c == '/' && next == '*') {
                    while (i < text.length && !(text[i] == '*' && i + 1 < text.length && text[i + 1] == '/')) {
                        out.append(if (text[i] == '\n') '\n' else ' ')
                        i++
                    }
                    out.append("  ")
                    i += 2
                    continue
                } else {
                    if (c == '"') inStr = true
                    if (c == '\'') inChar = true
                    out.append(c)
                }
                i++
            }
            return out.toString()
        }

        fun read(file: String): String {
            val hits = byName[file] ?: throw GradleException("G14: no $file under src/main/java")
            if (hits.size != 1) throw GradleException("G14: $file is ambiguous: $hits")
            return stripComments(hits[0].readText())
        }

        // --- scanners --------------------------------------------------------
        // Java is not parsed here; these walk characters while honouring string and char literals,
        // which is all these four shapes need and all a build script should attempt.
        fun balancedFrom(text: String, open: Char, close: Char, at: Int): String {
            var depth = 0
            var i = at
            var inStr = false
            var inChar = false
            var esc = false
            while (i < text.length) {
                val c = text[i]
                if (esc) {
                    esc = false
                } else if (inStr || inChar) {
                    if (c == '\\') esc = true else if (inStr && c == '"') inStr = false
                    else if (inChar && c == '\'') inChar = false
                } else when (c) {
                    '"' -> inStr = true
                    '\'' -> inChar = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return text.substring(at + 1, i)
                    }
                }
                i++
            }
            throw GradleException("G14: unbalanced '$open' at offset $at")
        }

        fun splitTopLevel(args: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var depth = 0
            var inStr = false
            var inChar = false
            var esc = false
            for (c in args) {
                if (esc) {
                    esc = false
                } else if (inStr || inChar) {
                    if (c == '\\') esc = true else if (inStr && c == '"') inStr = false
                    else if (inChar && c == '\'') inChar = false
                } else when (c) {
                    '"' -> inStr = true
                    '\'' -> inChar = true
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    ',' -> if (depth == 0) {
                        out.add(sb.toString())
                        sb.setLength(0)
                        continue
                    }
                }
                sb.append(c)
            }
            if (sb.isNotBlank()) out.add(sb.toString())
            return out
        }

        // --- arm A: forArtifact token-prefix parity ---------------------------
        val bagDecl = "List<String> tokens = List.of("
        fun tokenPrefixes(siteId: String): Set<String> {
            val file = siteId.substringBefore('|')
            val keyVar = siteId.substringAfter('|')
            val src = read(file)
            val call = "String $keyVar = ActionKey.forArtifact("
            val callAt = src.indexOf(call)
            if (callAt < 0) {
                throw GradleException("G14: declared site $siteId no longer exists. Update the pair"
                        + " table in server/engine/build.gradle.kts so it still names the real code.")
            }
            val bagAt = src.lastIndexOf(bagDecl, callAt)
            if (bagAt < 0) {
                throw GradleException("G14: $siteId no longer keys a `$bagDecl…)` bag. A bag this"
                        + " guard cannot read is a bag it cannot compare — give it back that shape,"
                        + " or move the pair onto a shared key owner and delete it from the table.")
            }
            val between = src.substring(bagAt, callAt)
            if (between.contains("tokens.add") || between.contains("ActionKey.forArtifact(")) {
                throw GradleException("G14: $siteId's token bag is amended between its `List.of` and"
                        + " its key, so the literal list is not the whole fact set. Same remedy as"
                        + " above: one readable bag, or one shared key owner.")
            }
            return splitTopLevel(balancedFrom(src, '(', ')', bagAt + bagDecl.length - 1))
                    .mapIndexed { i, raw ->
                        val element = raw.lines()
                                .filterNot { it.trim().startsWith("//") }
                                .joinToString("\n")
                                .trim()
                        val prefix = Regex("^\"([A-Za-z][A-Za-z0-9_.-]*):").find(element)
                                ?: throw GradleException("G14: $siteId token ${i + 1} does not open"
                                        + " with a \"prefix:\" literal, so no side can be compared"
                                        + " against it: $element")
                        prefix.groupValues[1]
                    }
                    .toSet()
        }

        val problems = mutableListOf<String>()

        val siteRegex = Regex("""(?:String\s+)?(\w+)\s*=\s*ActionKey\.forArtifact\(""")
        val foundSites = sources.flatMap { f ->
            siteRegex.findAll(f.readText()).map { "${f.name}|${it.groupValues[1]}" }
        }.toSortedSet()
        val declaredSites =
                (pairs.flatMap { listOf(it.second.first, it.second.second) } + shared.keys + unpaired.keys)
                        .toSortedSet()
        if (foundSites != declaredSites) {
            problems.add("Every ActionKey.forArtifact site must be declared in"
                    + " server/engine/build.gradle.kts, as half of a build/forecast pair, as a"
                    + " shared owner both sides call, or as unpaired with the reason there is no"
                    + " twin. An undeclared site is a key nobody has decided how to forecast.\n"
                    + "  undeclared: ${(foundSites - declaredSites).ifEmpty { "none" }}\n"
                    + "  declared but gone: ${(declaredSites - foundSites).ifEmpty { "none" }}")
        } else {
            pairs.forEach { (label, sites) ->
                val build = tokenPrefixes(sites.first)
                val forecast = tokenPrefixes(sites.second)
                if (build != forecast) {
                    problems.add("$label: the build and the forecast hash different facts, so"
                            + " `jk explain` reports a phantom rebuild (or worse, misses a real"
                            + " one).\n"
                            + "  build    ${sites.first}: ${build.sorted()}\n"
                            + "  forecast ${sites.second}: ${forecast.sorted()}\n"
                            + "  only in the build:    ${(build - forecast).sorted()}\n"
                            + "  only in the forecast: ${(forecast - build).sorted()}")
                }
            }
        }

        // --- arm A2: a shared key owner must really have both callers ----------
        // Reach points are literal, so a caller that forks the derivation instead of calling it
        // either shows up here (the literal is gone) or lands as a new undeclared forArtifact site.
        fun reaches(reach: String, label: String, kind: String) {
            val file = reach.substringBefore('|')
            val literal = reach.substringAfter('|')
            if (!read(file).contains(literal)) {
                problems.add("$label is declared a shared $kind owner, but $file no longer reaches it"
                        + " (`$literal` is gone). A shared owner with one caller is a second body"
                        + " waiting to drift: either restore the call, or declare the two sites"
                        + " explicitly so this guard compares them instead.")
            }
        }
        shared.forEach { (site, spec) ->
            val (label, reachPoints) = spec
            if (reachPoints.size < 2) {
                problems.add("$label ($site) is declared shared but names ${reachPoints.size} caller;"
                        + " shared means the build AND the forecast go through one body.")
            }
            reachPoints.forEach { reaches(it, "$label ($site)", "key") }
        }

        // --- arm A3: an unpaired key must be honest about the forecast STEP -----
        // The exemption that hid it said "not forecast", which was true of the key and false
        // of the step: explain emitted package-jar for every plugin-packaged module and keyed it
        // against the plain jar. So the claim is checked against what TaskForecaster constructs.
        val forecaster = read("TaskForecaster.java")
        val stepArgs = (Regex("""new TaskForecast\.Task\(\s*([^,]+),""").findAll(forecaster)
                + Regex("""\bcompileStep\(\s*([^,]+),""").findAll(forecaster))
                .map { it.groupValues[1].trim().replace(Regex("""\s+"""), " ") }
                .toList()
                .toSortedSet()
        val unresolved = stepArgs.filterNot {
            it.startsWith("\"") || it.startsWith("TaskNames.") || indirections.contains(it)
        }
        if (unresolved.isNotEmpty()) {
            problems.add("TaskForecaster names a step through something this scan cannot resolve:"
                    + " $unresolved. A step the scan cannot see is a step an unpaired key can hide"
                    + " behind — give it a literal or a TaskNames constant, or declare the"
                    + " indirection in forecastStepIndirections and say why it is safe.")
        }
        val taskNames = Regex("""String\s+([A-Z][A-Z_0-9]*)\s*=\s*"([^"]+)"""")
                .findAll(taskNamesSource)
                .associate { it.groupValues[1] to it.groupValues[2] }
        val forecastSteps = stepArgs.mapNotNull { arg ->
            when {
                arg.startsWith("\"") -> arg.trim('"')
                arg.startsWith("TaskNames.") -> taskNames[arg.removePrefix("TaskNames.")]
                        ?: throw GradleException("G14: TaskNames has no constant $arg")
                else -> null
            }
        }.toSortedSet()
        unpaired.forEach { (site, spec) ->
            val (taskName, claimsStep, why) = spec
            val stepped = forecastSteps.contains(taskName)
            if (stepped != claimsStep) {
                problems.add(if (stepped)
                    "$site is exempt as \"$why\", but TaskForecaster DOES emit a `$taskName` step —"
                            + " so explain shows that step priced against no key of its own, or"
                            + " against another step's. Pair the site, move it onto a shared owner,"
                            + " or (if the step is genuinely keyless) set the flag to true and say"
                            + " what decides it instead."
                else
                    "$site claims the forecast emits a `$taskName` step, and it does not. Set the"
                            + " flag to false and state that there is no step, so the next reader"
                            + " is not told a forecast exists that does not.")
            }
        }

        // --- arm B: CompileRequest chains vs what forJavac reads ---------------
        val actionKey = read("ActionKey.java")
        val forJavacAt = actionKey.indexOf("public static String forJavac(")
        if (forJavacAt < 0) {
            throw GradleException("G14: ActionKey.forJavac is gone; arm B has nothing to key off.")
        }
        val forJavacBody = balancedFrom(actionKey, '{', '}', actionKey.indexOf('{', forJavacAt))
        val keyedFields = Regex("""request\.([a-zA-Z][A-Za-z0-9]*)\(\)""")
                .findAll(forJavacBody)
                .map { it.groupValues[1] }
                .toSortedSet()
        if (keyedFields.isEmpty()) {
            throw GradleException("G14: forJavac reads no CompileRequest field; the scan has rotted.")
        }

        fun keyedSetters(siteId: String): Set<String> {
            val file = siteId.substringBefore('|')
            val marker = siteId.substringAfter('|')
            val src = read(file)
            val markAt = src.indexOf(marker)
            if (markAt < 0 || src.indexOf(marker, markAt + 1) >= 0) {
                throw GradleException("G14: `$marker` must occur exactly once in $file to address a"
                        + " CompileRequest chain; it now occurs ${Regex(Regex.escape(marker))
                        .findAll(src).count()} times. Pick a new marker.")
            }
            val builders = Regex("""CompileRequest\.builder\(\)""").findAll(src).map { it.range.first }.toList()
            val start = builders.minByOrNull { Math.abs(it - markAt) }
                    ?: throw GradleException("G14: no CompileRequest.builder() in $file")
            var i = start
            var depth = 0
            var inStr = false
            var inChar = false
            var esc = false
            val setters = sortedSetOf<String>()
            while (i < src.length) {
                val c = src[i]
                if (esc) {
                    esc = false
                } else if (inStr || inChar) {
                    if (c == '\\') esc = true else if (inStr && c == '"') inStr = false
                    else if (inChar && c == '\'') inChar = false
                } else when (c) {
                    '"' -> inStr = true
                    '\'' -> inChar = true
                    '(' -> depth++
                    ')' -> depth--
                    ';' -> if (depth == 0) break
                    '.' -> if (depth == 0) {
                        Regex("""^\.([a-zA-Z][A-Za-z0-9]*)\s*\(""").find(src.substring(i, minOf(src.length, i + 64)))
                                ?.let { setters.add(it.groupValues[1]) }
                    }
                }
                i++
            }
            // Past the primary chain. The fluent chain ends at the first `;`, but a builder held in
            // a local keeps taking setters afterwards — both keyed build sites add the Scala fields
            // in a following `if`, and stopping at the `;` is why the guard could not see the
            // forecast setting none of them. Follow the variable to its build().
            if (!setters.contains("build")) {
                val assignedTo = Regex("""([A-Za-z_][A-Za-z0-9_]*)\s*=\s*$""")
                        .find(src.substring(maxOf(0, start - 200), start))
                        ?.groupValues?.get(1)
                        ?: throw GradleException("G14: the CompileRequest chain in $file is not"
                                + " terminated by .build() and is not assigned to a variable, so"
                                + " this scan cannot tell where it ends. Chain it, or assign it.")
                val rest = src.substring(i)
                val buildAt = Regex("""\b${Regex.escape(assignedTo)}\s*\.\s*build\s*\(""").find(rest)
                        ?: throw GradleException("G14: `$assignedTo` in $file never reaches .build();"
                                + " the continuation scan has no end and would read the rest of the"
                                + " file as part of the chain.")
                // Each `<var>.` statement is itself a fluent chain
                // (`req.scalaVersion(..).compilerClasspath(..);`), so the same character walk runs
                // from each one — it is what makes the tail visible, not just the first setter.
                val tail = rest.substring(0, buildAt.range.first)
                val contStarts = Regex("""\b${Regex.escape(assignedTo)}\s*\.""").findAll(tail)
                        .map { it.range.last }.toList()
                for (cs in contStarts) {
                    var j = cs
                    var d = 0
                    var str = false
                    var chr = false
                    var e2 = false
                    while (j < tail.length) {
                        val c = tail[j]
                        if (e2) {
                            e2 = false
                        } else if (str || chr) {
                            if (c == '\\') e2 = true else if (str && c == '"') str = false
                            else if (chr && c == '\'') chr = false
                        } else when (c) {
                            '"' -> str = true
                            '\'' -> chr = true
                            '(' -> d++
                            ')' -> d--
                            ';' -> if (d == 0) break
                            '.' -> if (d == 0) {
                                Regex("""^\.([a-zA-Z][A-Za-z0-9]*)\s*\(""")
                                        .find(tail.substring(j, minOf(tail.length, j + 64)))
                                        ?.let { setters.add(it.groupValues[1]) }
                            }
                        }
                        j++
                    }
                }
            }
            setters.removeAll(setOf("builder", "build"))
            return setters
        }

        val builderCounts = sources.mapNotNull { f ->
            val n = Regex("""CompileRequest\.builder\(\)""").findAll(f.readText()).count()
            if (n == 0) null else f.name to n
        }.toMap()
        if (builderCounts != requestSites) {
            problems.add("Every CompileRequest.builder() chain must be declared in"
                    + " server/engine/build.gradle.kts, so a new one is classified as keyed (it"
                    + " reaches ActionKey.forJavac and needs a forecast twin) or unkeyed (it does"
                    + " not) before it can drift.\n"
                    + "  found:    ${builderCounts.toSortedMap()}\n"
                    + "  declared: ${requestSites.toSortedMap()}")
        } else {
            requestPairs.forEach { (label, sites) ->
                val build = keyedSetters(sites.first).intersect(keyedFields)
                val forecast = keyedSetters(sites.second).intersect(keyedFields)
                if (build != forecast) {
                    problems.add("$label: the build's CompileRequest and the forecast's set"
                            + " different fields that ActionKey.forJavac hashes, so the two keys"
                            + " cannot match. forJavac reads: ${keyedFields.toList()}\n"
                            + "  build    ${sites.first}: ${build.sorted()}\n"
                            + "  forecast ${sites.second}: ${forecast.sorted()}\n"
                            + "  only in the build:    ${(build - forecast).sorted()}\n"
                            + "  only in the forecast: ${(forecast - build).sorted()}")
                }
            }
        }

        // --- arm C: a shared CompileRequest owner must really have both callers ---
        requestShared.forEach { (label, spec) ->
            val (owner, reachPoints) = spec
            val ownerFile = owner.substringBefore('|')
            if (!read(ownerFile).contains(owner.substringAfter('|'))) {
                problems.add("$label's shared CompileRequest owner is gone from $ownerFile. Either"
                        + " restore it, or declare the two chains as a pair so arm B compares them.")
            }
            if (reachPoints.size < 2) {
                problems.add("$label is declared a shared CompileRequest but names"
                        + " ${reachPoints.size} caller; shared means the build AND the forecast"
                        + " derive the request from one body.")
            }
            reachPoints.forEach { reaches(it, "$label ($owner)", "CompileRequest") }
        }

        if (problems.isNotEmpty()) {
            throw GradleException("Build/forecast key parity:\n\n" + problems.joinToString("\n\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
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
