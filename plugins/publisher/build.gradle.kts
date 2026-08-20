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
    implementation(project(":core"))  // Hashing (util) + io + jsonl codec all reachable transitively
    implementation(project(":io"))
    implementation(project(":plugin-sdk"))  // shared JSONL codec (on the worker runtime classpath (thin jar + sidecar))
    implementation(libs.bouncycastle.bcpg)
    implementation(libs.sigstore.java)

    // GpgTestFixture moved here from supply-chain-testkit (which is deleted)
    testImplementation(libs.bouncycastle.bcpg)
}
