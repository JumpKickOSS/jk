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
            // No upper bound (`provider { null }` is the documented way to unset until-build).
            // A pinned untilBuild expires on JetBrains' release cadence, not ours: "252.*" says
            // "build 252 or older", and IDEA Community 2025.3 is build 253 (shipped 2025-12-08,
            // and the newest release the JetBrains releases API lists) — so the plugin refused to
            // install on every IDEA a user can download. This plugin is wire-only: it shells out
            // to the `jk` binary and never touches an internal platform API, so there is no
            // version it can silently outgrow. G22 keeps the bound off.
            untilBuild = provider { null }
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
    // Packaging guard: plugin must not ship engine/server jars (JK-1511). On `buildPlugin`, not
    // `test`: `scripts/package-intellij.sh` runs `buildPlugin` and nothing else, so hanging the
    // scan off `test` meant it had never run against a distribution it was written to inspect.
    buildPlugin {
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
