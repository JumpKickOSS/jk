// SPDX-License-Identifier: Apache-2.0
// A rule pack module (server/guard/packs/<name>): a jar whose entries are a jk-guards.toml fragment and
// its fixture sources, coordinates cc.jumpkick.guards:<name>:<jk version>. `installLocal` stages it into
// the local Maven store beside the worker jars (store/repos/jk-local/cc/jumpkick/guards/<name>/<ver>/),
// which is where `jk lock` finds a first-party pack a project extends.

plugins {
    id("java-library")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

// Coordinates + version must match cc.jumpkick.model.JkVersion.VERSION, like the worker plugins and
// jk-guards-junit: a project's `[guards] extends` names the pack at the installed jk's version.
group = "cc.jumpkick.guards"
version = "0.13.1"

// The artifact is the pack's directory name (server/guard/packs/<name>), not the Gradle project name.
val packArtifact: String = project.projectDir.name
base {
    archivesName.set(packArtifact)
}

tasks.register("installLocal") {
    description = "Install the ${project.name} rule pack jar+pom into the local Maven store (repos/jk-local)"
    group = "jk"
    dependsOn(tasks.jar)
    val jarProvider = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarProvider)
    val ver = project.version.toString()
    val artifact = packArtifact
    doLast {
        val jar = jarProvider.get().asFile
        val dir = JkLayoutPaths.storeRoot().resolve("repos/jk-local/cc/jumpkick/guards/$artifact/$ver")
        dir.mkdirs()
        val target = dir.resolve("$artifact-$ver.jar")
        CopyReplacing.copy(jar, target)
        dir.resolve("$artifact-$ver.pom").writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>cc.jumpkick.guards</groupId>
              <artifactId>$artifact</artifactId>
              <version>$ver</version>
              <packaging>jar</packaging>
            </project>
            """.trimIndent() + "\n"
        )
        println("Installed rule pack $artifact $ver")
        println("  path:   $target")
    }
}
