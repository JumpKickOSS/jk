// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-quarkus: Quarkus build plugin worker (augment + fast-jar). Declarative BOM/scaffold in jk-plugin.toml."

dependencies {
    implementation(project(":plugin-sdk"))
    // Compile against bootstrap APIs; at runtime the engine supplies step-dependency jars on the
    // forked worker CP (quarkus-bootstrap + maven-resolver + aligned smallrye-common).
    // Keep in sync with cc.jumpkick.model.ToolDefaults.QUARKUS_TOOLING_BOM_VERSION: a Gradle script
    // cannot read a Java constant, so this is the one copy jk keeps by necessity.
    val quarkusBootstrap = "3.38.0"
    compileOnly(libs.quarkus.bootstrap.core)
    compileOnly(libs.quarkus.bootstrap.maven.resolver)
    compileOnly(libs.quarkus.bootstrap.app.model)
    // Real PlatformImportsImpl for the platform-properties injection tests — the injection is
    // pure model + file I/O, so the tests exercise the production types, not stand-ins.
    testImplementation(libs.quarkus.bootstrap.app.model)
    // `FakeBuildIo` — the shared engine-side fake for a packager/step body.
    testImplementation(testFixtures(project(":plugin-sdk")))
}
