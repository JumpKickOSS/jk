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
    val quarkusBootstrap = "3.28.5"
    compileOnly("io.quarkus:quarkus-bootstrap-core:$quarkusBootstrap")
    compileOnly("io.quarkus:quarkus-bootstrap-maven-resolver:$quarkusBootstrap")
    compileOnly("io.quarkus:quarkus-bootstrap-app-model:$quarkusBootstrap")
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
