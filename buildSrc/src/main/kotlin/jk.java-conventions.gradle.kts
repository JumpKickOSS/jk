// SPDX-License-Identifier: Apache-2.0

import java.time.Duration

plugins {
    java
    `java-library`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
    // Keep in lock-step with jk's own default lint policy (cc.jumpkick.compile.JavacLint),
    // so `gradle build` and `jk build` surface the same javac warnings.
    options.compilerArgs.add("-Xlint:deprecation,unchecked")
}

// Reach the version catalog from a convention plugin without the buildSrc
// classpath hack: read it through VersionCatalogsExtension on the project.
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // JSpecify + Lombok: compile-time only; never on the runtime / native / fat-jar classpath.
    "compileOnly"(libs.findLibrary("jspecify").orElseThrow())
    "testCompileOnly"(libs.findLibrary("jspecify").orElseThrow())
    "compileOnly"(libs.findLibrary("lombok").orElseThrow())
    "annotationProcessor"(libs.findLibrary("lombok").orElseThrow())
    "testCompileOnly"(libs.findLibrary("lombok").orElseThrow())
    "testAnnotationProcessor"(libs.findLibrary("lombok").orElseThrow())
    "testImplementation"(libs.findLibrary("junit-jupiter").orElseThrow())
    "testImplementation"(libs.findLibrary("assertj-core").orElseThrow())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").orElseThrow())
}

// Two-tier tests (suite performance):
//   ./gradlew test              — unit/fast (exclude integration|slow|bench|network); target <5 min
//   ./gradlew integrationTest   — engine/e2e/network/worker suites
// Tag classes with @Tag("integration"), @Tag("slow"), @Tag("bench"), or @Tag("network").
val slowTags = listOf("integration", "slow", "bench", "network")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real product layout. JK_HOME is a single-tree
    // umbrella that mirrors the XDG shape: it relocates the five roots to
    // $JK_HOME/{bin,cache,config,data,state}, so the store is $JK_HOME/data/store and the
    // engine jar $JK_HOME/data/lib/jk-engine/. Managed JDKs default to the shared IntelliJ
    // root and are *not* relocated by JK_HOME — set JK_JDKS_DIR for hermetic JDK isolation.
    val testJkHome = layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath
    environment("JK_HOME", testJkHome)
    environment("JK_JDKS_DIR", "$testJkHome/jdks")
    // Same isolation for the Maven local repository (M2Dirs honours JK_M2_LOCAL):
    // tests that exercise the real fetch pipeline against a mock Maven server would
    // otherwise mirror their stub artifacts into the developer's real ~/.m2
    // overwriting genuine jars when a fixture reuses real coordinates (e.g. the
    // injected junit-jupiter test deps) and corrupting every later build on the
    // machine. The env var also reaches any jk subprocess a test forks.
    environment("JK_M2_LOCAL", layout.buildDirectory.dir("test-m2").get().asFile.absolutePath)
    // The resident engine queues a cache GC on its 12h feed tick — immediately on startup when
    // idle. In-process engines under test share the module's JK_HOME store, so that startup GC
    // races any test fixture staging prune-eligible files (.put-* temps) for its own explicit
    // prune request and eats them first (flaky counts). Explicit `jk cache prune` requests are
    // unaffected by this switch.
    environment("JK_AUTO_PRUNE", "false")
    // The embedded HTTP server is on by default (docs/http.md). Tests must not open listening
    // sockets as a side effect: engines spawned by integration tests would race the developer's
    // real engine (and each other, across parallel checkouts) for port 8910. The env var reaches
    // any jk subprocess a test forks; suites that exercise HTTP construct HttpEngineServer (or
    // pass an explicit JkHttpConfig) directly.
    environment("JK_HTTP_ENABLED", "false")
    // Fail hung methods instead of multi-hour freezes (override per-task if needed).
    systemProperty("junit.jupiter.execution.timeout.default", "120s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
    // Per-host rate-limit cooldowns are keyed by host, and every in-process HTTP test serves from
    // 127.0.0.1 — so without a throwaway store one test's simulated 429 cools down loopback for every
    // other test, and the record lands in the developer's real product layout.
    systemProperty("jk.http.cooldown.dir", layout.buildDirectory.dir("test-http-cooldown").get().asFile.absolutePath)
    // Default @TempDir (junit-*) follows java.io.tmpdir. Host /tmp is often a small-inode
    // tmpfs; leftover fixtures exhaust it and the next suite fails in TempDirFactory.
    // CLI UDS tests still bind under /tmp via JkTempDirFactory (path-length).
    val testTmp = layout.buildDirectory.dir("tmp")
    doFirst { testTmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTmp.get().asFile.absolutePath)
}

