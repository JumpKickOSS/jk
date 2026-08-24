// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-image-runner: child-JVM worker that builds and pushes OCI images via Jib. " +
        "Isolates Jib-core, Guava, and the Google HTTP stack from jk's own classpath."

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // image module deleted; ImageConfig moved to :core
    implementation(project(":plugin-sdk"))  // SPI + :host codec/primitives (worker runtime classpath via POM)
    implementation(libs.jib.core)
    // Unpacking a base image's layers to reach its JRE (BaseJre); jib-core already brings it.
    implementation(libs.commons.compress)
}
