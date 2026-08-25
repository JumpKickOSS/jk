// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
}

// `ResolveProcessCacheExtension` (a ServiceLoader-registered JUnit extension) drops the
// process-wide resolve memos between tests: fixture repos routinely reuse one GAV with different
// bodies, and a GAV-keyed production cache would otherwise poison the next test. Every tier needs
// it, including networkTest — QuarkusLockPerfTest lives here.
//
// This used to be `junit.jupiter.extensions.autodetection.enabled=true` in a
// `src/test/resources/junit-platform.properties`, i.e. a second mechanism for a policy the rest of
// the tree states as a Gradle system property, invisible to anyone reading a build file and
// silently outranked by one (JK-2447). One mechanism now; the conventions plugin sets the default
// to false and this is the one module that overrides it.
tasks.withType<Test>().configureEach {
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
}
