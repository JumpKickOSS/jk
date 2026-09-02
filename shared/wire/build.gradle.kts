// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk engine API: the client<->engine wire contract — protocol codec, engine paths/" +
        "transport, and the front-end-safe build DTOs/listeners (slim-client Stage 5). The thin " +
        "surface a front-end links to talk to the engine; the engine implementation lives in :engine."

dependencies {
    // The DTOs speak the jk-api vocabulary (BuildPlan/Step/BuildPlanListener, JkBuild, TestSummary).
    api(project(":jk-api"))
    // CachePruneScheduler consults JkCacheConfig (~/.jk/config.toml [cache]).
    api(project(":core"))
    // EngineProtocol encodes/decodes with the shared Jsonl codec (not the plugin SPI).
    api(project(":host"))
    // TEST ONLY. `EngineProtocol.INVOCATION_PHASE` frames carry the wire names of :plugin-sdk's
    // InvocationPhase enum, and EngineProtocolTest closes that set against the enum itself rather
    // than restating it. The enum is the owner; production code here only ever sees the String, so
    // this edge stays out of `api`/`implementation` and never reaches a client classpath.
    testImplementation(testFixtures(project(":host")))
    testImplementation(project(":plugin-sdk"))
}

// ---------------------------------------------------------------------------
// Guard (letter allocated at landing): retired wire spellings stay retired.
//
// Defect it prevents: a spelling this module already killed growing back somewhere :wire:test
// cannot see. The ban list lives HERE because retired names have no code owner by definition —
// there is deliberately no constant left to read them from. The live owners are named in the
// failure message instead.
//
// Two arms, because the corpora fail differently:
//   java — the 8 retired test-count spellings, banned as a field key (quoted or dotted)
//          in every production Java source. The one owner is TestSummary.WIRE_KEY +
//          countsJson/countsMap/readCounts.
//   spa  — those 8, plus `class`, the retired diagnostic alias of EngineProtocol.TEST_CLASS_FIELD
//         , banned in the dashboard's JS/HTML, which cannot import Java and hand-types
//          every key it reads. `class` cannot join the java arm: `.class` literals and the
//          test-worker discovery protocol (LauncherPath/JUnitLauncher `--list-only`) keep that
//          token live in Java on purpose, so the ban's shape and the defect's shape only match in
//          the SPA.
//
// Both arms self-fail on an implausibly small corpus — measured 1,238 production Java sources and
// 23 SPA assets when this landed (the two-segment module glob is deliberate: `docs/user/examples`
// and generated sample projects under a module's `build/` are not production source).
// This is a guard task, not a JUnit test, because a test scanning
// other modules' sources goes UP-TO-DATE with them unchanged (the ActionTreeTest lesson,
// shared/host/build.gradle.kts) and is invisible to the guard registry.

// True when the source references `name` as a field key: quoted (a JSON key in Java or JS) or
// dotted (a JS property read). The trailing boundary matters: `.className` and `.classList` are
// reads of longer identifiers, not of the banned key.
fun readsTheKey(body: String, name: String): Boolean {
    var i = body.indexOf(name)
    while (i >= 0) {
        if (i > 0) {
            val before = body[i - 1]
            val after = body.getOrNull(i + name.length)
            val wholeWord = after == null || !(after.isLetterOrDigit() || after == '_' || after == '$')
            if (wholeWord && (before == '"' || before == '\'' || before == '.')) return true
        }
        i = body.indexOf(name, i + 1)
    }
    return false
}

// Guard G28.
val checkNoRetiredWireSpelling by tasks.registering {
    group = "verification"
    description = "Fail the build on a retired wire-key spelling typed as a field key in production source"
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val javaSources = rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("*/*/src/main/java/**/*.java")
        exclude("**/build/classes/**", "**/build/generated/**", "**/build/tmp/**", "**/build/resources/**")
    }
    val spaSources = rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("clients/web/src/main/resources/web/**/*.js", "clients/web/src/main/resources/web/**/*.html")
    }
    inputs.files(javaSources).withPropertyName("treeWideProductionSources")
    inputs.files(spaSources).withPropertyName("spaAssets")
    val stamp = layout.buildDirectory.file("guards/no-retired-wire-spelling.ok")
    outputs.file(stamp)
    doLast {
        val retiredEverywhere = listOf(
                "testTotal",
                "testSucceeded",
                "testFailed",
                "testSkipped",
                "testsTotal",
                "testsSucceeded",
                "testsFailed",
                "testsSkipped")
        val retiredInSpa = retiredEverywhere + "class"

        val javaFiles = javaSources.files.sorted()
        val spaFiles = spaSources.files.sorted()
        if (javaFiles.size < 1_150 || spaFiles.size < 15) {
            throw GradleException("The retired-wire-spelling guard scanned ${javaFiles.size} production Java"
                    + " sources and ${spaFiles.size} SPA assets; it was measured against 1,238 and 23. The"
                    + " include pattern has stopped seeing the tree — fix it before trusting a green run.")
        }

        val hits = mutableListOf<String>()
        fun scan(files: List<File>, retired: List<String>) {
            files.forEach { f ->
                val body = f.readText()
                retired.forEach { name ->
                    if (readsTheKey(body, name)) {
                        hits.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}: \"$name\"")
                    }
                }
            }
        }
        scan(javaFiles, retiredEverywhere)
        scan(spaFiles, retiredInSpa)
        if (hits.isNotEmpty()) {
            throw GradleException("Retired wire-key spellings are back in production source:\n"
                    + hits.joinToString("\n")
                    + "\n  Test counts have one owner: TestSummary.WIRE_KEY + countsJson/countsMap/readCounts"
                    + " (the nested tests:{total,succeeded,failed,skipped} object). The failing test's class"
                    + " is EngineProtocol.TEST_CLASS_FIELD (\"testClass\"); the SPA reads d.testClass only.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoRetiredWireSpelling) }
tasks.named("jar") { dependsOn(checkNoRetiredWireSpelling) }

// `WireKeyClosureTest` closes the JSON key read/write vocabulary over the WHOLE tree, so its result
// depends on every module's production sources — none of which Gradle would otherwise treat as an
// input to `:wire:test`. Without this the task goes UP-TO-DATE and the closure silently stops being
// checked: shared/host measured that exact failure for ActionTreeTest (an orphan read reintroduced
// in `:engine` left the test green until `--rerun-tasks`). A guard that does not re-run is a guard
// that is not there.
tasks.named<Test>("test") {
    inputs.files(rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("*/*/src/main/java/**/*.java")
        exclude("**/build/classes/**", "**/build/generated/**", "**/build/tmp/**", "**/build/resources/**")
    })
            .withPropertyName("treeWideProductionSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
