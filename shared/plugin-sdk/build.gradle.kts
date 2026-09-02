// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
    `java-test-fixtures`
    `maven-publish`
}

description = "jk plugin SPI: the stable surface plugins compile against. " +
        "The JSONL codec and the host primitives (Hashing, PathUtil, Errors, Os, Exit) live " +
        "in :host, which every plugin therefore reaches."

// Published as `cc.jumpkick:jk-plugin-sdk` on its OWN version line, independent of
// cc.jumpkick.model.JkVersion (jk's release train): the SPI freezes on a different cadence. This
// line is the ONE owner of the SDK version: it is what actually gets published, `test` hands it to
// PublishedSdkConsumerTest below, and PluginSdkScaffoldVersionTest (in :core) reads it back to hold
// the `jk new --plugin` scaffold pin against it. A later pass deleted the Java constant that used to
// mirror it — nothing in production read it, and a mirror a compiler cannot check is a comment.
group = "cc.jumpkick"
version = "0.1.0"

// The plugin SPI leaf (plus :host for the shared codec and host primitives). Classes ride the user's
// test JVM on the project's pinned JDK — JDK 17 floor. :jk-api carries
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
// :host publishes as `cc.jumpkick:jk-host` — see shared/host/build.gradle.kts.
dependencies {
    api(project(":host"))
    // The SPI's own fake (`FakeBuildIo`) lives in `testFixtures`, so the four plugin modules that
    // drive a packager or a step body share one implementation of these interfaces instead of four.
    // It is here rather than in :host's testFixtures because it names PackageIo/TaskExec, and :host
    // is the floor those types sit on — reach decides placement, not convenience.
    testFixturesApi(libs.junit.jupiter)
}

// Test fixtures are NOT part of what `cc.jumpkick:jk-plugin-sdk` publishes: a third-party plugin
// author compiles against the SPI, and the fake engine jk tests itself with is not part of that
// contract. Without this, Gradle would add testFixtures variants plus a `-test-fixtures` jar to the
// publication and name JUnit in the SDK's module metadata.
val sdkJavaComponent = components["java"] as AdhocComponentWithVariants
listOf("testFixturesApiElements", "testFixturesRuntimeElements").forEach { name ->
    sdkJavaComponent.withVariantsFromConfiguration(configurations[name]) { skip() }
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

/**
 * [src] with every comment blanked to spaces (newlines kept, offsets preserved) and every string
 * literal kept verbatim — so a scan can match code + the string keys it reads, while javadoc that
 * merely *warns about* a banned name stays invisible without an allowlist.
 */
fun blankJavaComments(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        when {
            src.startsWith("//", i) -> {
                while (i < src.length && src[i] != '\n') {
                    out.append(' ')
                    i++
                }
            }
            src.startsWith("/*", i) -> {
                val end = src.indexOf("*/", i + 2)
                val stop = if (end < 0) src.length else end + 2
                while (i < stop) {
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
            }
            src.startsWith("\"\"\"", i) -> {
                val end = src.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) src.length else end + 3
                out.append(src, i, stop)
                i = stop
            }
            src[i] == '"' || src[i] == '\'' -> {
                val quote = src[i]
                out.append(src[i])
                i++
                while (i < src.length && src[i] != quote) {
                    if (src[i] == '\\' && i + 1 < src.length) {
                        out.append(src[i]).append(src[i + 1])
                        i += 2
                    } else {
                        out.append(src[i])
                        i++
                    }
                }
                if (i < src.length) {
                    out.append(src[i])
                    i++
                }
            }
            else -> {
                out.append(src[i])
                i++
            }
        }
    }
    return out.toString()
}

