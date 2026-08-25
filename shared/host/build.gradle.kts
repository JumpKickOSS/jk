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

// ---------------------------------------------------------------------------
// Guard G24 (JK-2492): the AOT refusal marker is spelled in exactly one file.
//
// Defect it prevents: the fourth copy of a rule that already has an owner. JK-2396 made
// `AotCacheFiles` the single owner of the JEP 514 marker name and converged five modules onto it;
// `EngineInstall` went on open-coding `.aot.noaot` / `.noaot` in three places, and that copy
// agreed with the owner only by luck. The last time the two spellings disagreed — `<stem>.noaot`
// on one side, `<stem>.aot.noaot` on the other — each sweep was blind to the other's markers, in a
// directory the engine and the worker trainer share.
//
// Two arms, because the two source sets fail differently:
//   main  — a string literal may not contain the marker suffix at all. Call the owner
//           (`marker` / `isMarker` / `cacheOf` / `blocked`), or name `AotCacheFiles.MARKER`.
//   test  — a fixture may spell a whole cache file name ("kotlinc-0123456789abcdef.aot.noaot"):
//           pinning the on-disk spelling is what those tests are for. It may not spell the bare
//           suffix, which is the owner's rule re-derived by concatenation.
//
// Neither arm has an allowlist. Both self-fail when the owner's constants cannot be read, and when
// the scan comes back implausibly small — measured against 1,236 main and 922 test Java files.
// Comments are stripped first, so prose can still name the marker; the module glob is two segments
// deep on purpose, because `build/tmp/**` holds generated sample projects with their own
// `src/main/java`.

/**
 * Every string literal in a Java source, with comments and char literals out of the way. A scan
 * that cannot tell a literal from prose either bans documentation or misses the copy sitting
 * inside a `//` comment's quotes — this tree has both shapes.
 */
fun javaStringLiterals(src: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> {
                val nl = src.indexOf('\n', i)
                i = if (nl < 0) src.length else nl + 1
            }
            src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2)
                i = if (end < 0) src.length else end + 2
            }
            src.startsWith("\"\"\"", i) -> {
                val end = src.indexOf("\"\"\"", i + 3)
                out.add(src.substring(i + 3, if (end < 0) src.length else end))
                i = if (end < 0) src.length else end + 3
            }
            src[i] == '"' || src[i] == '\'' -> {
                val quote = src[i]
                val body = StringBuilder()
                var j = i + 1
                while (j < src.length && src[j] != quote) {
                    if (src[j] == '\\') { body.append(src[j]); j++ }
                    if (j < src.length) { body.append(src[j]); j++ }
                }
                if (quote == '"') out.add(body.toString())
                i = j + 1
            }
            else -> i++
        }
    }
    return out
}

val checkSingleAotMarkerSpelling by tasks.registering {
    group = "verification"
    description = "Fail the build on a .noaot marker suffix typed outside cc.jumpkick.host.AotCacheFiles"
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/AotCacheFiles.java")
    val javaSources = fileTree(rootProject.layout.projectDirectory) {
        include("*/*/src/main/java/**/*.java", "*/*/src/test/java/**/*.java")
    }
    inputs.file(owner).withPropertyName("aotCacheFiles")
    inputs.files(javaSources).withPropertyName("javaSources")
    val stamp = layout.buildDirectory.file("guards/single-aot-marker-spelling.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        val ownerText = ownerFile.readText()
        fun constant(name: String): String =
                Regex("""String\s+$name\s*=\s*"([^"]+)"""").find(ownerText)?.groupValues?.get(1)
                        ?: throw GradleException("cc.jumpkick.host.AotCacheFiles no longer declares a"
                                + " String $name, so this guard has lost the owner it reads. Restore the"
                                + " constant or retire the guard deliberately.")
        val marker = constant("MARKER")
        val cache = constant("CACHE")

        val files = javaSources.files.filter { it != ownerFile }.sorted()
        val mainFiles = files.filter { it.invariantSeparatorsPath.contains("/src/main/java/") }
        val testFiles = files.filter { it.invariantSeparatorsPath.contains("/src/test/java/") }
        if (mainFiles.size < 1_100 || testFiles.size < 800) {
            throw GradleException("The AOT marker guard scanned ${mainFiles.size} main and"
                    + " ${testFiles.size} test Java files; it was measured against 1,236 and 922."
                    + " The include pattern has stopped seeing the tree — fix it before trusting a"
                    + " green run.")
        }

        val hits = mutableListOf<String>()
        mainFiles.forEach { f ->
            javaStringLiterals(f.readText()).filter { it.contains(marker) }.forEach {
                hits.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}: \"$it\"")
            }
        }
        testFiles.forEach { f ->
            javaStringLiterals(f.readText()).filter { it == marker || it == cache + marker }.forEach {
                hits.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}: \"$it\"")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("The AOT refusal marker is named once, in"
                    + " cc.jumpkick.host.AotCacheFiles. These re-type it:\n"
                    + hits.joinToString("\n")
                    + "\n  Use AotCacheFiles.marker(cache) / isMarker(name) / cacheOf(name) /"
                    + " blocked(cache), or AotCacheFiles.MARKER when only the suffix will do."
                    + " :host is on every production module's classpath. A test fixture may spell a"
                    + " whole cache file name, never the bare suffix.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkSingleAotMarkerSpelling) }
tasks.named("jar") { dependsOn(checkSingleAotMarkerSpelling) }

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
