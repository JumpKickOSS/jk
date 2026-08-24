// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    `maven-publish`
}

description = "jk host leaf — the JDK-only floor every jk process links: the JSONL / MiniJson " +
        "codec shared by the CLI wire (S1) and the plugin-worker protocol (S7), the host " +
        "primitives (Hashing, PathUtil, Errors, Os, JdkFingerprint) and the Exit / command " +
        "vocabulary. Zero deps; JDK 17 floor so workers on a project JDK can load the same classes."

// Zero dependencies is the whole point. :core cannot serve this role — it api-exposes tomlj, and a
// thin worker rebuilds its classpath from a POM, so putting :core on a plugin classpath would drag
// a TOML parser into every forked worker. Anything added here lands on all 16 plugins (via
// :plugin-sdk) and inside the native image (via :cli): JDK-only, IO-light, no third-party types.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}

// Published, because `cc.jumpkick:jk-plugin-sdk` api-exposes these types (JK-2466). Without
// coordinates here Gradle rendered the SDK's only dependency from its own defaults —
// `jk:host:unspecified`, a coordinate no repository can serve — so the SDK was unresolvable for
// every consumer. This module stays a leaf: coordinates and a publication are not a dependency,
// and the zero-dep rule above is unchanged.
//
// It rides jk's release train (`cc.jumpkick.model.JkVersion.VERSION`, the same literal
// `jk.plugin-conventions` pins), NOT the SPI's independent 0.1.0 line, because :host is the floor
// the engine, the native client and all 16 workers link — jk's own `jk.toml` / `jk-lock.toml`
// already name it `cc.jumpkick:jk-host` at this version, and the flattened worker POMs resolve it
// here too. The SDK's version line stays independent: `jk-plugin-sdk:0.1.0` simply pins the floor
// it compiles against. Cost accepted: :host's version is public API from now on.
group = "cc.jumpkick"
version = "0.12.0"

publishing {
    publications {
        create<MavenPublication>("host") {
            artifactId = "jk-host"
            from(components["java"])
            pom {
                name.set("jk host leaf")
                description.set(
                        "The JDK-only floor every jk process links: the JSONL codec, Hashing, "
                                + "PathUtil, Errors, Os and the Exit vocabulary. Zero dependencies. "
                                + "Published because cc.jumpkick:jk-plugin-sdk api-exposes it.")
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
