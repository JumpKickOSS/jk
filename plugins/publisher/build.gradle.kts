// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-publish-runner: child-JVM worker that assembles, signs, and publishes Maven " +
        "artifacts. Isolates BouncyCastle, sigstore-java, and the upload HTTP logic from jk's " +
        "own classpath. Reads a line-oriented spec, streams JSONL progress back to jk."

tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    pluginManifestResources(rootProject)
}

dependencies {
    implementation(project(":core"))  // repo/lock model; Hashing + codec now come from :host via the SDK
    implementation(project(":io"))
    implementation(project(":plugin-sdk"))  // SPI + :host codec/primitives (worker runtime classpath via POM)
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.sigstore.java)

    // GpgTestFixture moved here from supply-chain-testkit (which is deleted)
    testImplementation(libs.bouncycastle.bcpg)
    testImplementation(testFixtures(project(":core")))
}
