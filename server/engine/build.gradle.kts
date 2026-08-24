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

// ---------------------------------------------------------------------------
// Guard G14 (JK-2410): a forecast key must hash the same facts as the build key.
//
// `jk explain` re-derives, in a second hand-written copy, every cache key the build computes. When
// the copies disagree the forecast either reports a phantom rebuild (the visible symptom) or blesses
// a stale artifact (the dangerous one). Six such drifts were live at once. The two tests that claimed
// to guard this asserted two hand-typed `List.of(...)` literals in the *test file* against each other
// instead of reading the real bags, which is exactly why they stayed green through all six.
//
// Two arms, both plain text scans over this module's `src/main/java`:
//
//   A. `ActionKey.forArtifact` token bags. Whole keys legitimately differ — the two sides run at
//      different times over different inputs. The *set of `"<prefix>:"` literals* may not: a prefix
//      on one side only means one side hashes a fact the other ignores. Every `forArtifact` site in
//      the module must appear below, either as half of a pair or as unpaired with the reason stated,
//      so a new site cannot be added without deciding which it is.
//
//   B. `CompileRequest` builder chains, restricted to the fields `ActionKey.forJavac` actually
//      reads. forJavac hashes release/extraOptions/sources/classpath/processorPath today and the
//      four keyed chains agree on those (JK-2392 measured byte-identical keys, with `release 24` as
//      the sensitivity control). `javaHome` is set by the build and not by the forecast, and that is
//      harmless only because forJavac ignores it — luck, not design. JK-2460 will start hashing it;
//      the moment `request.javaHome()` appears in forJavac this guard goes red until both
//      TaskForecaster chains set it too. That is the point of the arm: it prices the next change.
//
// What a prefix set cannot see, stated so nobody mistakes green for parity: value drift behind an
// agreed prefix. Both assembly sites emit `main:`, but the build derives it from
// `project.mainClass()` while the forecast uses `PluginModule.mainClass(dir, project)`, which
// answers WORKER_MAIN for a plugin worker. The same blind spot covers the mixed-Scala and
// mixed-Groovy inputs the forecast's CompileRequest never sets at all. Both are real and both need
// one shared key owner rather than a text scan (round3 forecast audit F1/F2).
// ---------------------------------------------------------------------------

// A build site and its forecast twin, addressed as `<file>|<key variable>`: every bag site is
// written `String <var> = ActionKey.forArtifact(...)` and the variable is unique within its file.
val forArtifactPairs = listOf(
        "package-jar" to ("PlannerPackage.java|pkgKey" to "TaskForecaster.java|pkgKey"),
        "package-assembly" to ("PlannerTails.java|shKey" to "TaskForecaster.java|shKey"))

// forArtifact sites with no forecast twin, and why there is nothing to compare them against.
val forArtifactUnpaired = mapOf(
        "PlannerTails.java|key" to "package-sources: explain does not forecast the sources jar at all",
        "PlannerNative.java|nKey" to "native-image: the forecast probes the task pointer, not a token bag",
        "PlannerPlugin.java|actionKey" to "plugin step: not forecast",
        "PlannerPlugin.java|pkgKey" to "plugin packager: not forecast",
        "ImagePlans.java|imgKey" to "OCI tarball: jk image is not forecast",
        "BuildLogicSupport.java|key" to "build-logic compile: not forecast")

// A keyed CompileRequest chain, addressed as `<file>|<marker>`. The marker must occur exactly once
// in its file; the chain is the `CompileRequest.builder()` nearest to it.
val compileRequestPairs = listOf(
        "compile-main" to
                ("PlannerCompile.java|\"compile-main\", javaOut" to "TaskForecaster.java|\"compile-main\", out)"),
        "compile-test" to
                ("TestSupport.java|qualifiedTaskId(taskId, outputDir)"
                        to "TaskForecaster.java|\"compile-test\", testOut)"))

// Every `CompileRequest.builder()` site in the module and how many times it appears, so a new chain
// has to be declared as keyed (above) or unkeyed (here) before the build will run.
val compileRequestSites = mapOf(
        "PlannerCompile.java" to 1, // keyed: compile-main build
        "TestSupport.java" to 1, // keyed: compile-test build
        "TaskForecaster.java" to 2, // keyed: both forecasts
        "LocalProjectBuilder.java" to 1, // unkeyed: source-dependency build calls JavacRunner directly
        "ScriptPlans.java" to 1) // unkeyed: jk run <script> calls JavacRunner directly

val checkForecastKeyParity by tasks.registering {
    group = "verification"
    description = "Fail the build when a forecast key hashes a different fact set than the build key"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val pairs = forArtifactPairs
    val unpaired = forArtifactUnpaired
    val requestPairs = compileRequestPairs
    val requestSites = compileRequestSites
    // The declarations above are inputs too: editing a table without touching a source file still
    // has to re-run the check, or the ratchet can be loosened by an up-to-date task.
    inputs.property("declarations", listOf(pairs, unpaired, requestPairs, requestSites).toString())
    val stamp = layout.buildDirectory.file("guards/forecast-key-parity.ok")
    outputs.file(stamp)
    doLast {
        val sources = mainJava.files.sorted()
        val byName = sources.groupBy { it.name }
        fun read(file: String): String {
            val hits = byName[file] ?: throw GradleException("G14: no $file under src/main/java")
            if (hits.size != 1) throw GradleException("G14: $file is ambiguous: $hits")
            return hits[0].readText()
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
                (pairs.flatMap { listOf(it.second.first, it.second.second) } + unpaired.keys).toSortedSet()
        if (foundSites != declaredSites) {
            problems.add("Every ActionKey.forArtifact site must be declared in"
                    + " server/engine/build.gradle.kts, as half of a build/forecast pair or as"
                    + " unpaired with the reason there is no twin. An undeclared site is a key"
                    + " nobody has decided how to forecast.\n"
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

        if (problems.isNotEmpty()) {
            throw GradleException("Build/forecast key parity (JK-2410):\n\n" + problems.joinToString("\n\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkForecastKeyParity) }
tasks.named("jar") { dependsOn(checkForecastKeyParity) }
