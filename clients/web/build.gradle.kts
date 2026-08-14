// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk web dashboard: the resident engine's single-page dashboard (Vue-from-CDN SPA, no " +
        "build step). Pure static assets served at classpath /web/ — no Java main. The engine takes " +
        "this as a runtimeOnly dependency so StaticContent finds /web/* and the jk-engine fat jar " +
        "bundles it; the native CLI never links it (the dashboard is a server concern). The only " +
        "test is the headless fold.js event-folding suite, run via `node --test` when Node is present."

// WebClientFoldTest shells out to `node --test` reading fold.js and fold.test.mjs from the
// SOURCE tree at runtime — Gradle can't see that, so declare them as inputs or editing fold.js
// leaves :web:test UP-TO-DATE and the suite silently never reruns.
tasks.named<Test>("test") {
    inputs.files(
                    "src/main/resources/web/fold.js",
                    "src/test/js/fold.test.mjs",
                    "src/main/resources/web/code.js",
                    "src/test/js/code.test.mjs")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}
