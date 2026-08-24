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

// ---------------------------------------------------------------------------
// Guard G11 (JK-2412): a fully-qualified class name in the body of a file is a ratchet, not a rule.
//
// `code-as-art.md`'s House rules say "no FQCN except collisions", and until JK-2408 that rule was
// delegated to a no-op: `jk format`'s `optimize-imports` pass built its OpenRewrite parser with no
// classpath, so it resolved nothing and shortened nothing. Twelve module audits each filed the same
// finding. The tree carried 4,306 package-qualified references across 622 files.
//
// The sweep removed 3,428 of them. The 878 that remain are not style choices; they are three
// measured limits, and `fqcn-baseline.txt` names which files each one holds:
//
//   1. 108 of 2,063 files fail OpenRewrite's own print-idempotence check — its Javadoc printer
//      mangles a wrapped `@param` continuation line, so the parser returns a ParseError and the
//      file is left untouched. Writing the mangled print back is the alternative, so this check
//      is right to exist and these files simply cannot be shortened by the formatter.
//   2. The recipe rewrites a `J.FieldAccess` only when it resolves to a top-level class, so a
//      static member (`ValueLayout.JAVA_INT`) or an annotation (`@NullMarked`) is never shortened.
//   3. Dependency jars are deliberately off the format classpath (JK-2408 measured 4.6x cost and a
//      worker SIGSEGV for 1.4% more shortenings), so a third-party type cannot be resolved.
//
// The guard therefore counts EVERY package-qualified reference, not only the kinds the recipe can
// fix. Narrowing it to type references would leave the most common residual shape — a qualified
// static call — permanently unguarded, and hand-shortening one is a two-line edit that `jk format`
// will never undo. A genuine collision keeps its FQCN and is listed under `## collisions` with the
// name it collides with.
//
// Three rules, all against the checked-in `fqcn-baseline.txt` at the repo root (see its header):
//   1. A listed file may only shrink.
//   2. An unlisted file must have zero.
//   3. Every entry sits under a `##` section that states why, and a collision names its collision.
// A shrink passes and prints the tightened line to paste back — the ratchet never blocks progress.
//
// Scope is `src/main/java` and `src/test/java`. Unlike the size caps, tests get no discount: an
// FQCN costs the same to read either side, and `jk format` treats both alike.
// ---------------------------------------------------------------------------

// A package-qualified reference: two or more all-lowercase dot-separated segments followed by an
// UpperCamel identifier. Two segments is the floor because `cc.jumpkick.Foo` has exactly two, and
// requiring two is what keeps `builder.config.Value` — a field chain, not a package — out.
val fqcnPattern = Regex("""(?<![\w.$])(?:[a-z][a-z0-9_]*\.){2,}[A-Z][A-Za-z0-9_]*""")

/**
 * Blank out comments and string/char/text-block literals, preserving length and line structure, so
 * a name inside a `{@link}` or a JSONL fixture is not counted. An FQCN in prose is documentation;
 * only code is in scope.
 */
fun blankNonCode(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    var line = false
    var block = false
    var text = false
    var str = false
    var chr = false
    while (i < src.length) {
        val c = src[i]
        val two = if (i + 2 <= src.length) src.substring(i, i + 2) else ""
        val three = if (i + 3 <= src.length) src.substring(i, i + 3) else ""
        when {
            line -> if (c == '\n') { line = false; out.append(c) } else out.append(' ')
            block -> if (two == "*/") { block = false; out.append("  "); i += 2; continue }
                    else out.append(if (c == '\n') '\n' else ' ')
            text -> if (three == "\"\"\"") { text = false; out.append("   "); i += 3; continue }
                    else out.append(if (c == '\n') '\n' else ' ')
            str -> {
                if (c == '\\') { out.append("  "); i += 2; continue }
                if (c == '"') str = false
                out.append(' ')
            }
            chr -> {
                if (c == '\\') { out.append("  "); i += 2; continue }
                if (c == '\'') chr = false
                out.append(' ')
            }
            two == "//" -> { line = true; out.append("  "); i += 2; continue }
            two == "/*" -> { block = true; out.append("  "); i += 2; continue }
            three == "\"\"\"" -> { text = true; out.append("   "); i += 3; continue }
            c == '"' -> { str = true; out.append(' '); i += 1; continue }
            c == '\'' -> { chr = true; out.append(' '); i += 1; continue }
            else -> out.append(c)
        }
        i++
    }
    return out.toString()
}

