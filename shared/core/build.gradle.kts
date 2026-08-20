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
}

// Built-in plugin manifests + scaffolds are engine-only (JK-2149). :core tests still
// parse PluginTableRegistry, so bake the same tree onto the test classpath only.
tasks.processTestResources {
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    pluginManifestResources(rootProject)
}

tasks.named<Jar>("jar") {
    doLast {
        val jarFile = archiveFile.get().asFile
        val baked =
            zipTree(jarFile)
                .matching {
                    include("cc/jumpkick/plugin/manifest/**")
                    exclude("**/*.class")
                }
                .files
        if (baked.isNotEmpty()) {
            throw GradleException(
                ":core jar must not contain plugin manifests: " +
                    baked.map { it.name }.sorted())
        }
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
