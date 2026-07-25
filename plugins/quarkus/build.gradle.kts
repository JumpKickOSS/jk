// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-quarkus: Quarkus build plugin worker (fast-jar packager). Declarative BOM/scaffold in jk-plugin.toml."

dependencies {
    implementation(project(":plugin-sdk"))
}

tasks.jar {
    dependsOn(configurations.runtimeClasspath)
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}
