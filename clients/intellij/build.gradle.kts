// SPDX-License-Identifier: Apache-2.0
// JumpKick IntelliJ plugin — wire-only (jk CLI + BSP). Never depends on engine jars.
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "cc.jumpkick"
version = "0.10.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.1.7")
        bundledPlugin("com.intellij.java")
        instrumentationTools()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "cc.jumpkick.idea"
        name = "JumpKick"
        version = project.version.toString()
        description.set(
            """
            JumpKick build tool integration (wire-only). Installs BSP connection
            (<code>.bsp/jk.json</code>) and runs <code>jk</code> for sync/build/test.
            Requires <code>jk</code> on PATH. Never loads the JumpKick engine jar into the IDE.
            """.trimIndent()
        )
        ideaVersion {
            sinceBuild.set("241")
            untilBuild.set("252.*")
        }
    }
}

tasks {
    buildSearchableOptions {
        enabled = false
    }
    withType<JavaCompile>().configureEach {
        options.release.set(17)
    }
}
