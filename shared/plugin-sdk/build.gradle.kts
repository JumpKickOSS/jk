// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

description = "jk plugin SPI: the stable surface plugins compile against. " +
        "The JSONL codec and the host primitives (Hashing, PathUtil, Errors, Os, Exit) live " +
        "in :host, which every plugin therefore reaches."

// Published as `cc.jumpkick:jk-plugin-sdk` on its OWN version line, independent of
// cc.jumpkick.model.JkVersion (jk's release train): the SPI freezes on a different cadence. This
// line is the ONE owner of the SDK version: it is what actually gets published, `test` hands it to
// PublishedSdkConsumerTest below, and PluginSdkScaffoldVersionTest (in :core) reads it back to hold
// the `jk new --plugin` scaffold pin against it. JK-2430 deleted the Java constant that used to
// mirror it — nothing in production read it, and a mirror a compiler cannot check is a comment.
group = "cc.jumpkick"
version = "0.1.0"

// The plugin SPI leaf (plus :host for the shared codec and host primitives). Classes ride the user's
// test JVM on the project's pinned JDK — JDK 17 floor. Since JK-2139, :jk-api carries
// its own same-shape PluginConfig fork; neither module depends on the other.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}

// `api`, not `implementation`: the codec and the Exit vocabulary are reachable from the SPI a
// plugin author writes against, so they belong on the consumer's compile classpath. Which is why
// :host publishes as `cc.jumpkick:jk-host` — see shared/host/build.gradle.kts (JK-2466).
dependencies {
    api(project(":host"))
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
                        "The stable API third-party jk build plugins compile against. " +
                                "JSONL codec and host primitives are cc.jumpkick:jk-host.")
                url.set("https://github.com/JumpKickOSS/jk")
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
                    url.set("https://github.com/JumpKickOSS/jk")
                    connection.set("scm:git:https://github.com/JumpKickOSS/jk.git")
                }
            }
        }
    }
}

// PublishedSdkConsumerTest resolves the SDK the way a Maven consumer does — out of a real
// repository, through the published POM — so `test` needs both halves of the closure staged first.
// The repository itself is declared once in jk.java-conventions (`treeLocal`); its location is read
// back off the build here rather than re-typed.
val treeLocal = publishing.repositories.getByName<MavenArtifactRepository>("treeLocal")

tasks.named<Test>("test") {
    dependsOn(
            "publishSdkPublicationToTreeLocalRepository",
            ":host:publishHostPublicationToTreeLocalRepository")
    // The staged repository is a test INPUT, not just a dependency: a publish task declares no
    // outputs, so without this the suite stays up-to-date across a coordinate change and reports
    // green having re-run nothing — which is how a broken POM ships past a test that covers it.
    inputs.dir(treeLocal.url).withPropertyName("treeLocalRepo")
    systemProperty("jk.tree.local.repo", File(treeLocal.url).absolutePath)
    // The version that was actually published, straight off the publishing owner above. A constant
    // in src/main could disagree with it and the resolve would still find *a* jar.
    systemProperty("jk.plugin.sdk.version", version.toString())
}
