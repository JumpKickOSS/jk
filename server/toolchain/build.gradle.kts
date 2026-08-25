// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk toolchain (engine remainder): resolver-backed tool installs, compat/import machinery"

dependencies {
    // The client-retained slice (JDK flow, discovery probes, launcher shims, exporters) — carved
    // out for the slim client (Stage 5); api so :toolchain's consumers keep seeing those packages.
    api(project(":toolchain-jdk"))
    implementation(project(":core"))
    implementation(project(":io"))
    // ToolResolver leans on NaiveResolver + EffectivePomBuilder for transitive deps.
    implementation(project(":resolver"))
    // JdkInstaller extracts tar.gz archives using MinimalTar (built-in, no library).
    // JdkCatalogClient downloads jdks.json (uncompressed) — no XZ or JSON library needed.
    // The tree's shared test primitives (`cc.jumpkick.testing.LoopbackHttp`) — the loopback
    // route-table server this module's suites each carried a copy of. A :host source set that
    // never reaches main, the fat jar or a worker POM (JK-2443).
    testImplementation(testFixtures(project(":host")))
}
