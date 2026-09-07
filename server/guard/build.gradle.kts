// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "jk guard: the declarative house-rule engine — jk-guards.toml schema and loader, the " +
        "per-module facts index the compile task writes, the rule evaluators, the engine-owned " +
        "baseline, and the report renderers. Engine-only, like :resolver: never on the native CLI " +
        "classpath and never inside a plugin worker."

dependencies {
    implementation(project(":host"))
    implementation(project(":jk-api"))
    implementation(project(":core"))
    // The published guard-test library: the facts-index reader and the Site/fingerprint types are
    // defined once there and consumed here, so a guard test and a TOML rule read the same index.
    // Its JUnit API is for the suites that compile against @Guard, not for this evaluator.
    implementation(project(":guard-api")) {
        exclude(group = "org.junit")
        exclude(group = "org.junit.jupiter")
        exclude(group = "org.junit.platform")
    }
    implementation(libs.asm)
    implementation(libs.asm.tree)
    implementation(libs.asm.commons)
    testImplementation(testFixtures(project(":host")))
}
