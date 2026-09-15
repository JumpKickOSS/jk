// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "jk-guards-junit: the guard-test library a project's src/guard suite compiles against — " +
        "@Guard, @GuardSuite, @Allow, @Fixture and the read-only Facts / Model / Text / Output / " +
        "Violations views the engine injects. Depends on :host and the JUnit Jupiter API only; JDK 17 " +
        "because it runs inside the project's forked test JVM."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}

dependencies {
    api(project(":host"))
    // The runtime is a JUnit 5 extension: @Guard is a composed @Test, @GuardSuite an @ExtendWith.
    api(libs.junit.jupiter.api)
    // ArchUnit is the user's: JkArchUnit compiles against it, jk-guards-junit never ships it.
    compileOnly(libs.archunit)
    testImplementation(libs.archunit)
    testImplementation(libs.junit.platform.launcher)
}

// The version is the tree's, read from the root jk.toml like the worker plugins
// (jk.plugin-conventions): the engine provisions this exact coordinate for a src/guard suite.
version = JkTreeVersion.of(rootProject.projectDir)

// Same destination as the worker jars' `installLocal` (jk.plugin-conventions) and `jk install`:
// store/repos/jk-local/cc/jumpkick/jk-guards-junit/<ver>/. A project's `src/guard` suite compiles
// against this coordinate at the installed jk's version, offline; the engine copies it from ~/.m2
// when the store lacks it, so publishToMavenLocal is the other route.
tasks.register("installLocal") {
    description = "Install jk-guards-junit jar+pom into the local Maven store (repos/jk-local)"
    group = "jk"
    dependsOn(tasks.jar)
    val jarProvider = tasks.jar.flatMap { it.archiveFile }
    inputs.file(jarProvider)
    val ver = project.version.toString()
    doLast {
        val jar = jarProvider.get().asFile
        val dir = JkLayoutPaths.storeRoot().resolve("repos/jk-local/cc/jumpkick/jk-guards-junit/$ver")
        dir.mkdirs()
        val target = dir.resolve("jk-guards-junit-$ver.jar")
        CopyReplacing.copy(jar, target)
        val pom = """
            <?xml version="1.0" encoding="UTF-8"?>
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>cc.jumpkick</groupId>
              <artifactId>jk-guards-junit</artifactId>
              <version>$ver</version>
              <packaging>jar</packaging>
              <dependencies>
                <dependency>
                  <groupId>cc.jumpkick</groupId>
                  <artifactId>jk-host</artifactId>
                  <version>$ver</version>
                </dependency>
              </dependencies>
            </project>
        """.trimIndent() + "\n"
        dir.resolve("jk-guards-junit-$ver.pom").writeText(pom)
        println("Installed jk-guards-junit $ver")
        println("  path:   $target")
    }
}
