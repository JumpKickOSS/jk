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
 * Blank out comments — and, when [blankStrings], string/char/text-block literals too — preserving
 * length and line structure, so a name inside a `{@link}` or a JSONL fixture is not counted. A name
 * in prose is documentation; only code is in scope.
 *
 * The FQCN guard blanks literals: `cc.jumpkick.Foo` inside a fixture string is data. The JK-2409
 * guards keep them, because the thing they are hunting for (`"##JKT:"`, `"true"`, `"%02x"`) *is* a
 * literal — they need the javadoc that merely mentions it gone, and nothing more.
 */
fun blankNonCode(src: String, blankStrings: Boolean = true): String {
    val out = StringBuilder(src.length)
    var i = 0
    var line = false
    var block = false
    var text = false
    var str = false
    var chr = false
    // A literal's own characters: kept verbatim, or blanked to the same width.
    fun lit(s: String) = out.append(if (blankStrings) " ".repeat(s.length) else s)
    while (i < src.length) {
        val c = src[i]
        val two = if (i + 2 <= src.length) src.substring(i, i + 2) else ""
        val three = if (i + 3 <= src.length) src.substring(i, i + 3) else ""
        val escape = if (i + 2 <= src.length) src.substring(i, i + 2) else "$c "
        when {
            line -> if (c == '\n') { line = false; out.append(c) } else out.append(' ')
            block -> if (two == "*/") { block = false; out.append("  "); i += 2; continue }
                    else out.append(if (c == '\n') '\n' else ' ')
            text -> if (three == "\"\"\"") { text = false; lit(three); i += 3; continue }
                    else if (c == '\n') out.append('\n') else lit(c.toString())
            str -> {
                if (c == '\\') { lit(escape); i += 2; continue }
                if (c == '"') str = false
                lit(c.toString())
            }
            chr -> {
                if (c == '\\') { lit(escape); i += 2; continue }
                if (c == '\'') chr = false
                lit(c.toString())
            }
            two == "//" -> { line = true; out.append("  "); i += 2; continue }
            two == "/*" -> { block = true; out.append("  "); i += 2; continue }
            three == "\"\"\"" -> { text = true; lit(three); i += 3; continue }
            c == '"' -> { str = true; lit("\""); i += 1; continue }
            c == '\'' -> { chr = true; lit("'"); i += 1; continue }
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

// ---------------------------------------------------------------------------
// Guard plumbing shared by G3 / G5 / G7 / G9 (JK-2409).
//
// Two habits inherited from G1 (JK-2393), both load-bearing:
//   * match against code only — a banned literal named in javadoc is documentation, not a defect;
//   * squash whitespace first, so `jk format` wrapping a call across two lines cannot evade a
//     pattern written on one. A guard a re-flow can defeat stops working without anyone noticing.
// ---------------------------------------------------------------------------

/** The text a guard pattern is matched against: comments and imports gone, whitespace squashed. */
fun guardText(src: String): String =
        blankNonCode(src, blankStrings = false)
                .lineSequence()
                .filterNot {
                    val s = it.trimStart()
                    s.startsWith("import ") || s.startsWith("package ")
                }
                .joinToString("\n")
                .replace(Regex("\\s+"), "")

/** Occurrences of [pattern] in already-[guardText]-ed code. */
fun countIn(code: String, pattern: Regex): Int = pattern.findAll(code).count()

/**
 * Judge this module's per-file hit counts against an allowlist: `(grew, unlisted, loose)`.
 *
 * Growth and an unlisted file are failures. A file that shrank — or went clean — is *loose*, which
 * passes and prints the tightened entry to paste back, so the sweep that clears a guard is never
 * blocked by the guard itself. Only entries under [here], this module's repo-relative directory,
 * can be judged clean: the rest of the allowlist belongs to other modules' copies of the task.
 */
fun ratchetVerdict(
        hits: Map<String, Int>,
        allowed: Map<String, Int>,
        here: String
): Triple<List<String>, List<String>, List<String>> {
    val grew = mutableListOf<String>()
    val unlisted = mutableListOf<String>()
    val loose = mutableListOf<String>()
    hits.toSortedMap().forEach { (rel, n) ->
        val at = allowed[rel]
        when {
            at == null -> unlisted.add("  %5d  %s".format(n, rel))
            n > at -> grew.add("  $rel: $n sites, allowed $at (+${n - at})")
            n < at -> loose.add("  %5d  %s   (was %d)".format(n, rel, at))
        }
    }
    allowed.toSortedMap().forEach { (rel, at) ->
        if (rel.startsWith(here) && rel !in hits) loose.add("  (clean) $rel   (was $at)")
    }
    return Triple(grew, unlisted, loose)
}

// ---------------------------------------------------------------------------
// Guard G3 (JK-2409): one XML parser, one hardening posture.
//
// Defect it prevents: a new `DocumentBuilderFactory` that forgets an XXE flag and then parses
// third-party XML — an AAR's `res/values/*.xml` from any Maven artifact, a git dependency's
// `pom.xml` inside the resident engine. Round 3 found seven production sites at five hardening
// levels, two of them with no XXE flags at all; JK-2381 has since hardened those two, so the
// spread today is four levels, from one flag (`DeployCommand`) to six (`AndroidRepoFeed`).
//
// Two arms:
//   1. The other JAXP parser entry points are banned outright — zero sites today, no allowlist.
//      Without this arm the guard is one `SAXParserFactory` away from decoration.
//      `TransformerFactory` is deliberately absent: `ResourceMerger:141` uses it to *write* a DOM
//      out, which is not a parse.
//   2. `DocumentBuilderFactory` is a ratchet, because the owner it must funnel into does not exist
//      yet. JK-2421 adds `DomXml.parse` in `server/io` carrying `AndroidRepoFeed`'s six flags and
//      takes this list from seven files to one; until then a file not on it fails, and a file that
//      stops parsing has to come off it. Ownership, not site count, is the unit: a file either
//      builds its own parser or it does not, and reformatting one must not move the number.
//
// Checking the six flags instead of naming an owner is the obvious alternative and it is not
// enforceable: a text scan cannot tell which factory instance a `setFeature` call configures, so
// it would pass on six flags set on the wrong object. Ownership is checkable; posture is not.
//
// Scope is `src/main/java`: `PomExporterTest` parses a POM the test itself just wrote.
// ---------------------------------------------------------------------------

/** Production files still building their own `DocumentBuilderFactory`. Cleared by JK-2421. */
val xmlParserRatchet = setOf(
        "plugins/android/src/main/java/cc/jumpkick/android/DeployCommand.java",
        "plugins/android/src/main/java/cc/jumpkick/android/ResourceMerger.java",
        "server/engine/src/main/java/cc/jumpkick/runtime/SourceProjectBuilder.java",
        "server/io/src/main/java/cc/jumpkick/repo/MavenMetadata.java",
        "server/io/src/main/java/cc/jumpkick/repo/PomParser.java",
        "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java",
        "shared/toolchain-jdk/src/main/java/cc/jumpkick/androidsdk/AndroidRepoFeed.java")

val checkSingleXmlParserOwner by tasks.registering {
    group = "verification"
    description = "Fail the build on an XML parser outside the declared owner (XXE posture)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val allowed = xmlParserRatchet
    val stamp = layout.buildDirectory.file("guards/single-xml-parser-owner.ok")
    outputs.file(stamp)
    doLast {
        val bannedFactories = listOf("SAXParserFactory", "XMLInputFactory", "XMLReaderFactory")
        val banned = mutableListOf<String>()
        val parsers = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            bannedFactories.filter { code.contains(it) }.forEach { banned.add("  $rel: $it") }
            if (code.contains("DocumentBuilderFactory")) parsers.add(rel)
        }

        val problems = mutableListOf<String>()
        if (banned.isNotEmpty()) {
            problems.add("jk parses XML in one place, with one hardening posture (JK-2409)."
                    + " These name a JAXP parser that has no owner and no XXE flags at all:\n"
                    + banned.joinToString("\n"))
        }
        val unlisted = parsers.filterNot { it in allowed }
        if (unlisted.isNotEmpty()) {
            problems.add("A new DocumentBuilderFactory is a new XXE posture to get wrong"
                    + " (JK-2409). These are not on the ratchet:\n"
                    + unlisted.joinToString("\n") { "  $it" }
                    + "\n  Parse through the owner. Until JK-2421 lands `DomXml.parse`, copy"
                    + " AndroidRepoFeed's six flags verbatim and add the file here in the same"
                    + " commit, with the reason.")
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        val cleared = allowed.filter { it.startsWith(here) && it !in parsers }.sorted()
        if (cleared.isNotEmpty()) {
            logger.lifecycle("xmlParserRatchet is loose (these no longer parse — drop them in this"
                    + " commit):")
            cleared.forEach { logger.lifecycle("  $it") }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkSingleXmlParserOwner) }
tasks.named("jar") { dependsOn(checkSingleXmlParserOwner) }

// ---------------------------------------------------------------------------
// Guard G5 (JK-2409): a fork protocol's line prefix has exactly two ends.
//
// Defect it prevents: a third copy of `##JKT:` that drifts from the other two, or a rename that
// updates the writer and not the reader. Every `##JK<X>:` marker is one end of a parent/child
// protocol — the plugin declares it (`PluginManifest`, or `protocol-prefix` in `jk-plugin.toml`)
// and the engine reads it (`new PluginClient(...)`, or a `PREFIX` constant) — so a prefix named
// once is a dead protocol and a prefix named three times is a lockstep waiting to break. Neither
// end fails loudly at runtime: the child's protocol lines simply look like ordinary stdout.
//
// This is a pure lock, not a ratchet: all 16 live prefixes are already at exactly two. There is
// no allowlist and no reason to open one.
//
// Scope is tree-wide `<group>/<module>/src/main/java` plus each module's `jk-plugin.toml`, because
// the two ends live in different Gradle modules and no per-module scan can see a pair. Comments
// are blanked first, which is what keeps `##JKGIT:` — an invented prefix used as a javadoc example
// in three files, and a real protocol nowhere — out of the count.
// ---------------------------------------------------------------------------
val checkWireProtocolPrefixPairs by tasks.registering {
    group = "verification"
    description = "Fail the build when a ##JK*: protocol prefix is not named exactly twice"
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val protocolSources = fileTree(rootProject.layout.projectDirectory) {
        include("*/*/src/main/java/**/*.java")
        include("*/*/jk-plugin.toml")
        exclude("**/build/**", "**/target/**", "**/.git/**", "**/.gradle/**", "**/node_modules/**")
    }
    inputs.files(protocolSources).withPropertyName("protocolSources")
    val stamp = layout.buildDirectory.file("guards/wire-protocol-prefix-pairs.ok")
    outputs.file(stamp)
    doLast {
        val quotedPrefix = Regex("\"(##JK[A-Z]+:)\"")
        val sites = LinkedHashMap<String, MutableList<String>>()
        protocolSources.files.sorted().forEach { f ->
            val raw = f.readText()
            if (!raw.contains("##JK")) return@forEach
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val code =
                    if (f.name.endsWith(".toml"))
                            raw.lineSequence()
                                    .filterNot { it.trimStart().startsWith("#") }
                                    .joinToString("\n")
                    else guardText(raw)
            quotedPrefix.findAll(code).forEach { m ->
                sites.getOrPut(m.groupValues[1]) { mutableListOf() }.add(rel)
            }
        }
        val wrong = sites.toSortedMap().filter { (_, at) -> at.size != 2 || at.distinct().size != 2 }
        if (wrong.isNotEmpty()) {
            throw GradleException("A ##JK*: protocol prefix names exactly two ends — one writes it,"
                    + " one reads it (JK-2409). These do not:\n"
                    + wrong.entries.joinToString("\n") { (p, at) ->
                        "  $p: ${at.size} site(s)\n" + at.joinToString("\n") { "      $it" }
                    }
                    + "\n  One site means a dead protocol; three means a copy that will drift."
                    + " Reference the declaring constant instead of re-typing the literal.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkWireProtocolPrefixPairs) }
tasks.named("jar") { dependsOn(checkWireProtocolPrefixPairs) }

// ---------------------------------------------------------------------------
// Guard G7 (JK-2409): one truth set, and it lives in `EnvValues.parseBool`.
//
// Defect it prevents: `JK_FOO=yes` working in one reader and not the next. `EnvValues.parseBool`
// is the jk-wide truth set — `1/true/yes/on` against `0/false/no/off`, case-insensitively, trimmed
// — and every hand-rolled comparison below it accepts a different subset. `HttpProjectApi:611`
// takes four spellings, `FormatStamps:34` takes two, `JkConfigLoader:84` is case-insensitive where
// `JdkCatalogClient:268` is not. Users cannot see which reader they are talking to.
//
// The pattern covers `"true"`/`"1"` on either side of `equals` and `equalsIgnoreCase`. The narrow
// form the audit measured (17 sites, receiver-position, `equalsIgnoreCase` only) is defeated by
// flipping the argument order, which is not a defence; widening it costs four more allowlist
// entries and finds five more real duplications. The other truth-set members (`yes`/`on`/`off`)
// are out of scope until JK-2419's sweep lands, because banning them today would need a 30-file
// allowlist rather than a 22-file one.
//
// A ratchet, not a ban: the owner exists and is reachable, but 22 files still call past it and
// this ticket does not touch product source. JK-2419 clears the second section to nothing.
//
// Scope is `src/main/java`. A test asserting on the string `"true"` is a fixture, not a reader.
// ---------------------------------------------------------------------------

/** Files comparing against a boolean literal by hand. See G7 above for the two sections. */
val truthSetRatchet = mapOf(
        // Someone else's format, someone else's truth set — correct as written, permanent.
        // Maven POM XML: `<optional>` and `<activeByDefault>` are xs:boolean, `true` only.
        "server/io/src/main/java/cc/jumpkick/repo/PomParser.java" to 1,
        "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java" to 1,
        // The disco JDK catalog is JSON: `true`/`false`, never `yes`.
        "shared/toolchain-jdk/src/main/java/cc/jumpkick/jdk/JdkCatalogClient.java" to 2,
        // giter8 template booleans are `y`/`yes`/`true` — a different set on purpose.
        "server/engine/src/main/java/cc/jumpkick/giter8/Giter8Value.java" to 1,
        // jk's own on-disk memo row stores the bit as literal `1`, so `parts[2]` is not user input.
        // Its other two sites (`PreflightMemo:698`, one env read spelled twice) are JK-2419's.
        "server/engine/src/main/java/cc/jumpkick/runtime/PreflightMemo.java" to 3,

        // Pending JK-2419 — every one of these must become `EnvValues.parseBool(...)`.
        "clients/cli/src/main/java/cc/jumpkick/cli/theme/JkDarkTheme.java" to 2,
        "clients/cli/src/main/java/cc/jumpkick/cli/theme/Theme.java" to 2,
        "plugins/quarkus/src/main/java/cc/jumpkick/quarkus/QuarkusAugmentMain.java" to 2,
        "server/engine/src/main/java/cc/jumpkick/engine/HostWarmup.java" to 2,
        "server/engine/src/main/java/cc/jumpkick/engine/http/HttpProjectApi.java" to 2,
        "server/engine/src/main/java/cc/jumpkick/engine/http/mcp/McpHistoryViews.java" to 3,
        "server/engine/src/main/java/cc/jumpkick/runtime/BuildEta.java" to 2,
        "server/engine/src/main/java/cc/jumpkick/task/FormatStamps.java" to 2,
        "server/engine/src/main/java/cc/jumpkick/test/JupiterParallelDetect.java" to 2,
        "shared/core/src/main/java/cc/jumpkick/config/GlobalConfig.java" to 2,
        "shared/core/src/main/java/cc/jumpkick/config/JkCacheConfig.java" to 2,
        "shared/core/src/main/java/cc/jumpkick/config/JkConfigLoader.java" to 1,
        "shared/core/src/main/java/cc/jumpkick/config/JkEngineConfig.java" to 2,
        "shared/core/src/main/java/cc/jumpkick/config/ManifestTables.java" to 1,
        "shared/core/src/main/java/cc/jumpkick/config/NerdFontDetect.java" to 2,
        "shared/core/src/main/java/cc/jumpkick/resolve/ResolveProfile.java" to 2,
        "shared/jk-api/src/main/java/cc/jumpkick/model/Profiles.java" to 1)

val checkSingleTruthSet by tasks.registering {
    group = "verification"
    description = "Fail the build on a hand-rolled boolean truth set (use EnvValues.parseBool)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val allowed = truthSetRatchet
    val stamp = layout.buildDirectory.file("guards/single-truth-set.ok")
    outputs.file(stamp)
    doLast {
        // `"true".equals[IgnoreCase](x)` and `x.equals[IgnoreCase]("true")`, same for `"1"`.
        val handRolled = Regex(
                """"(?:true|1)"\.equals(?:IgnoreCase)?\(|\.equals(?:IgnoreCase)?\("(?:true|1)"\)""")
        val hits = LinkedHashMap<String, Int>()
        mainJava.files.sorted().forEach { f ->
            val n = countIn(guardText(f.readText()), handRolled)
            if (n > 0) hits[f.relativeTo(treeRoot).invariantSeparatorsPath] = n
        }
        val (grew, unlisted, loose) = ratchetVerdict(hits, allowed, here)

        val problems = mutableListOf<String>()
        if (unlisted.isNotEmpty()) {
            problems.add("jk has one boolean truth set and it is EnvValues.parseBool — 1/true/yes/on"
                    + " against 0/false/no/off, trimmed, case-insensitive (JK-2409). These compare"
                    + " by hand and are not on the ratchet:\n"
                    + unlisted.joinToString("\n")
                    + "\n  Call cc.jumpkick.config.EnvValues.parseBool(raw) (or .bool(env, name)"
                    + " for a JK_* variable). A reader of someone else's format — Maven's"
                    + " xs:boolean, giter8's y/yes — is an exemption, and says so here.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file on the truth-set ratchet may only shrink (JK-2409). These grew:\n"
                    + grew.joinToString("\n"))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        if (loose.isNotEmpty()) {
            logger.lifecycle("truthSetRatchet is loose (these shrank — tighten it in this commit):")
            loose.forEach { logger.lifecycle(it) }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkSingleTruthSet) }
tasks.named("jar") { dependsOn(checkSingleTruthSet) }

// ---------------------------------------------------------------------------
// Guard G9 (JK-2409): bytes become hex in one place, `Hashing.hex`.
//
// Defect it prevents: the next per-byte hex loop. `KotlinCompiler:174` allocates roughly 6,400
// throwaway `Formatter` objects per compile because `String.format("%02x", b)` builds one per
// byte; `AndroidCommand:131` omits the `& 0xff` mask that its neighbour remembers. Both are
// `Hashing.hex(byte[])`, which already exists in `shared/host`.
//
// A ratchet, not a ban: two of the three offenders are plugins, and `shared/plugin-sdk` depends on
// `:jsonl` alone, so `Hashing` is not on their classpath (round-3 corrections item 18). JK-2416
// resolves the reachability and clears this list.
//
// Scope is `src/main/java`; there are no hex loops in test sources today.
// ---------------------------------------------------------------------------

/** Files hand-encoding bytes as hex. See G9 above. */
val hexLoopRatchet = mapOf(
        // Not a digest: SigV4 percent-encodes a URI byte as UPPERCASE hex, which is what the AWS
        // canonical-request spec requires and what `Hashing.hex` deliberately does not produce.
        "server/io/src/main/java/cc/jumpkick/repo/s3/SigV4Signer.java" to 2,

        // Pending JK-2416 — each of these is `Hashing.hex(digest)`.
        "plugins/android/src/main/java/cc/jumpkick/android/AndroidCommand.java" to 1,
        "plugins/kotlin-compiler/src/main/java/cc/jumpkick/kotlin/compiler/KotlinCompiler.java" to 1,
        "shared/jk-api/src/main/java/cc/jumpkick/model/BuildIdentity.java" to 2)

val checkNoHandRolledHex by tasks.registering {
    group = "verification"
    description = "Fail the build on a per-byte hex loop (use Hashing.hex)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val allowed = hexLoopRatchet
    val stamp = layout.buildDirectory.file("guards/no-hand-rolled-hex.ok")
    outputs.file(stamp)
    doLast {
        // `%02x` in any format string (format / formatted / printf) and Character.forDigit.
        val hexLoop = Regex("""%02[xX]|Character\.forDigit\(""")
        val hits = LinkedHashMap<String, Int>()
        mainJava.files.sorted().forEach { f ->
            val n = countIn(guardText(f.readText()), hexLoop)
            if (n > 0) hits[f.relativeTo(treeRoot).invariantSeparatorsPath] = n
        }
        val (grew, unlisted, loose) = ratchetVerdict(hits, allowed, here)

        val problems = mutableListOf<String>()
        if (unlisted.isNotEmpty()) {
            problems.add("Bytes become hex in one place (JK-2409). These hand-encode and are not on"
                    + " the ratchet:\n"
                    + unlisted.joinToString("\n")
                    + "\n  Call cc.jumpkick.host.Hashing.hex(byte[]) — or sha256Hex, which does the"
                    + " digest too. Uppercase hex for a non-digest encoding is an exemption, and"
                    + " says so here.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file on the hex ratchet may only shrink (JK-2409). These grew:\n"
                    + grew.joinToString("\n"))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        if (loose.isNotEmpty()) {
            logger.lifecycle("hexLoopRatchet is loose (these shrank — tighten it in this commit):")
            loose.forEach { logger.lifecycle(it) }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoHandRolledHex) }
tasks.named("jar") { dependsOn(checkNoHandRolledHex) }
