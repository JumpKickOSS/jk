// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
    `java-test-fixtures`
    `maven-publish`
}

description = "jk host leaf — the JDK-only floor every jk process links: the JSONL / MiniJson " +
        "codec shared by the CLI wire (S1) and the plugin-worker protocol (S7), the host " +
        "primitives (Hashing, PathUtil, Errors, Os, JdkFingerprint) and the Exit / command " +
        "vocabulary. Zero deps; JDK 17 floor so workers on a project JDK can load the same classes."

// Zero dependencies is the whole point. :core cannot serve this role — it api-exposes tomlj, and a
// thin worker rebuilds its classpath from a POM, so putting :core on a plugin classpath would drag
// a TOML parser into every forked worker. Anything added here lands on all 15 plugins (via
// :plugin-sdk) and inside the native image (via :cli): JDK-only, IO-light, no third-party types.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}

// ---------------------------------------------------------------------------
// `testFixtures` — the tree's shared TEST primitives. Separate source set, so none of this reaches
// `main`, the native image, a worker jar or a published POM (see the publish skip and
// `checkHostTestFixturesStayOutOfProduction` below).
//
// The zero-dependency rule above governs `main`; these classes only ever run inside a Gradle test
// JVM, so they may name JUnit. What they may NOT name is any jk module: :host is the floor, and a
// fixture here reaching :core or :plugin-sdk would invert the layering the whole module exists for.
// The plugin SPI's own fake lives in `:plugin-sdk`'s testFixtures for exactly that reason.
dependencies {
    testFixturesApi(libs.junit.jupiter)
}

// Test fixtures are NOT part of what `cc.jumpkick:jk-host` publishes. Without this, Gradle adds
// `testFixturesApiElements`/`testFixturesRuntimeElements` variants plus a `-test-fixtures`
// classified jar to the publication, so a third party resolving the SDK's floor would be offered
// jk's JUnit test scaffolding — and the published module metadata would name JUnit as a dependency
// of the one artifact whose selling point is having none.
val hostJavaComponent = components["java"] as AdhocComponentWithVariants
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
    hostJavaComponent.withVariantsFromConfiguration(configurations[name]) { skip() }
}

// `check` only, not `jar`: arm 1 reads this module's own generated module metadata, which Gradle
// derives from the jar, so hanging it off `jar` too is a genuine cycle. `checkAll` depends on every
// module's `check`, so it runs in the gate either way.

// Published, because `cc.jumpkick:jk-plugin-sdk` api-exposes these types. Without
// coordinates here Gradle rendered the SDK's only dependency from its own defaults —
// `jk:host:unspecified`, a coordinate no repository can serve — so the SDK was unresolvable for
// every consumer. This module stays a leaf: coordinates and a publication are not a dependency,
// and the zero-dep rule above is unchanged.
//
// It rides jk's release train (`cc.jumpkick.model.JkVersion.VERSION`, the same literal
// `jk.plugin-conventions` pins), NOT the SPI's independent 0.1.0 line, because :host is the floor
// the engine, the native client and all 16 workers link — jk's own `jk.toml` / `jk-lock.toml`
// already name it `cc.jumpkick:jk-host` at this version, and the flattened worker POMs resolve it
// here too. The SDK's version line stays independent: `jk-plugin-sdk` at 0.1.0 simply pins the floor
// it compiles against. Cost accepted: :host's version is public API from now on.
group = "cc.jumpkick"
version = "0.13.3"

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


// `ActionTreeTest` closes the action-index directory vocabulary over the WHOLE tree, so its result
// depends on every module's production sources — none of which Gradle would otherwise treat as an
// input to `:host:test`. Without this the task goes UP-TO-DATE and the closure silently stops being
// checked: measured directly, reintroducing a bare `resolve("keys")` in `:engine` left `:host:test`
// green until `--rerun-tasks`. A guard that does not re-run is a guard that is not there.
tasks.named<Test>("test") {
    inputs.files(rootProject.fileTree(rootProject.layout.projectDirectory) {
        include("*/*/src/main/java/**/*.java")
        exclude("**/build/classes/**", "**/build/generated/**", "**/build/tmp/**", "**/build/resources/**")
    })
            .withPropertyName("treeWideProductionSources")
            .withPathSensitivity(PathSensitivity.RELATIVE)
}

