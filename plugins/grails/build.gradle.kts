// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-grails: the built-in Grails build plugin's code layer — the grails-jar " +
        "packager, which delegates to jk-spring-boot's BootJarPackager (a Grails 8 jar IS a " +
        "Boot 4.1 jar). The declarative layer (schema, grails-bom, compiler args, grails-app " +
        "source roots, scaffold) lives in jk-plugin.toml; this jar carries only the packager."

dependencies {
    implementation(project(":plugin-sdk"))
    // BootJarPackager — bundled into this fat jar below so the worker runs standalone.
    implementation(project(":spring-boot"))
    // `FakeBuildIo` — the shared engine-side fake for a packager/step body.
    testImplementation(testFixtures(project(":plugin-sdk")))
}
