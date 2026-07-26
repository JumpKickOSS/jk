// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-quarkus: Quarkus build plugin worker (augment + fast-jar). Declarative BOM/scaffold in jk-plugin.toml."

dependencies {
    implementation(project(":plugin-sdk"))
    // Compile-only: forked at package time on the step-dep bootstrap classpath (matches [quarkus] version).
    compileOnly("io.quarkus:quarkus-bootstrap-core:3.28.5")
    compileOnly("io.quarkus:quarkus-bootstrap-maven-resolver:3.28.5")
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
