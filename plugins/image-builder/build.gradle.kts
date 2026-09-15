// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
    id("jk.nullmarked-conventions")
}

description = "jk-image-runner: child-JVM worker that builds and pushes OCI images via Jib. " +
        "Isolates Jib-core, Guava, and the Google HTTP stack from jk's own classpath."

dependencies {
    // jk-model owns ImageConfig and RepoCredential — the only non-SPI first-party types this
    // worker names. :core and :io were declared here and never imported; a later pass dropped them
    // from jk.toml and this is the same edit on the Gradle side. See plugins/image-builder/jk.toml.
    implementation(project(":jk-api"))
    implementation(project(":plugin-sdk"))  // SPI + :host codec/primitives (worker runtime classpath via POM)
    implementation(libs.jib.core)
    // The JRE flavour of Guava, pinned: jib-core needs its stream collectors, and the -android
    // flavour of the same module lacks them. Same pin as plugins/image-builder/jk.toml.
    implementation(libs.guava)
    // Unpacking a base image's layers to reach its JRE (BaseJre); jib-core already brings it.
    implementation(libs.commons.compress)
    testImplementation(testFixtures(project(":host")))
}
