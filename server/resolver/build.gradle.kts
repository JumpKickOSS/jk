// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
    // The tree's shared test primitives (`cc.jumpkick.testing`): `LoopbackHttp` — the loopback
    // route-table server eight suites here each carried a copy of — and `SysProps`, which closes
    // this module's `jk.m2.local` leak. A :host source set that never reaches main.
    testImplementation(testFixtures(project(":host")))
    testImplementation(libs.jqwik)
}

// `ResolveProcessCacheExtension` (a ServiceLoader-registered JUnit extension) drops the
// process-wide resolve memos between tests: fixture repos routinely reuse one GAV with different
// bodies, and a GAV-keyed production cache would otherwise poison the next test. Every tier needs
// it, including networkTest — QuarkusLockPerfTest lives here. The conventions plugin sets
// autodetection default to false; this is the one module that overrides it as a Gradle system
// property.
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
