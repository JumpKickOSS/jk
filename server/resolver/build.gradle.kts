// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
}

// :resolver tests exercise the built-in registry (QuarkusPlatformContribTest), so
// bake fixtures onto the test classpath only — mirroring :core.
tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    pluginManifestResources(rootProject)
}
