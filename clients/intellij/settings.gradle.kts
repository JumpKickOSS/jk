// Standalone IntelliJ plugin build. Not part of the root multi-project.
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
rootProject.name = "jumpkick-intellij"

dependencyResolutionManagement {
    versionCatalogs { create("libs") { from(files("../../gradle/libs.versions.toml")) } }
}
