// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
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
