// SPDX-License-Identifier: Apache-2.0

plugins {
    `kotlin-dsl`
}

// The build of the build is locked like the build: gradle.lockfile beside this script.
dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation(libs.errorprone.gradle)
    implementation(libs.nullaway.gradle)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
