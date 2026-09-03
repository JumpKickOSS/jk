// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
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

// ---------------------------------------------------------------------------
// Guard (letter assigned at landing): a test fixture never reaches production.
//
// Defect it prevents: the one way a shared test artifact can be catastrophically wrong. `:host` and
// `:plugin-sdk` are the two modules every jk process and every third-party plugin links, and both
// now publish to Maven Central. A `testFixtures` source set on a published module is a variant away
// from shipping JUnit as a transitive dependency of `cc.jumpkick:jk-host` — and one keyword
// (`implementation` where `testImplementation` was meant) away from a fixture landing inside the
// native image or a worker jar, where nothing else would notice until GraalVM failed to see a
// reflective JUnit lookup at runtime.
//
// Three arms, because three different things can go wrong and each is observable somewhere else:
//   1. PUBLISHED — the generated POM and Gradle module metadata of both publishing modules name no
//      test-fixtures variant and no test-framework dependency. Reads the real generated files.
//   2. WIRING — no build script hands a `testFixtures(...)` dependency to a non-test configuration.
//      A text scan over every build script, which is where the mistake is actually typed. This is
//      the arm that generalises: it covers the 15 worker modules' flattened POMs (built from
//      `runtimeClasspath`) and the native image, without resolving 31 configurations.
//   3. `:cli`'s `checkCliRuntimeClasspath` — the real resolved classpath of the native client.
//      It lives in that module because that is where the fact is.
//
// Self-fail arms: the publication files must exist and be non-trivial, the scan must find build
// scripts, and it must find at least one real `testFixtures(` usage — a scan that sees no fixtures
// at all would pass this guard while the whole mechanism had been deleted.
// Guard G34.
val checkTestFixturesStayOutOfProduction = registerGuard("checkTestFixturesStayOutOfProduction") {
    group = "verification"
    description = "Fail when a testFixtures variant reaches a publication, a POM or a production configuration"
    dependsOn(
            "generatePomFileForHostPublication",
            "generateMetadataFileForHostPublication",
            ":plugin-sdk:generatePomFileForSdkPublication",
            ":plugin-sdk:generateMetadataFileForSdkPublication")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val publications = rootProject.layout.projectDirectory.let { root ->
        listOf(
                root.file("shared/host/build/publications/host/pom-default.xml"),
                root.file("shared/host/build/publications/host/module.json"),
                root.file("shared/plugin-sdk/build/publications/sdk/pom-default.xml"),
                root.file("shared/plugin-sdk/build/publications/sdk/module.json"))
    }
    // Declare the scripts as FILES, not as a tree rooted at the repo. A fileTree whose root is the
    // root project directory overlaps `:dist`'s output directory (build/dist), and Gradle then
    // rejects the task with "uses this output of task ':dist' without declaring a dependency" —
    // which made `./gradlew build dist` fail on every invocation after the first, once build/dist
    // existed. The guard only ever reads these specific scripts.
    val scriptFiles = mutableListOf(rootProject.layout.projectDirectory.file("build.gradle.kts").asFile)
    rootProject.subprojects.forEach {
        scriptFiles.add(it.layout.projectDirectory.file("build.gradle.kts").asFile)
    }
    val conventionScripts = fileTree(rootProject.layout.projectDirectory.dir("buildSrc/src/main/kotlin")) {
        include("*.gradle.kts")
    }
    val buildScripts = files(scriptFiles.filter { it.isFile }, conventionScripts)
    inputs.files(buildScripts).withPropertyName("buildScripts")
    val stamp = layout.buildDirectory.file("guards/test-fixtures-out-of-production.ok")
    outputs.file(stamp)
    doLast {
        // ---- arm 1: what actually gets published -------------------------------------------
        val banned = listOf("test-fixtures", "testFixtures", "junit", "assertj", "opentest4j")
        val published = mutableListOf<String>()
        publications.forEach { f ->
            val file = f.asFile
            if (!file.isFile || file.length() < 100) {
                throw GradleException("The test-fixtures guard cannot read"
                        + " ${file.relativeTo(treeRoot).invariantSeparatorsPath} (missing or"
                        + " implausibly small), so arm 1 is not checking anything. Fix the"
                        + " dependsOn wiring before trusting a green run.")
            }
            val text = file.readText()
            banned.filter { text.contains(it, ignoreCase = true) }.forEach {
                published.add("  ${file.relativeTo(treeRoot).invariantSeparatorsPath}: names \"$it\"")
            }
        }
        if (published.isNotEmpty()) {
            throw GradleException("A published POM or module descriptor names a test fixture or a"
                    + " test framework. jk-host and jk-plugin-sdk are what third parties compile"
                    + " against; jk's test scaffolding is not part of that contract. Skip the"
                    + " testFixtures variants on the publication:\n" + published.joinToString("\n"))
        }

        // ---- arm 2: how the dependency is declared ----------------------------------------
        val productionConfigurations = Regex(
                """^\s*(api|implementation|compileOnly|compileOnlyApi|runtimeOnly|annotationProcessor)\s*\(""")
        val scripts = buildScripts.files.sorted()
        if (scripts.size < 20) {
            throw GradleException("The test-fixtures guard scanned ${scripts.size} build scripts; the"
                    + " tree has 31 modules. The include pattern has stopped seeing it.")
        }
        var fixtureUses = 0
        val wiring = mutableListOf<String>()
        scripts.forEach { f ->
            f.readLines().forEachIndexed { i, raw ->
                val line = raw.substringBefore("//")
                if (!line.contains("testFixtures(")) return@forEachIndexed
                fixtureUses++
                if (productionConfigurations.containsMatchIn(line)) {
                    wiring.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}:${i + 1}:"
                            + " ${line.trim()}")
                }
            }
        }
        if (fixtureUses == 0) {
            throw GradleException("The test-fixtures guard found no `testFixtures(` dependency in any"
                    + " build script. Either the shared fixtures were deleted — in which case delete"
                    + " this guard deliberately — or the scan is broken.")
        }
        if (wiring.isNotEmpty()) {
            throw GradleException("A testFixtures variant is wired into a production configuration."
                    + " That puts test code on a worker's flattened POM and inside the native image:\n"
                    + wiring.joinToString("\n")
                    + "\n  Use testImplementation / testCompileOnly / testRuntimeOnly.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
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
// Guard G24: the AOT refusal marker is spelled in exactly one file.
//
// Defect it prevents: the fourth copy of a rule that already has an owner. An earlier pass made
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

val checkSingleAotMarkerSpelling = registerGuard("checkSingleAotMarkerSpelling") {
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

// ---------------------------------------------------------------------------
// Guard (letter assigned at landing): deterministic .properties rendering has one owner.
//
// Defect it prevents: the writer Properties.store() invites. store() prepends a #-dated comment
// line and emits keys in unspecified Hashtable order, so a caller that reaches for it ships a
// non-reproducible artifact — and the historical alternative was a hand-rolled renderer per
// plugin, one spec-correct, one not escaping at all. cc.jumpkick.host.DeterministicProperties
// .render is the one writer. This bans `.store(` on any line of a main-source file that imports
// java.util.Properties. A same-file JkStores.store() call beside that import would false-positive;
// no such file exists today, and the fix is to call the owner, not to widen this scan.
//
// Self-fail arms: the owner must still declare render(Map), and the scan must keep seeing files
// that import java.util.Properties — measured 2026-08-25: 14 importing main-source files,
// 0 `.store(` lines among them.

/**
 * [src] with comments and string-literal bodies blanked to spaces (newlines kept), so a scan
 * matches only code — a javadoc that merely *mentions* Properties.store() stays invisible.
 */
fun javaCodeOnly(src: String): String {
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
                while (i < stop) {
                    out.append(if (src[i] == '\n') '\n' else ' ')
                    i++
                }
            }
            src[i] == '"' || src[i] == '\'' -> {
                val quote = src[i]
                out.append(quote)
                i++
                while (i < src.length && src[i] != quote) {
                    if (src[i] == '\\' && i + 1 < src.length) {
                        out.append("  ")
                        i += 2
                    } else {
                        out.append(if (src[i] == '\n') '\n' else ' ')
                        i++
                    }
                }
                if (i < src.length) {
                    out.append(quote)
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

// Guard G30.
val checkPropertiesStoreOwner = registerGuard("checkPropertiesStoreOwner") {
    group = "verification"
    description = "Fail the build on a Properties.store() call in main sources (use DeterministicProperties.render)"
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/DeterministicProperties.java")
    val mainSources = fileTree(rootProject.layout.projectDirectory) {
        include("*/*/src/main/java/**/*.java")
        exclude("**/build/**")
    }
    inputs.file(owner).withPropertyName("deterministicProperties")
    inputs.files(mainSources).withPropertyName("mainSources")
    val stamp = layout.buildDirectory.file("guards/properties-store-owner.ok")
    outputs.file(stamp)
    doLast {
        if (!Regex("""String\s+render\s*\(""").containsMatchIn(owner.asFile.readText())) {
            throw GradleException("cc.jumpkick.host.DeterministicProperties no longer declares"
                    + " render(...), so this guard has lost the owner it points callers at. Restore"
                    + " the method or retire the guard deliberately.")
        }
        val importing = mutableListOf<File>()
        val hits = mutableListOf<String>()
        mainSources.files.sorted().forEach { f ->
            val code = javaCodeOnly(f.readText())
            if (!code.contains("import java.util.Properties;")) return@forEach
            importing.add(f)
            code.lines().forEachIndexed { idx, line ->
                if (line.contains(".store(")) {
                    hits.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}:${idx + 1}:"
                            + " ${line.trim()}")
                }
            }
        }
        if (importing.isEmpty()) {
            throw GradleException("The Properties-store guard found no main-source file importing"
                    + " java.util.Properties; it was measured against 14. The include pattern has"
                    + " stopped seeing the tree — fix it before trusting a green run.")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("Properties.store() writes a #-dated comment line in Hashtable"
                    + " order — a non-reproducible artifact. Render through"
                    + " cc.jumpkick.host.DeterministicProperties.render instead:\n"
                    + hits.joinToString("\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
