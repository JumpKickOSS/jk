// SPDX-License-Identifier: Apache-2.0

rootProject.name = "buildSrc"

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    // One version catalog for the build and the build of the build: a pin lives in
    // gradle/libs.versions.toml or it does not exist.
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