tasks.named<Test>("test") {
    description = "Unit/fast tests (excludes @Tag integration|slow|bench|network)"
    useJUnitPlatform {
        excludeTags(*slowTags.toTypedArray())
    }
    // Parallel forks for pure unit modules. CLI overrides to 1 for integration only.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // Suite budget: hang becomes a fail, not a 20+ min stall.
    timeout.set(Duration.ofMinutes(8))
}

// Same classpath/sources as test; different tag filter + longer budget.
val integrationTest by tasks.registering(Test::class) {
    description = "Integration/slow tests (@Tag integration|slow). Not part of check by default."
    group = "verification"
    val testSourceSet = sourceSets["test"]
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    useJUnitPlatform {
        includeTags("integration", "slow")
        excludeTags("bench")
    }
    shouldRunAfter(tasks.named("test"))
    systemProperty("junit.jupiter.execution.timeout.default", "300s")
    systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
    timeout.set(Duration.ofMinutes(45))
    // Inherit hermetic env from withType<Test> configureEach above.
}

// Optional: full verification including integration (nightly / merge gates).
tasks.register("checkAll") {
    group = "verification"
    description = "Unit test + integrationTest for this module"
    dependsOn(tasks.named("test"), integrationTest)
}

// ---------------------------------------------------------------------------
// Guard G1 (JK-2393): one owner for a JDK's launcher path.
//
// `cc.jumpkick.jdk.JdkFingerprint.java(javaHome)` / `.javac(javaHome)` are the only sanctioned way
// to name a JDK's `bin/java` — they append `.exe` on Windows. Hand-building the path as
// `<javaHome>/bin/java` silently drops that suffix and the fork is simply dead on Windows; five
// worker launches (Groovy, Kotlin, KSP, and `jk train`) shipped that way. Banned outright in
// production sources: there is no allowlist and no legitimate reason to open one.
//
// Scope is `src/main/java`. Test fixtures that lay down a POSIX-only fake JDK tree are writing
// files, not launching processes, so they are not in scope.
// ---------------------------------------------------------------------------
val checkNoHandBuiltJavaBinary by tasks.registering {
    group = "verification"
    description = "Fail the build on a hand-built <javaHome>/bin/java (use JdkFingerprint.java)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val stamp = layout.buildDirectory.file("guards/no-hand-built-java-binary.ok")
    outputs.file(stamp)
    doLast {
        val banned = listOf("""resolve("bin/java")""", """resolve("bin").resolve("java")""")
        // Whitespace-insensitive: the formatter wraps long resolve() chains across lines.
        val hits = mainJava.files.sorted().flatMap { f ->
            val squashed = f.readText().replace(Regex("\\s+"), "")
            banned.filter { squashed.contains(it) }.map { "${f.name}: $it" }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                    "A hand-built <javaHome>/bin/java drops the Windows `.exe` (JK-2393). "
                            + "Call cc.jumpkick.jdk.JdkFingerprint.java(javaHome) instead: "
                            + hits)
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoHandBuiltJavaBinary) }
tasks.named("jar") { dependsOn(checkNoHandBuiltJavaBinary) }

// ---------------------------------------------------------------------------
// Guard G10 (JK-2411): the size caps are a ratchet, not a suggestion.
//
// `code-as-art.md`'s size table had no mechanical check, and unenforced caps regrow: `EngineServer`
// finished its peel at 1,064 lines and was back over 1,200 eleven days later; the `JkManager` triad
// went 2,467 -> 3,414 in ten days, ending up larger than the 2,204-line god class it replaced. A
// shipped number is a floor, not a fact.
//
// Caps are per language, because the languages do not split at the same cost. A Java split costs an
// import; `clients/web` has no bundler, so a JS split costs a <script> tag and a load-order
// invariant no compiler checks. CSS is exempt outright — splitting a cascade on line count is a
// regression risk with no readability win. Soft caps (400 Java / 600 JS) are a review signal, not a
// gate; only the hard caps below are enforced.
//
// Three rules, all against the checked-in `size-baseline.txt` at the repo root (see its header):
//   1. A listed file may only shrink.
//   2. An unlisted file must be at or under its language's hard cap.
//   3. Every listed file claims the exception band, so every entry carries the invariant comment.
// A shrink passes and prints the tightened line to paste back — the ratchet never blocks progress.
//
// The count is `wc -l` (newline characters), the number the scoreboard and commit messages cite.
// Scope is production sources of Gradle modules that apply this plugin: `src/main/java`,
// `src/main/kotlin`, and `src/main/resources/**/*.{js,mjs}` (that last one is `clients/web`).
// ---------------------------------------------------------------------------
val fileSizeHardCaps = mapOf("java" to 800, "kt" to 800, "js" to 1200, "mjs" to 1200)

