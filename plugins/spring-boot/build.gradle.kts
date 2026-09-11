// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
    id("jk.nullmarked-conventions")
}

description = "jk-spring-boot: the built-in Spring Boot build plugin's code layer — the " +
        "Spring AOT step and the boot-jar packager, run in a forked worker JVM over the " +
        "build-plugin harness. The declarative layer (schema, BOM, compiler args, kotlin " +
        "plugins) lives in jk-plugin.toml at this jar's root, plus the hard 10% code. Deliberately " +
        "depends on plugin-api alone: it is the blueprint third-party build plugins copy."

dependencies {
    implementation(project(":plugin-sdk"))
    // `FakeBuildIo` — the shared engine-side fake for a packager/step body.
    testImplementation(testFixtures(project(":plugin-sdk")))
}
