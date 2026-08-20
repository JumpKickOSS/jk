// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-compat-runner: child-JVM worker that handles Maven/Gradle import, export, " +
        "and distribution provisioning. Keeps Maven XML parsing code and tool installer " +
        "out of the main jk binary's GraalVM reachable set."

// Import unit tests parse [spring-boot] via PluginTableRegistry — fixtures on the
// test classpath only. The published worker jar is not a catalog.
tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    pluginManifestResources(rootProject)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    implementation(project(":toolchain"))
    // compat deleted: mvn/gradle/kotlin bridge + JkBuildRenderer moved into this module
    implementation(project(":plugin-sdk"))  // shared JSONL codec (worker runtime classpath via POM)
}