val checkFileSizeCaps by tasks.registering {
    group = "verification"
    description = "Fail the build when a file grows past size-baseline.txt or over its hard cap"
    val sources = fileTree(layout.projectDirectory) {
        include("src/main/java/**/*.java")
        include("src/main/kotlin/**/*.kt")
        include("src/main/resources/**/*.js")
        include("src/main/resources/**/*.mjs")
    }
    inputs.files(sources).withPropertyName("productionSources")
    val baseline = rootProject.layout.projectDirectory.file("size-baseline.txt")
    inputs.file(baseline).withPropertyName("sizeBaseline")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val caps = fileSizeHardCaps
    val stamp = layout.buildDirectory.file("guards/file-size-caps.ok")
    outputs.file(stamp)
    doLast {
        // An entry is `<lines>  <path>`; its invariant is the comment block directly above it, with
        // no blank line between. Whitespace-insensitive so the columns can stay aligned by eye.
        val listed = LinkedHashMap<String, Int>()
        val malformed = mutableListOf<String>()
        val undocumented = mutableListOf<String>()
        var documented = false
        baseline.asFile.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> documented = false
                line.startsWith("#") -> documented = true
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    val lines = if (parts.size == 2) parts[0].toIntOrNull() else null
                    if (lines == null) {
                        malformed.add("  size-baseline.txt:${i + 1}: expected `<lines>  <path>`, got `$line`")
                    } else {
                        listed[parts[1]] = lines
                        if (!documented) undocumented.add("  size-baseline.txt:${i + 1}: ${parts[1]}")
                    }
                    documented = false
                }
            }
        }

        val grew = mutableListOf<String>()
        val overCap = mutableListOf<String>()
        val loose = mutableListOf<String>()
        val present = mutableSetOf<String>()
        sources.files.sorted().forEach { f ->
            val hard = caps[f.extension.lowercase()] ?: return@forEach
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val lines = f.readText().count { it == '\n' }
            present.add(rel)
            val listedAt = listed[rel]
            when {
                listedAt == null && lines > hard ->
                        overCap.add("  $rel: $lines lines, hard cap $hard")
                listedAt != null && lines > listedAt ->
                        grew.add("  $rel: $lines lines, baseline $listedAt (+${lines - listedAt})")
                listedAt != null && lines < listedAt ->
                        loose.add("  %5d  %s   (was %d)".format(lines, rel, listedAt))
            }
        }
        listed.forEach { (rel, at) ->
            if (rel.startsWith(here) && rel !in present) loose.add("  (deleted) $rel   (was $at)")
        }

        val problems = mutableListOf<String>()
        if (grew.isNotEmpty()) {
            problems.add("A file in size-baseline.txt may only shrink (JK-2411). These grew:\n"
                    + grew.joinToString("\n"))
        }
        if (overCap.isNotEmpty()) {
            problems.add("Over the hard cap for their language and not in size-baseline.txt:\n"
                    + overCap.joinToString("\n")
                    + "\n  Split the file, or add it to size-baseline.txt with the invariant that"
                    + " must not be split.")
        }
        if (undocumented.isNotEmpty()) {
            problems.add("Every size-baseline.txt entry is a file claiming the exception band, and"
                    + " the exception band costs a stated invariant. Write the comment directly"
                    + " above the entry:\n" + undocumented.joinToString("\n"))
        }
        if (malformed.isNotEmpty()) {
            problems.add("size-baseline.txt is malformed:\n" + malformed.joinToString("\n"))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        if (loose.isNotEmpty()) {
            logger.lifecycle("size-baseline.txt is loose (these shrank — tighten it in this commit):")
            loose.forEach { logger.lifecycle(it) }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkFileSizeCaps) }
tasks.named("jar") { dependsOn(checkFileSizeCaps) }