/** Package-qualified references in a Java source, excluding its own `import`/`package` lines. */
fun countFqcns(src: String): Int =
        blankNonCode(src).lineSequence().sumOf { raw ->
            val s = raw.trimStart()
            if (s.startsWith("import ") || s.startsWith("package ")) 0
            else fqcnPattern.findAll(raw).count()
        }

val checkNoFqcn by tasks.registering {
    group = "verification"
    description = "Fail the build when a file gains a fully-qualified class name (fqcn-baseline.txt)"
    val sources = fileTree(layout.projectDirectory) {
        include("src/main/java/**/*.java")
        include("src/test/java/**/*.java")
    }
    inputs.files(sources).withPropertyName("javaSources")
    val baseline = rootProject.layout.projectDirectory.file("fqcn-baseline.txt")
    inputs.file(baseline).withPropertyName("fqcnBaseline")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val stamp = layout.buildDirectory.file("guards/no-fqcn.ok")
    outputs.file(stamp)
    doLast {
        // `## <section>` opens a reason; `#` lines are that reason's prose, or — inside the
        // collisions section — the one entry's named collision. An entry is `<count>  <path>`.
        val listed = LinkedHashMap<String, Int>()
        val malformed = mutableListOf<String>()
        val unexplained = mutableListOf<String>()
        var section: String? = null
        var lastWasComment = false
        baseline.asFile.readLines().forEachIndexed { i, raw ->
            val line = raw.trim()
            when {
                line.startsWith("##") -> { section = line.removePrefix("##").trim(); lastWasComment = true }
                line.startsWith("#") -> lastWasComment = true
                line.isEmpty() -> {}
                else -> {
                    val parts = line.split(Regex("\\s+"))
                    val count = if (parts.size == 2) parts[0].toIntOrNull() else null
                    when {
                        count == null ->
                                malformed.add("  fqcn-baseline.txt:${i + 1}: expected `<count>  <path>`, got `$line`")
                        section == null ->
                                unexplained.add("  fqcn-baseline.txt:${i + 1}: ${parts[1]} — no `## <reason>` section above it")
                        section == "collisions" && !lastWasComment ->
                                unexplained.add("  fqcn-baseline.txt:${i + 1}: ${parts[1]} — a collision must name what it collides with")
                        else -> listed[parts[1]] = count
                    }
                    lastWasComment = false
                }
            }
        }

        val grew = mutableListOf<String>()
        val unlisted = mutableListOf<String>()
        val loose = mutableListOf<String>()
        val present = mutableSetOf<String>()
        sources.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val n = countFqcns(f.readText())
            present.add(rel)
            val at = listed[rel]
            when {
                at == null && n > 0 -> unlisted.add("  %5d  %s".format(n, rel))
                at != null && n > at -> grew.add("  $rel: $n FQCNs, baseline $at (+${n - at})")
                at != null && n < at -> loose.add("  %5d  %s   (was %d)".format(n, rel, at))
            }
        }
        listed.forEach { (rel, at) ->
            if (rel.startsWith(here) && rel !in present) loose.add("  (deleted) $rel   (was $at)")
        }

        val problems = mutableListOf<String>()
        if (unlisted.isNotEmpty()) {
            problems.add("A fully-qualified class name in a method body is banned (JK-2412) —"
                    + " import the type. These files are not in fqcn-baseline.txt:\n"
                    + unlisted.joinToString("\n")
                    + "\n  `jk format` shortens type references for you. A static member, an"
                    + " annotation or a third-party type it cannot reach is a hand edit."
                    + " A genuine collision goes under `## collisions` with the name it collides with.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file in fqcn-baseline.txt may only shrink (JK-2412). These grew:\n"
                    + grew.joinToString("\n"))
        }
        if (unexplained.isNotEmpty()) {
            problems.add("Every fqcn-baseline.txt entry states why the FQCN survives:\n"
                    + unexplained.joinToString("\n"))
        }
        if (malformed.isNotEmpty()) {
            problems.add("fqcn-baseline.txt is malformed:\n" + malformed.joinToString("\n"))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        if (loose.isNotEmpty()) {
            logger.lifecycle("fqcn-baseline.txt is loose (these shrank — tighten it in this commit):")
            loose.forEach { logger.lifecycle(it) }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoFqcn) }
tasks.named("jar") { dependsOn(checkNoFqcn) }
