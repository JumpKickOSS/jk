// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk web dashboard: the resident engine's single-page dashboard (Vue-from-CDN SPA, no " +
        "build step). Pure static assets served at classpath /web/ — no Java main. The engine takes " +
        "this as a runtimeOnly dependency so StaticContent finds /web/* and the jk-engine fat jar " +
        "bundles it; the native CLI never links it (the dashboard is a server concern). Its tests are " +
        "the headless `node --test` suites under src/test/js, driven by WebClientJsTest."

dependencies {
    // WireTokenParityTest holds wire.js's hand-typed tokens to their EngineProtocol owners.
    // Test-only: the shipped SPA stays pure static assets with no Java.
    testImplementation(testFixtures(project(":host")))
    testImplementation(project(":wire"))
}

tasks.named<Test>("test") {
    // WebClientJsTest shells out to `node --test`, reading the SPA modules and the .mjs suites from
    // the SOURCE tree at runtime — Gradle can't see that, so editing fold.js would otherwise leave
    // :web:test UP-TO-DATE and the suites silently never rerun. Whole directories, not a hand-listed
    // file set: a list goes stale the moment someone adds a module or a suite.
    inputs.dir("src/main/resources/web")
            .withPropertyName("spaModules")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/test/js")
            .withPropertyName("jsTestSuites")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    // WireTokenParityTest reads the engine sources that write the dashboard's SSE-layer names
    // (request-start / request-finish / run-snapshot / plan / cache). Named files, not a glob, so
    // moving one fails the build loudly instead of silently blinding the SSE arm.
    inputs.files(
            rootProject.file("server/engine/src/main/java/cc/jumpkick/engine/SsePublisher.java"),
            rootProject.file("server/engine/src/main/java/cc/jumpkick/engine/LiveRuns.java"),
            rootProject.file("server/engine/src/main/java/cc/jumpkick/engine/http/LiveVitals.java"))
            .withPropertyName("sseVocabularySources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
    // Node is a hard requirement — WebClientJsTest fails the build when it is missing, rather than
    // skipping into a green. Opting out has to be deliberate:
    //   JK_WEB_JS_SKIP=1 ./gradlew :web:test
    // Read through the provider API so the value comes from the invoking environment rather than a
    // long-lived daemon's stale copy of it. CI never sets it.
    environment("JK_WEB_JS_SKIP", providers.environmentVariable("JK_WEB_JS_SKIP").getOrElse(""))
}
