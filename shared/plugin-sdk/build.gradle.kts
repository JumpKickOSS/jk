// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

description = "jk plugin SPI: the stable surface plugins compile against, plus " +
        "the host<->plugin wire protocol codec."

// Published as `cc.jumpkick:jk-plugin-sdk` on its OWN version line, independent of
// cc.jumpkick.model.JkVersion (jk's release train): the SPI freezes on a different cadence. Keep
// this literal in sync with cc.jumpkick.plugin.PluginSdkVersion.VERSION (the constant the CLI reads
// to render `jk new --plugin` scaffolds).
group = "cc.jumpkick"
version = "0.1.0"

// Dependency-free BY DESIGN — this is the bottom of the contract-leaf pair (see
// shared/plugin-sdk/build.gradle.kts): its classes are vendored into jk-test-runner and ride
// the user's test JVM on the project's pinned JDK, so this module holds jk's promised
// JDK 17 project floor. model depends on THIS module (for PluginConfig), never the
// reverse.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}

// The one library artifact a third-party build plugin compiles against. NOT jk.plugin-conventions:
// that fat-jars the module, pins it to JkVersion, and sets Main-Class=PluginMain — all wrong for a
// dependency-free SPI library. `withSourcesJar()` (from jk.java-conventions) rides along.
publishing {
    publications {
        create<MavenPublication>("sdk") {
            artifactId = "jk-plugin-sdk"
            from(components["java"])
            pom {
                name.set("jk plugin SDK")
                description.set(
                        "The stable API third-party jk build plugins compile against, plus the " +
                                "host<->plugin JSONL wire codec.")
                url.set("https://github.com/BryanSant/jk")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("jk")
                        name.set("jk contributors")
                    }
                }
                scm {
                    url.set("https://github.com/BryanSant/jk")
                    connection.set("scm:git:https://github.com/BryanSant/jk.git")
                }
            }
        }
    }
}
