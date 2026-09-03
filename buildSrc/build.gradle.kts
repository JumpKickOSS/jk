// SPDX-License-Identifier: Apache-2.0

plugins {
    `kotlin-dsl`
}

dependencies {
    implementation("net.ltgt.gradle:gradle-errorprone-plugin:5.1.0")
    implementation("net.ltgt.gradle:gradle-nullaway-plugin:3.1.0")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.0")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
}

tasks.test {
    useJUnitPlatform()
}