// A worker that reads JK_OFFLINE or an offline system property reads the *engine daemon's*
// startup environment, so one `JK_OFFLINE=1 jk build` pins every later build in that session
// offline. The one correct source is the spec: TaskExec.offline(), sealed by the engine at the
// fork. This guard bans the bypass shapes in worker sources — the env read (both spellings), the
// offline-keyed property read, and the producer side (a -D…offline literal in worker argv) — over
// plugins/*/src/main/java plus this module's own src/main/java. Tests are exempt: they
// legitimately build specs both ways. Measured 2026-08-25: 0 violations over 119 files
// (70 plugin, 49 plugin-sdk).
// Guard G29.
val checkWorkerOfflineFromSpec by tasks.registering {
    group = "verification"
    description = "Fail the build on a JK_OFFLINE / offline-property read in worker sources (use TaskExec.offline())"
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val owner = rootProject.layout.projectDirectory.file(
            "shared/plugin-sdk/src/main/java/cc/jumpkick/plugin/build/TaskExec.java")
    val workerSources = fileTree(rootProject.layout.projectDirectory) {
        include("plugins/*/src/main/java/**/*.java", "shared/plugin-sdk/src/main/java/**/*.java")
    }
    inputs.file(owner).withPropertyName("taskExec")
    inputs.files(workerSources).withPropertyName("workerSources")
    val stamp = layout.buildDirectory.file("guards/worker-offline-from-spec.ok")
    outputs.file(stamp)
    doLast {
        // Owner-read tripwire: the route this guard points to must still exist, or a green run
        // would be endorsing a spelling nobody can call.
        if (!Regex("""boolean\s+offline\s*\(\s*\)""").containsMatchIn(owner.asFile.readText())) {
            throw GradleException("TaskExec no longer declares `boolean offline()`, so this guard has"
                    + " lost the owner it points workers at. Restore the accessor or retire the guard"
                    + " deliberately.")
        }

        val files = workerSources.files.sorted()
        val pluginFiles = files.filter { it.invariantSeparatorsPath.contains("/plugins/") }
        val sdkFiles = files.filter { it.invariantSeparatorsPath.contains("/shared/plugin-sdk/") }
        if (pluginFiles.isEmpty() || sdkFiles.isEmpty()) {
            throw GradleException("The worker offline guard scanned ${pluginFiles.size} plugin and"
                    + " ${sdkFiles.size} plugin-sdk Java files; it was measured against 70 and 49."
                    + " The include pattern has stopped seeing a tree — fix it before trusting a"
                    + " green run.")
        }

        fun lineOf(text: String, offset: Int) = text.substring(0, offset).count { it == '\n' } + 1
        val propRead = Regex("""(?:getProperty|getBoolean)\s*\(\s*"([^"]*)"""")
        val argvLiteral = Regex(""""(-D[^"]*)"""")

        val hits = mutableListOf<String>()
        files.forEach { f ->
            val text = blankJavaComments(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            Regex("getenv").findAll(text).forEach { m ->
                val stop = text.indexOf(';', m.range.first).let { if (it < 0) text.length else it }
                if (text.substring(m.range.first, stop).contains("\"JK_OFFLINE\"")) {
                    hits.add("  $rel:${lineOf(text, m.range.first)}: getenv of JK_OFFLINE")
                }
            }
            propRead.findAll(text).forEach { m ->
                if (m.groupValues[1].lowercase().contains("offline")) {
                    hits.add("  $rel:${lineOf(text, m.range.first)}: offline-keyed property read"
                            + " \"${m.groupValues[1]}\"")
                }
            }
            argvLiteral.findAll(text).forEach { m ->
                if (m.groupValues[1].lowercase().contains("offline")) {
                    hits.add("  $rel:${lineOf(text, m.range.first)}: offline -D literal"
                            + " \"${m.groupValues[1]}\"")
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A worker's offline decision comes from the spec — TaskExec.offline(),"
                    + " sealed by the engine at the fork. Env/property reads here see the engine"
                    + " daemon's startup environment, not this job:\n"
                    + hits.joinToString("\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkWorkerOfflineFromSpec) }
tasks.named("jar") { dependsOn(checkWorkerOfflineFromSpec) }
