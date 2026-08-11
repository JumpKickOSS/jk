// SPDX-License-Identifier: Apache-2.0
// JumpKick IntelliJ plugin — wire-only (jk CLI + BSP). Never depends on engine jars.
plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = "cc.jumpkick"
version = "0.12.0"

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
    testImplementation("junit:junit:4.13.2")
}

intellijPlatform {
    pluginConfiguration {
        id = "cc.jumpkick.idea"
        name = "JumpKick"
        version = project.version.toString()
        description.set(
            """
            JumpKick build tool integration (wire-only). <b>Sync</b> imports modules from the
            engine ide-model via the <code>jk</code> CLI, installs BSP (<code>.bsp/jk.json</code>),
            and runs build/test/lock. Requires <code>jk</code> on PATH / <code>JK_BIN</code>.
            Never loads the JumpKick engine jar into the IDE.
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
    // Packaging guard: plugin must not ship engine/server jars (JK-1511).
    test {
        doLast {
            val distJar = layout.buildDirectory.file("libs/jumpkick-intellij-${project.version}.jar")
            // Also scan composed plugin lib if present after buildPlugin.
            val pluginLibs = layout.buildDirectory.dir("distributions").get().asFile
            val bad = mutableListOf<String>()
            fun scan(f: java.io.File) {
                if (!f.exists()) return
                if (f.isFile && f.name.endsWith(".jar")) {
                    val n = f.name.lowercase()
                    if (n.contains("jk-engine") || n.contains("server-engine") || n.startsWith("engine-")) {
                        bad.add(f.absolutePath)
                    }
                } else if (f.isDirectory) {
                    f.listFiles()?.forEach(::scan)
                }
            }
            scan(layout.buildDirectory.get().asFile)
            if (bad.isNotEmpty()) {
                throw GradleException("Engine jars must not appear under plugin build output: $bad")
            }
        }
    }
}
