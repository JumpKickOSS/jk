// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-quarkus: Quarkus build plugin worker (augment + fast-jar). Declarative BOM/scaffold in jk-plugin.toml."

dependencies {
    implementation(project(":plugin-sdk"))
    // Compile against bootstrap APIs; at runtime the engine supplies step-dependency jars on the
    // forked worker CP (quarkus-bootstrap + maven-resolver + aligned smallrye-common).
    // Keep in sync with cc.jumpkick.model.ToolDefaults.QUARKUS_PLATFORM_VERSION.
    val quarkusBootstrap = "3.38.0"
    compileOnly("io.quarkus:quarkus-bootstrap-core:$quarkusBootstrap")
    compileOnly("io.quarkus:quarkus-bootstrap-maven-resolver:$quarkusBootstrap")
    compileOnly("io.quarkus:quarkus-bootstrap-app-model:$quarkusBootstrap")
}
