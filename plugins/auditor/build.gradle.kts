// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-audit-runner: child-JVM worker that queries the OSV vulnerability API and " +
        "streams JSONL findings back to jk. Isolated from jk's own classpath so Jackson " +
        "and the OSV HTTP client never load in the main jk process."

dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-sdk"))  // shared JSONL codec (on the worker runtime classpath (thin jar + sidecar))
    implementation(libs.jackson.databind)
}
