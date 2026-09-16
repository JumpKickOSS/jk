// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
    id("jk.nullmarked-conventions")
}

description = "jk-quarkus: Quarkus build plugin worker (augment + fast-jar). Declarative BOM/scaffold in jk-plugin.toml."

dependencies {
    implementation(project(":plugin-sdk"))
    // Compile against bootstrap APIs; at runtime the engine supplies step-dependency jars on the
    // forked worker CP (quarkus-bootstrap + maven-resolver + aligned smallrye-common), resolved at
    // the project's own Quarkus version. The catalog pin here is the compile-time floor and tracks
    // plugins/quarkus/jk.toml's [provided-dependencies].
    compileOnly(libs.quarkus.bootstrap.core)
    compileOnly(libs.quarkus.bootstrap.maven.resolver)
    compileOnly(libs.quarkus.bootstrap.app.model)
    // Real PlatformImportsImpl for the platform-properties injection tests — the injection is
    // pure model + file I/O, so the tests exercise the production types, not stand-ins.
    testImplementation(libs.quarkus.bootstrap.app.model)
    // `FakeBuildIo` — the shared engine-side fake for a packager/step body.
    testImplementation(testFixtures(project(":plugin-sdk")))
}
