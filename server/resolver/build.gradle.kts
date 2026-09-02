// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // The tree's shared test primitives (`cc.jumpkick.testing`): `LoopbackHttp` — the loopback
    // route-table server eight suites here each carried a copy of — and `SysProps`, which closes
    // this module's `jk.m2.local` leak. A :host source set that never reaches main.
    testImplementation(testFixtures(project(":host")))
}

// `ResolveProcessCacheExtension` (a ServiceLoader-registered JUnit extension) drops the
// process-wide resolve memos between tests: fixture repos routinely reuse one GAV with different
// bodies, and a GAV-keyed production cache would otherwise poison the next test. Every tier needs
// it, including networkTest — QuarkusLockPerfTest lives here.
//
// This used to be `junit.jupiter.extensions.autodetection.enabled=true` in a
// `src/test/resources/junit-platform.properties`, i.e. a second mechanism for a policy the rest of
// the tree states as a Gradle system property, invisible to anyone reading a build file and
// silently outranked by one. One mechanism now; the conventions plugin sets the default
// to false and this is the one module that overrides it.
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
