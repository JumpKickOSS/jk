// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk I/O: http, cache, git, repo"

dependencies {
    // The thin client slice (http, forge auth, credential files, Cas read/link surface) — carved
    // out for the slim client (Stage 5); api so :io's own consumers keep seeing those packages.
    api(project(":client-io"))
    implementation(project(":core"))
    // JGit is now in :git-runner (subprocess worker); io no longer needs it.
    // The tree's shared test primitives (`cc.jumpkick.testing.LoopbackHttp`) — the loopback
    // route-table server this module's suites each carried a copy of. A :host source set that
    // never reaches main, the fat jar or a worker POM (JK-2443).
    testImplementation(testFixtures(project(":host")))
}
