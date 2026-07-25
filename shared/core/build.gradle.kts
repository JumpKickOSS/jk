// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk core foundations: TOML config parser, lockfile, layout, library catalog, deny " +
        "policy, plus the shared filesystem/hashing/XML machinery (PathUtil, Hashing, TreeFingerprint, " +
        "JkDirs, GitUrl, MinimalXml, AtomicWrites) absorbed from the former :support module"

dependencies {
    api(project(":jk-api"))
    // MiniJson + Jsonl live in :plugin-sdk (JK-1133); core depends on plugin-sdk for the tree codec.
    // plugin-api itself depends only on :model, so the direction is legal.
    api(project(":plugin-sdk"))
    api(libs.tomlj)
    // ComparableVersion is the Maven-canonical version comparator (e.g.
    // `1.0-alpha < 1.0-rc < 1.0 < 1.0-sp1`). Used by Versions.compare, rehomed here from
    // :resolver for the slim client (tree/why/library/jdk-list are offline client verbs).
    implementation(libs.maven.artifact)
}

// Built-in plugin manifests also live under src/main/resources (self-host / ticket-1007 — jk
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
    from(rootProject.file("plugins/android/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "android.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/protobuf/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "protobuf.jk-plugin.toml" }
    }
    from(rootProject.file("plugins/shrink/jk-plugin.toml")) {
        into("cc/jumpkick/plugin/manifest")
        rename { "shrink.jk-plugin.toml" }
    }
}
