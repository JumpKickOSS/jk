// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-audit-runner: child-JVM worker that queries the OSV vulnerability API and " +
        "streams JSONL findings back to jk. Isolated from jk's own classpath so the OSV HTTP " +
        "client never loads in the main jk process."

// No JSON library. OSV's batch and vuln documents are read with MiniJson, which :plugin-sdk
// already puts on this worker's classpath via :host — Jackson 3 was a second tree reader worth
// 2.65 MB and three jars on a 14 KB worker whose POM-rebuilt launch classpath has to download
// every one of them (JK-2422).
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-sdk"))  // SPI + :host codec/primitives (worker runtime classpath via POM)
}
