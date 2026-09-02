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
// every one of them.
dependencies {
    implementation(project(":core"))
    implementation(project(":plugin-sdk"))  // SPI + :host codec/primitives (worker runtime classpath via POM)
}

// PublishedWorkerPomTest reads the worker POM the way a launch does — out of the Maven repo
// `stageWorkerRepo` writes, which is what `installLocal` copies into store/repos/jk-local and what
// scripts/publish-maven-repo.sh uploads. This module is the sample: its closure spans all four
// first-party rungs (:core, :plugin-sdk, :host, :jk-api) plus third-party jars, so a coordinate
// rendered from a Gradle default shows up here first.
val stagedWorkerRepo = layout.buildDirectory.dir("worker-repo/repos/jk-local")

tasks.named<Test>("test") {
    dependsOn("stageWorkerRepo")
    // The staged repo is a test INPUT, not just a dependency: without it the suite stays
    // up-to-date across a coordinate change and reports green having re-run nothing.
    inputs.dir(stagedWorkerRepo).withPropertyName("stagedWorkerRepo")
    systemProperty("jk.worker.repo", stagedWorkerRepo.get().asFile.absolutePath)
    systemProperty("jk.worker.project", project.name)
}
