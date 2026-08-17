// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: TOML config parser, lockfile, layout, library catalog, deny " +
        "policy, plus the shared filesystem/hashing/XML machinery (PathUtil, Hashing, TreeFingerprint, " +
        "JkDirs, GitUrl, MinimalXml, AtomicWrites) absorbed from the former :support module"

dependencies {
    api(project(":jk-api"))
    // MiniJson / Jsonl live in :jsonl. Plugin tables on JkBuild use model.PluginConfig.
    api(project(":jsonl"))
    api(libs.tomlj)
    // ComparableVersion is the Maven-canonical version comparator (e.g.
    // `1.0-alpha < 1.0-rc < 1.0 < 1.0-sp1`). Used by Versions.compare, rehomed here from
    // :resolver for the slim client (tree/why/library/jdk-list are offline client verbs).
    implementation(libs.maven.artifact)
}

// Built-in plugin manifests also live under src/main/resources (self-host — jk
// has no Gradle processResources step). Keep baking from plugins/* so Gradle overwrites the
// resource tree with the plugin module's current blueprint (no silent drift).
tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    from(rootProject.file("plugins/spring-boot/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "spring-boot.jk-plugin.toml" }
    }
    // Scaffold templates ride next to the manifest (registry resource dir <id>/scaffold/...).
    from(rootProject.file("plugins/spring-boot/scaffold")) {
        into("cc/jumpkick/plugin/manifest/spring-boot/scaffold")
    }
    from(rootProject.file("plugins/grails/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "grails.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/grails/scaffold")) {
        into("cc/jumpkick/plugin/manifest/grails/scaffold")
    }
    from(rootProject.file("plugins/quarkus/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "quarkus.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/quarkus/scaffold")) {
        into("cc/jumpkick/plugin/manifest/quarkus/scaffold")
    }
    from(rootProject.file("plugins/micronaut/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "micronaut.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/micronaut/scaffold")) {
        into("cc/jumpkick/plugin/manifest/micronaut/scaffold")
    }
    from(rootProject.file("plugins/android/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "android.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/protobuf/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "protobuf.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/minified/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "minified.jk-plugin.toml" }
    }
}

// SelfHostingTomlTest guards the workspace's own manifests: catalog pins (JK-1840) and the
// manifests-sha256 re-lock stamp (JK-1863). Without these inputs an edit to jk.toml /
// jk-libs.toml / jk-lock.toml leaves :core:test UP-TO-DATE and the guard silently never reruns
// (same trap :web documents for fold.js).
tasks.named<Test>("test") {
    // MacPrefs makes CoreFoundation downcalls (JK-1970). Today this is only a JDK 25 warning, but
    // restricted methods become a hard error in a later release; :cli and :engine already pass it.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    inputs.files(
        rootProject.file("jk-lock.toml"),
        rootProject.file("jk-libs.toml"),
    )
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) {
            include("jk.toml", "*/*/jk.toml")
            exclude("**/build/**", ".git/**")
        }
    )
}
