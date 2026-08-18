// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk resolver: PubGrub solver and conflict diagnostics"

dependencies {
    implementation(project(":core"))
    implementation(project(":io"))
}

// Built-in plugin manifests are engine-only (JK-2149). :resolver tests exercise the
// built-in registry (QuarkusPlatformContribTest), so bake the same tree onto the test
// classpath only — mirroring :core.
tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    pluginManifestResources(rootProject)
}
