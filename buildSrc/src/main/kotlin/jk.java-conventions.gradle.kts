// SPDX-License-Identifier: Apache-2.0

import java.io.File
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

// Gradle's java-test-fixtures plugin defaults to src/testFixtures/java. jk's fixtures live in
// src/fixtures/java — no camelCase directory names.
pluginManager.withPlugin("java-test-fixtures") {
    sourceSets.named("testFixtures") { java.setSrcDirs(listOf("src/fixtures/java")) }
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

// Four-tier tests, one tier per tag (docs/contributors/test-suite-tiers.md):
//   ./gradlew test              — unit/fast, untagged only; target <5 min. The PR gate.
//   ./gradlew integrationTest   — @Tag(integration|slow): engine/e2e/worker suites. In checkAll.
//   ./gradlew networkTest       — @Tag(network): talks to a real remote. Nightly, NOT in checkAll.
//   ./gradlew benchTest         — @Tag(bench): microbench, asserts no deltas. On demand.
//
// The filters below are GENERATED from `TestTiers` (buildSrc/src/main/kotlin/TestTiers.kt), which
// is the single owner of the tag→task routing. Two half-tables here is what one sweep removed: `test`
// excluded four tags and `integrationTest` re-included two, so `@Tag("bench")` was run by no task
// at all. Guard G23 re-derives the partition from the same object, so the tiers and the guard
// cannot drift.
val slowTags = TestTiers.slowTags

fun TestTier.applyTo(spec: org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions) {
    if (include.isNotEmpty()) spec.includeTags(*include.toTypedArray())
    if (exclude.isNotEmpty()) spec.excludeTags(*exclude.toTypedArray())
}

fun tier(name: String): TestTier = TestTiers.all.first { it.task == name }

// ---------------------------------------------------------------------------
// Test tasks that exec a tool this build does not produce, and the tool each one runs.
//
// One table rather than a copy per module, for the reason the hole exists at all: it is generic
// (any test that shells out to a binary jk does not build) and it was fixed once, here. Adding
// `protoc` or `bundletool` is a line in this map, not a new pattern in a module script. The map is
// keyed `<project path>:<task name>` because the answer is per *tier*, not per module — only
// `:engine:integrationTest` forks git for the backend-parity matrix, but `:engine:test` reaches it
// too through `GitFetcherBackendSelectionTest`, so both are listed rather than assumed.
//
// Measured 2026-08-24 by scanning every `src/test/**` for `ProcessBuilder`/`Runtime.exec` and
// reading each call site: 21 test files fork a process, 19 of them a JDK tool out of
// `System.getProperty("java.home")` (`java`, `jshell`, `keytool`) which Gradle already tracks as
// the Test task's `javaLauncher`. The two that reach outside the JDK are the ones below.
//   * `:web` — `WebClientJsTest` runs `node --test` over `src/test/js`.
//   * `:engine` — `GitCliExtension.detect()` runs `git --version`, and the CLI arm of the
//     backend-parity matrix simply vanishes when it comes back empty (`GitBackendsTestSupport`).
//     A git that appears or disappears must therefore re-run the suite, which is exactly what an
//     identity of "absent" vs a version line buys.
// ---------------------------------------------------------------------------
val externalTestRuntimes: Map<String, List<Pair<String, String?>>> = mapOf(
        ":web:test" to listOf("node" to null),
        ":engine:test" to listOf("git" to "JK_GIT"),
        ":engine:integrationTest" to listOf("git" to "JK_GIT"))

/** True when the files under [root] total more than [capBytes]; stops counting at the cap. */
fun treeExceeds(root: File, capBytes: Long): Boolean {
    if (!root.isDirectory) return false
    var total = 0L
    root.walkTopDown().forEach { f ->
        if (f.isFile) {
            total += f.length()
            if (total > capBytes) return true
        }
    }
    return false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // Isolate tests from the developer's real product layout. JK_HOME relocates the whole tree
    // — $JK_HOME/{bin,cache,config,creds,lib,state,store} — so the store is $JK_HOME/store and the
    // engine jar $JK_HOME/lib/jk-engine/. Managed JDKs default to the shared IntelliJ root and are
    // *not* relocated by JK_HOME — set JK_JDKS_DIR for hermetic JDK isolation.
    val testJkHome = layout.buildDirectory.dir("test-jk-home").get().asFile.absolutePath
    environment("JK_HOME", testJkHome)
    environment("JK_JDKS_DIR", "$testJkHome/jdks")
    // Same isolation for the Maven local repository (M2Dirs honours JK_M2_LOCAL):
    // tests that exercise the real fetch pipeline against a mock Maven server would
    // otherwise mirror their stub artifacts into the developer's real ~/.m2
    // overwriting genuine jars when a fixture reuses real coordinates (e.g. the
    // injected junit-jupiter test deps) and corrupting every later build on the
    // machine. The env var also reaches any jk subprocess a test forks.
    val testM2 = layout.buildDirectory.dir("test-m2").get().asFile.absolutePath
    environment("JK_M2_LOCAL", testM2)
    // The warm home is the feature — two suites deliberately prime the store for the rest — but
    // nothing but `clean` ever removed it, and the CAS plus the store accumulate every fixture
    // blob ever fetched: measured at 744 MB under clients/cli and 1,013 MB under server/engine
    // before this bound existed. So the home is wiped when it turns a week old or outgrows 1 GiB,
    // and every suite already tolerates the resulting cold start (a fresh checkout is one). This
    // runs in the task action, before any test JVM forks; test-m2 is undeclared residue of the
    // same kind and goes in the same sweep.
    doFirst {
        val home = File(testJkHome)
        val stamp = File(home, ".wiped-at")
        val weekMs = 7L * 24 * 60 * 60 * 1000
        val capBytes = 1L shl 30
        val stale = stamp.isFile && System.currentTimeMillis() - stamp.lastModified() > weekMs
        if (stale || treeExceeds(home, capBytes)) {
            home.deleteRecursively()
            File(testM2).deleteRecursively()
        }
        if (!stamp.isFile) {
            home.mkdirs()
            stamp.writeText("Sweep stamp for the warm test home; see jk.java-conventions.\n")
        }
    }
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
    // ServiceLoader-registered JUnit extensions are OFF unless a tier says otherwise, and every
    // tier is now told rather than left to inherit. JUnit's own default is false, so
    // this changes no behaviour today — what it changes is that a NEW tier cannot pick up a
    // different answer by accident. Two modules register an extension this switch controls:
    //   * `:cli` — `EngineTestExtension` (materialize the jar, stop the engine after every class).
    //     `:cli:integrationTest` turns it back on; the unit tier must not spawn engines, so the
    //     difference between those two tiers is deliberate and lives in clients/cli/build.gradle.kts.
    //   * `:resolver` — `ResolveProcessCacheExtension` (drop process-wide resolve memos between
    //     tests). Wanted in every tier, so server/resolver/build.gradle.kts turns it on for all of
    //     them. It used to be a `junit-platform.properties` on the test classpath, which is a
    //     second mechanism for the same policy and invisible to anyone reading the build.
    systemProperty("junit.jupiter.extensions.autodetection.enabled", "false")
    // Opt-in class shuffle, so order-dependence is FOUND rather than waited for.
    //
    // Off by default and deliberately not in any gate: a gate that fails on an unlucky seed is a
    // gate people learn to re-run, which is the opposite of the trust the tier report exists to build. Use
    // it when hunting a suspected order-dependent failure.
    //
    //   ./gradlew :cli:integrationTest -Pjk.test.shuffle           # random seed, printed
    //   ./gradlew :cli:integrationTest -Pjk.test.shuffle=12345     # replay that seed
    //
    // The seed is printed on every run, failing or not, because a seed you only learn about when
    // something breaks cannot be used to prove something is fixed.
    val shuffle = providers.gradleProperty("jk.test.shuffle").orNull
    if (shuffle != null) {
        val seed = if (shuffle.isBlank() || shuffle == "true") System.nanoTime().toString() else shuffle
        systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$Random")
        systemProperty("junit.jupiter.execution.order.random.seed", seed)
        doFirst {
            logger.lifecycle("jk: $path shuffling test classes serially, seed=$seed"
                    + "  (replay with -Pjk.test.shuffle=$seed)")
        }
    }
    // The external runtime this tier execs is an input; see `externalTestRuntimes`.
    val declared = externalTestRuntimes["${project.path}:$name"].orEmpty()
    if (declared.isNotEmpty()) {
        val probeCache = rootProject.layout.buildDirectory.dir("external-tool-probe").get().asFile
        // Through the provider API, not System.getenv: inside a long-lived daemon the latter is the
        // environment the daemon *started* with, so pointing PATH at a different node would not be
        // noticed until some later build restarted it. Same reason clients/web reads JK_WEB_JS_SKIP
        // this way.
        val searchPath = providers.environmentVariable("PATH").getOrElse("")
        declared.forEach { (tool, envOverride) ->
            val override = envOverride?.let { providers.environmentVariable(it).orNull }
            inputs.property(
                    "externalRuntime.$tool",
                    ExternalToolVersions.identity(probeCache, tool, searchPath, override))
        }
    }
}

tasks.named<Test>("test") {
    description = "Unit/fast tests (excludes @Tag " + slowTags.joinToString("|") + ")"
    useJUnitPlatform { tier(TestTiers.UNIT).applyTo(this) }
    // Parallel forks for pure unit modules. CLI overrides to 1 for integration only.
    maxParallelForks = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
    // Suite budget: hang becomes a fail, not a 20+ min stall.
    timeout.set(Duration.ofMinutes(8))
}

/** A slow tier: same classpath and sources as `test`, its own tag filter and budget. */
fun slowTier(tierName: String, budget: Duration, describe: String) {
    tasks.register<Test>(tierName) {
        description = describe
        group = "verification"
        val testSourceSet = sourceSets["test"]
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        useJUnitPlatform { tier(tierName).applyTo(this) }
        shouldRunAfter(tasks.named("test"))
        systemProperty("junit.jupiter.execution.timeout.default", "300s")
        systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")
        timeout.set(budget)
        // Inherit hermetic env from withType<Test> configureEach above.
    }
}

slowTier(
        TestTiers.INTEGRATION,
        Duration.ofMinutes(45),
        "Integration tests (@Tag integration). Part of checkAll, not of check.")

// @Tag("slow") is off the gate. It was 426s of integrationTest's 1419s — 30% of the
// merge bar for 28 tests, 15s each — and what it asserts (does an Android / Grails / Scala / KSP /
// Protobuf project still build end to end) moves when a plugin or a toolchain does, not when the
// change under review does. Nightly is where a suite like that belongs; the tag already meant this
// before the table routed it anywhere else.
slowTier(
        TestTiers.SLOW,
        Duration.ofMinutes(30),
        "Framework/language e2e suites (@Tag slow). Nightly and on demand — never in checkAll.")

// @Tag("network") used to be excluded by `test` and re-included by nothing, so the only class
// carrying it reached the gate through its second tag (`slow`) — which meant the documented
// pre-merge bar hit Maven Central, and Sonatype's per-IP quota decided whether a PR was green
//. Its own task, off the gate, is the honest answer: the tag runs, and it runs nightly.
slowTier(
        TestTiers.NETWORK,
        Duration.ofMinutes(30),
        "Tests that talk to a real remote (@Tag network). Nightly only — never in checkAll.")

// @Tag("bench") is off the gate for a different reason: a microbench prints medians and asserts
// nothing about deltas, so gating on it would gate on CI noise. That intent was once
// spelled as an exclusion in both tasks, which is indistinguishable from an accident and left
// ForkedJavacAotBenchTest run by nothing. Now it has a task, and the reason is written down.
slowTier(
        TestTiers.BENCH,
        Duration.ofMinutes(30),
        "Microbenchmarks (@Tag bench). Prints medians, gates nothing — run on demand.")

// Optional: full verification including integration (nightly / merge gates). Deliberately NOT
// networkTest or benchTest — see TestTiers.NETWORK / TestTiers.BENCH for why each is off the gate.
tasks.register("checkAll") {
    group = "verification"
    description = "Unit test + integrationTest for this module"
    dependsOn(TestTiers.gating.map { tasks.named(it) })
}

// ---------------------------------------------------------------------------
// Guard G1: one owner for a JDK's launcher path.
//
// `cc.jumpkick.jdk.JdkFingerprint.java(javaHome)` / `.javac(javaHome)` / `.tool(javaHome, name)`
// are the only sanctioned way to name a JDK's `bin/java` — they append `.exe` on Windows.
// Hand-building the path silently drops that suffix and the fork is simply dead on Windows.
//
// WHAT THIS GUARD WAS MEASURED AGAINST — 2026-08-24, whole tree, `src/main/java` only:
//
//   * the two spellings it banned until now, `resolve("bin/java")` and
//     `resolve("bin").resolve("java")`:                                   0 files, 0 sites
//   * the shapes it bans as of this commit:                              17 files, 22 sites
//     of which `Path.of(<home>, "bin", "java")` — no `.exe`, shipped broken on Windows:  4
//   * after the sweep:                                                    0 files, 0 sites
//
// Read the first two numbers together. G1 was green for two months while 22 hand-rolled sites and
// four live Windows bugs sat in the tree, because its ban list described a spelling nobody used.
// A green guard is evidence about the guard, not about the tree — so every guard states the count
// it was measured against, and this one is proved to fail before it is believed (a later pass re-added
// a `Path.of(System.getProperty("java.home"), "bin", "java")` and watched `check` go red).
//
// The ban list is DERIVED, not re-typed: the launcher names come from the owner's own
// `public static Path <name>(Path javaHome)` shorthands and the Windows suffix from its
// `toolName`, so teaching the owner a third launcher bans hand-building that one the same minute.
// Five shapes per name — `bin/<n>`, `resolve("bin").resolve("<n>")` with and without the suffix,
// the `Path.of(…, "bin", "<n>")` positional form, and the `win ? "<n>.exe" : "<n>"` ternary that
// re-derives the suffix rule wherever it is written. There is no allowlist: the owner is on the
// host leaf, so every module in the tree can reach it.
//
// Scope is `src/main/java`, read through `guardText` — a `bin/java` in a javadoc line is prose.
// Test fixtures that lay down a POSIX-only fake JDK tree are writing files, not launching
// processes, so they are not in scope either.
// ---------------------------------------------------------------------------
val checkNoHandBuiltJavaBinary by tasks.registering {
    group = "verification"
    description = "Fail the build on a hand-built <javaHome>/bin/java (use JdkFingerprint.java)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val launcherOwner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/jdk/JdkFingerprint.java")
    inputs.file(launcherOwner).withPropertyName("launcherOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-hand-built-java-binary.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = launcherOwner.asFile
        val ownerText = ownerFile.readText()
        // The launcher vocabulary, straight out of the owner's named shorthands.
        val names = Regex("""public static Path (\w+)\(Path javaHome\)""")
                .findAll(ownerText)
                .map { it.groupValues[1] }
                .toList()
        if (names.isEmpty()) {
            throw GradleException("cc.jumpkick.jdk.JdkFingerprint declares no `public static Path"
                    + " <name>(Path javaHome)` shorthand, so the launcher guard has lost the ban"
                    + " list it reads. Restore one or retire this guard deliberately.")
        }
        // …and the Windows suffix, straight out of the owner's toolName.
        val suffix = Regex("""tool \+ "([^"]+)"""").find(ownerText)?.groupValues?.get(1)
                ?: throw GradleException("cc.jumpkick.jdk.JdkFingerprint.toolName no longer appends a"
                        + " literal suffix, so the launcher guard cannot see the rule it enforces."
                        + " Restore it or retire this guard deliberately.")
        val banned = names.flatMap { n ->
            listOf(
                    """resolve("bin/$n")""",
                    """resolve("bin").resolve("$n")""",
                    """resolve("bin").resolve("$n$suffix")""",
                    ""","bin","$n"""",
                    ""","bin","$n$suffix"""",
                    """"$n$suffix":"$n"""",
                    """"$n":"$n$suffix"""")
        }

        val hits = mainJava.files.sorted().filter { it != ownerFile }.flatMap { f ->
            // guardText squashes whitespace between literals (the formatter wraps long resolve()
            // chains) and blanks comments (a javadoc that spells the path is documentation).
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            banned.filter { code.contains(it) }.map { "  $rel: $it" }
        }
        if (hits.isNotEmpty()) {
            throw GradleException(
                    "A hand-built <javaHome>/bin/java drops the Windows `.exe` and the fork is dead"
                            + " there. ${hits.size} site(s):\n"
                            + hits.joinToString("\n")
                            + "\n  Call cc.jumpkick.jdk.JdkFingerprint.java(javaHome),"
                            + " .javac(javaHome), or .tool(javaHome, name) for any other JDK"
                            + " launcher. It is on the :host leaf, so every module reaches it —"
                            + " including a plugin worker, which is why the primitive moved there."
                            + " A launcher that is NOT a JDK tool under <home>/bin (mvn, gradle,"
                            + " kotlinc, native-image) is a different vocabulary and is not in scope.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoHandBuiltJavaBinary) }
tasks.named("jar") { dependsOn(checkNoHandBuiltJavaBinary) }

// ---------------------------------------------------------------------------
// Guard G10: the size caps are a ratchet, not a suggestion.
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
// The count is code lines (CodeLines.count): comments, blanks, and package/import lines do not
// count. A trailing comment on a statement still counts that line. String contents count. Scope
// includes test sources: `src/main/java`, `src/main/kotlin`, `src/test/java`,
// `src/test/kotlin`, `src/fixtures/java`, and `src/{main,test}/**/*.{js,mjs}`.
// ---------------------------------------------------------------------------
val fileSizeHardCaps = mapOf("java" to 800, "kt" to 800, "js" to 1200, "mjs" to 1200)

val checkFileSizeCaps by tasks.registering {
    group = "verification"
    description = "Fail the build when a file grows past size-baseline.txt or over its hard cap"
    // Test sources are capped too, and by the same numbers. The caps in the charter are
    // per LANGUAGE, and `.java` is `.java` — but this task used to scan `src/main` only, so a rule
    // stated for an extension was enforced for a directory. What that hole cost was measurable: the
    // single largest file in the tree was `JkBuildParserTest` at 2,334 lines, 2.9x the hard cap for
    // its own language and 5.6x its 416-line subject, and no guard could see it. Nor could the
    // doc/guard parity arm below, which compares extensions and is blind to directory scope.
    //
    // The counter-argument — that splitting a suite can duplicate a fixture, and duplication is
    // this tree's actual defect vector — argues for the exception band, not for a second number.
    // The band already exists: `size-baseline.txt` plus a stated invariant, reviewable as a diff.
    // Measured when this landed: 975 Java test files, p50 104 lines, p99 638, and every one of them
    // at or under 800.
    val sources = fileTree(layout.projectDirectory) {
        include("src/main/java/**/*.java")
        include("src/main/kotlin/**/*.kt")
        include("src/main/resources/**/*.js")
        include("src/main/resources/**/*.mjs")
        include("src/test/java/**/*.java")
        include("src/test/kotlin/**/*.kt")
        include("src/fixtures/java/**/*.java")
        include("src/test/js/**/*.js")
        include("src/test/js/**/*.mjs")
    }
    inputs.files(sources).withPropertyName("cappedSources")
    val baseline = rootProject.layout.projectDirectory.file("size-baseline.txt")
    inputs.file(baseline).withPropertyName("sizeBaseline")
    // The caps are a house rule before they are a task, and the rule is written down in
    // code-as-art.md. Both are read here so the doc and the guard cannot drift.
    val charter = rootProject.layout.projectDirectory.file("docs/contributors/code-as-art.md")
    inputs.file(charter).withPropertyName("charter")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val caps = fileSizeHardCaps
    val stamp = layout.buildDirectory.file("guards/file-size-caps.ok")
    outputs.file(stamp)
    doLast {
        // Doc/guard parity first: a cap the charter states and the task does not enforce is worse
        // than no cap at all, because a reader trusts the table. The charter's Size table declares
        // its own extensions, so this reads ext -> hard cap straight out of it; an em dash means
        // "exempt", i.e. the extension must be absent from `caps` entirely.
        val row = Regex("^\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|([^|]*)\\|")
        val docCaps = LinkedHashMap<String, Int?>()
        var inTable = false
        charter.asFile.readLines().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("| Language | Extensions |") -> inTable = true
                inTable && !line.startsWith("|") -> inTable = false
                inTable && !line.startsWith("|---") -> {
                    val m = row.find(line)
                    if (m != null) {
                        val hardCell = m.groupValues[4].trim()
                        val hard = if (hardCell == "\u2014") null
                                else hardCell.replace(",", "").toIntOrNull()
                        Regex("`\\.([a-z]+)`").findAll(m.groupValues[2])
                                .forEach { docCaps[it.groupValues[1]] = hard }
                    }
                }
            }
        }
        // Same contract for the charter's own Contents list: a heading added without its entry, or
        // an entry whose heading is gone, fails here. Navigation is a fact about the file, so it is
        // derived and checked rather than maintained by hand.
        val charterLines = charter.asFile.readLines()
        val slug = { t: String ->
            t.replace("`", "").lowercase().filter { it.isLetterOrDigit() || it == ' ' || it == '-' }
                    .trim().replace(' ', '-')
        }
        val tocSlugs = charterLines.mapNotNull {
            Regex("^ *- \\[(.+)]\\(#([a-z0-9-]+)\\)$").find(it.trimEnd())?.groupValues?.get(2)
        }
        val headings = charterLines.mapNotNull {
            Regex("^(##|###) (.+)$").find(it)?.groupValues?.get(2)
        }.filter { it != "Contents" }.map(slug)
        val tocDrift = mutableListOf<String>()
        headings.filterNot { it in tocSlugs }.forEach { tocDrift.add("  missing from Contents: #$it") }
        tocSlugs.filterNot { it in headings }.forEach { tocDrift.add("  in Contents, no such heading: #$it") }
        if (tocDrift.isEmpty() && tocSlugs != headings) {
            tocDrift.add("  Contents lists every heading but in a different order")
        }
        if (tocDrift.isNotEmpty()) {
            throw GradleException("docs/contributors/code-as-art.md's Contents list and its headings"
                    + " disagree:\n" + tocDrift.joinToString("\n"))
        }

        val drift = mutableListOf<String>()
        if (docCaps.isEmpty()) {
            drift.add("  the Size table in docs/contributors/code-as-art.md was not found;"
                    + " it must have a `| Language | Extensions | Soft | Hard | Exception |` header"
                    + " and one backticked extension per language")
        }
        (docCaps.keys + caps.keys).toSortedSet().forEach { ext ->
            val doc = if (ext in docCaps) docCaps[ext]?.toString() ?: "exempt" else "absent"
            val task = caps[ext]?.toString() ?: "exempt"
            if (doc != task) drift.add("  .$ext: charter says $doc, build enforces $task")
        }
        if (drift.isNotEmpty()) {
            throw GradleException("The size caps in docs/contributors/code-as-art.md and"
                    + " `fileSizeHardCaps` disagree. A cap the charter states and the"
                    + " build does not enforce is worse than no cap:\n" + drift.joinToString("\n"))
        }

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
            val lines = CodeLines.count(f.readText(), f.extension)
            present.add(rel)
            val listedAt = listed[rel]
            when {
                listedAt == null && lines > hard ->
                        overCap.add("  $rel: $lines code lines, hard cap $hard")
                listedAt != null && lines > listedAt ->
                        grew.add("  $rel: $lines code lines, baseline $listedAt (+${lines - listedAt})")
                listedAt != null && lines < listedAt ->
                        loose.add("  %5d  %s   (was %d)".format(lines, rel, listedAt))
            }
        }
        listed.forEach { (rel, at) ->
            if (rel.startsWith(here) && rel !in present) loose.add("  (deleted) $rel   (was $at)")
        }

        val problems = mutableListOf<String>()
        if (grew.isNotEmpty()) {
            problems.add("A file in size-baseline.txt may only shrink. These grew:\n"
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
// Guard G11: a fully-qualified class name in the body of a file is a ratchet, not a rule.
//
// `code-as-art.md`'s House rules say "no FQCN except collisions", and that rule was once
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
//   3. Dependency jars are deliberately off the format classpath (measured at 4.6x cost and a
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
 * The FQCN guard blanks literals: `cc.jumpkick.Foo` inside a fixture string is data. The original
 * guards keep them, because the thing they are hunting for (`"##JKT:"`, `"true"`, `"%02x"`) *is* a
 * literal — they need the javadoc that merely mentions it gone, and nothing more.
 */
fun blankNonCode(src: String, blankStrings: Boolean = true): String =
        CodeLines.blankNonCode(src, blankStrings)

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
            problems.add("A fully-qualified class name in a method body is banned —"
                    + " import the type. These files are not in fqcn-baseline.txt:\n"
                    + unlisted.joinToString("\n")
                    + "\n  `jk format` shortens type references for you. A static member, an"
                    + " annotation or a third-party type it cannot reach is a hand edit."
                    + " A genuine collision goes under `## collisions` with the name it collides with.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file in fqcn-baseline.txt may only shrink. These grew:\n"
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
// Guard plumbing shared by G3 / G5 / G6 / G7 / G9 / G12 / G13 / G15
//.
//
// Two habits inherited from G1, both load-bearing:
//   * match against code only — a banned literal named in javadoc is documentation, not a defect;
//   * squash whitespace first, so `jk format` wrapping a call across two lines cannot evade a
//     pattern written on one. A guard a re-flow can defeat stops working without anyone noticing.
//
// The squash stops at a literal's opening quote. Squashing *through* one invents tokens
// that were never written: `PlannerNative:168` labels a run `" native-image "`, and a squash that
// runs inside the quotes hands every downstream guard the step name `"native-image"` — a sentence
// reported as a defect. Java cannot wrap a string literal anyway, so there is nothing in there for
// a re-flow to hide.
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
                .let(::squashBetweenLiterals)

/** Drop whitespace between tokens, keeping every string / char / text-block literal verbatim. */
fun squashBetweenLiterals(src: String): String {
    val out = StringBuilder(src.length)
    var i = 0
    while (i < src.length) {
        val c = src[i]
        when {
            c == '"' || c == '\'' -> {
                val close = if (c == '"' && src.startsWith("\"\"\"", i)) "\"\"\"" else c.toString()
                out.append(close)
                i += close.length
                while (i < src.length) {
                    if (src[i] == '\\') {
                        out.append(src, i, minOf(i + 2, src.length))
                        i += 2
                    } else if (src.startsWith(close, i)) {
                        out.append(close)
                        i += close.length
                        break
                    } else {
                        out.append(src[i])
                        i++
                    }
                }
            }
            c.isWhitespace() -> i++
            else -> {
                out.append(c)
                i++
            }
        }
    }
    return out.toString()
}

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
// Guard G3: one XML parser, one hardening posture.
//
// Defect it prevents: a new `DocumentBuilderFactory` that forgets an XXE flag and then parses
// third-party XML — an AAR's `res/values/*.xml` from any Maven artifact, a git dependency's
// `pom.xml` inside the resident engine. Round 3 found seven production sites at five hardening
// levels, two of them with no XXE flags at all; those two were hardened first, then a sweep took all
// eight sites (seven production, one test) onto `cc.jumpkick.host.DomXml`.
//
// Three arms:
//   1. The other JAXP parser entry points are banned outright — zero sites today, no allowlist.
//      Without this arm the guard is one `SAXParserFactory` away from decoration.
//      `TransformerFactory` is deliberately absent: `ResourceMerger` uses it to *write* a DOM out,
//      which is not a parse.
//   2. `DocumentBuilderFactory` is banned everywhere but the owner. This was a ratchet over seven
//      files at first, because the owner did not exist yet to point a ban at; a later pass created it,
//      so the allowlist is one entry long and it is the owner's own path, not a concession.
//   3. Inside the owner, the six flags are required by name. Across files that check is worthless —
//      a scan cannot tell which factory instance a `setFeature` call configures, which is the whole
//      reason arm 2 exists. Inside one file holding exactly one factory it is exact, so deleting a
//      flag from `DomXml.hardened` fails the build instead of silently weakening every caller.
//
// The owner is in `shared/host`, not `server/io` as the ticket first said. `:android` sees only
// `:plugin-sdk` and `:toolchain-jdk` sees only `:core` + `:client-io`, so neither can reach
// `server/io`; `:host` is the JDK-only floor all 30 modules already link, and JAXP is JDK.
//
// Scope is `src/main/java` *and* `src/test/java`. A test parsing XML has the same posture to get
// wrong, and the one that did — `PomExporterTest`, checking a POM it had just written — is one call
// to the owner, so exempting tests would buy nothing and leave the shape unguarded.
// ---------------------------------------------------------------------------

/** The one file allowed to construct an XML parser. */
val xmlParserOwner = "shared/host/src/main/java/cc/jumpkick/host/DomXml.java"

/**
 * jk's XXE posture, as it must read inside the owner. Whitespace-squashed (see `guardText`), so
 * these are the exact tokens `DomXml.hardened` writes with `jk format`'s wrapping removed.
 */
val xxeHardening = listOf(
        "setFeature(XMLConstants.FEATURE_SECURE_PROCESSING,true)",
        "setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\",true)",
        "setFeature(\"http://xml.org/sax/features/external-general-entities\",false)",
        "setFeature(\"http://xml.org/sax/features/external-parameter-entities\",false)",
        "setFeature(\"http://apache.org/xml/features/nonvalidating/load-external-dtd\",false)",
        "setExpandEntityReferences(false)")

val checkSingleXmlParserOwner by tasks.registering {
    group = "verification"
    description = "Fail the build on an XML parser outside cc.jumpkick.host.DomXml (XXE posture)"
    val java = fileTree(layout.projectDirectory) {
        include("src/main/java/**/*.java")
        include("src/test/java/**/*.java")
    }
    inputs.files(java).withPropertyName("java")
    val owner = rootProject.layout.projectDirectory.file(xmlParserOwner)
    inputs.file(owner).withPropertyName("domXml")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val allowed = xmlParserOwner
    val required = xxeHardening
    val stamp = layout.buildDirectory.file("guards/single-xml-parser-owner.ok")
    outputs.file(stamp)
    doLast {
        val bannedFactories = listOf("SAXParserFactory", "XMLInputFactory", "XMLReaderFactory")
        val banned = mutableListOf<String>()
        val parsers = mutableListOf<String>()
        java.files.sorted().forEach { f ->
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            bannedFactories.filter { code.contains(it) }.forEach { banned.add("  $rel: $it") }
            if (rel != allowed && code.contains("DocumentBuilderFactory")) parsers.add("  $rel")
        }

        val problems = mutableListOf<String>()
        if (banned.isNotEmpty()) {
            problems.add("jk parses XML in one place, with one hardening posture."
                    + " These name a JAXP parser that has no owner and no XXE flags at all:\n"
                    + banned.joinToString("\n"))
        }
        if (parsers.isNotEmpty()) {
            problems.add("A second DocumentBuilderFactory is a second XXE posture to get wrong, and"
                    + " the seven that existed sat at four different hardening levels:\n"
                    + parsers.joinToString("\n")
                    + "\n  Parse through cc.jumpkick.host.DomXml — parse(byte[] | String | Path |"
                    + " InputStream) to read, newDocument() to build one. It hands out documents,"
                    + " never a factory or a builder, so there is no unhardened parser to obtain.")
        }
        // Arm 3 runs from every module's copy of the task, like G6's read of `Hashing`: the owner is
        // one file at a fixed path, and reading it here is what keeps the posture and the guard from
        // drifting apart in separate commits.
        val ownerCode = guardText(owner.asFile.readText())
        val missing = required.filterNot { ownerCode.contains(it) }
        if (missing.isNotEmpty()) {
            problems.add("cc.jumpkick.host.DomXml is the only parser jk builds, so its flags are the"
                    + " only XXE posture jk has. These are gone:\n"
                    + missing.joinToString("\n") { "  $it" }
                    + "\n  Restore them in DomXml.hardened, or change this list in the same commit"
                    + " and say in the message what jk now accepts from a hostile document.")
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkSingleXmlParserOwner) }
tasks.named("jar") { dependsOn(checkSingleXmlParserOwner) }

// ---------------------------------------------------------------------------
// Guard G5: a fork protocol's line prefix has exactly two ends.
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
                    + " one reads it. These do not:\n"
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
// Guard G7: one truth set, and it lives in
// `EnvValues.parseBool`.
//
// Defect it prevents: `JK_FOO=yes` working in one reader and not the next. `EnvValues.parseBool`
// is the jk-wide truth set — `1/true/yes/on` against `0/false/no/off`, case-insensitively, trimmed
// — and every hand-rolled comparison below it accepted a different subset. Before the sweep
// `HttpProjectApi` took four spellings, `FormatStamps` took two, `JkConfigLoader` was
// case-insensitive where `JdkCatalogClient` is not, and `Profiles.autoSelect` took only `true`, so
// `CI=yes` selected the `ci` profile in no reader at all. Users could not see which one they were
// talking to.
//
// The pattern is the whole truth set — `true|1|yes|on|false|0|no|off` on either side of `equals`
// and `equalsIgnoreCase` — and both halves of that width were earned, not guessed:
//   * The audit's form (receiver position, `equalsIgnoreCase`, `true`/`1` only) is defeated by
//     flipping the argument order, which is not a defence. Fixing that took 15 files to 22.
//   * `true`/`1` alone cannot see the FALSE side, and the false side is where the bypasses were
//     hiding: `AotSettings.isOff`, `CentralMirror.enabledByEnv`, `HostWarmup.isOff`,
//     `ChromeTimeline` and `CliSessionTranscript` each spelled their own `off/false/0/no`, and
//     none of them was on this ratchet. A guard's count is bounded by its pattern, not by the
//     defect (the lesson G9 learned the expensive way).
//
// The widened pattern does cost false positives, because `"0"` and `"1"` are also just numbers:
// two version-string comparisons and two wizard menu ids are on the list below for that reason,
// and say so. That is the right trade — a guard that cannot see `JK_AOT_TRAIN=no` is decoration.
//
// A ratchet whose only remaining entries are permanent exemptions: readers of someone else's
// format, and comparisons that are not booleans at all. There is no pending section left. The
// owner moved to `:host` in the same sweep so that `:jk-api` and the forked plugin workers — the
// two places that had no way to reach it — can call it.
//
// Scope is `src/main/java`. A test asserting on the string `"true"` is a fixture, not a reader.
// ---------------------------------------------------------------------------

/** Files comparing against a truth-set literal by hand. Every entry is permanent; see G7 above. */
val truthSetRatchet = mapOf(
        // --- Someone else's format, someone else's truth set. Correct as written. ---
        // Maven POM XML: `<optional>` and `<activeByDefault>` are xs:boolean, `true` only.
        "server/io/src/main/java/cc/jumpkick/repo/PomParser.java" to 1,
        "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java" to 1,
        // The disco JDK catalog is JSON: `true`/`false`, never `yes`.
        "shared/toolchain-jdk/src/main/java/cc/jumpkick/jdk/JdkCatalogClient.java" to 2,
        // giter8 template booleans are `y`/`yes`/`true` — a different set on purpose.
        "server/engine/src/main/java/cc/jumpkick/giter8/Giter8Value.java" to 2,

        // --- Not a boolean. The characters collide; the vocabulary does not. ---
        // jk's own on-disk memo row stores the bit as literal `1`, so `parts[2]` is not user input.
        "server/engine/src/main/java/cc/jumpkick/runtime/PreflightMemo.java" to 1,
        // A version string that is literally "0", and a version segment that is literally "0".
        "plugins/quarkus/src/main/java/cc/jumpkick/quarkus/LockedClosure.java" to 1,
        "server/resolver/src/main/java/cc/jumpkick/resolver/VersionSelectors.java" to 1,
        // Wizard menu ids. The prompt offers exactly two, jk wrote both, and a lenient parse would
        // silently accept an answer the menu never showed.
        "clients/cli/src/main/java/cc/jumpkick/command/ActivateCommand.java" to 1,
        "clients/cli/src/main/java/cc/jumpkick/command/JdkInstallWizard.java" to 1)

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
        // Every truth-set member, on either side of equals/equalsIgnoreCase (see G7 above).
        val truthy = "true|1|yes|on|false|0|no|off"
        val handRolled = Regex(
                """"(?:$truthy)"\.equals(?:IgnoreCase)?\(|\.equals(?:IgnoreCase)?\("(?:$truthy)"\)""")
        val hits = LinkedHashMap<String, Int>()
        mainJava.files.sorted().forEach { f ->
            val n = countIn(guardText(f.readText()), handRolled)
            if (n > 0) hits[f.relativeTo(treeRoot).invariantSeparatorsPath] = n
        }
        val (grew, unlisted, loose) = ratchetVerdict(hits, allowed, here)

        val problems = mutableListOf<String>()
        if (unlisted.isNotEmpty()) {
            problems.add("jk has one boolean truth set and it is EnvValues.parseBool — 1/true/yes/on"
                    + " against 0/false/no/off, trimmed, case-insensitive. These compare"
                    + " by hand and are not on the ratchet:\n"
                    + unlisted.joinToString("\n")
                    + "\n  Call cc.jumpkick.config.EnvValues.parseBool(raw) (or .bool(env, name)"
                    + " for a JK_* variable); it is in :host, so every module can reach it. A"
                    + " reader of someone else's format — Maven's xs:boolean, giter8's y/yes — or a"
                    + " comparison that is not a boolean at all is an exemption, and says so here.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file on the truth-set ratchet may only shrink. These grew:\n"
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
// Guard G6: one MessageDigest lookup in the tree, and it is `Hashing`'s.
//
// Defect it prevents: a second answer to "what does jk hash with". Fifteen production files called
// `MessageDigest.getInstance` directly and each one re-decided the surrounding questions — three
// different file-read buffer sizes, four different reactions to `NoSuchAlgorithmException`
// (rethrow, wrap, return null, and `BuildJobFingerprint`'s silent fall back to
// `Integer.toHexString(s.hashCode())`, a 32-bit non-digest quietly standing in for a cache key).
// None of those is a decision a call site should be making, and the fallback was a correctness
// hazard nobody would have found: it produces a plausible-looking hex string.
//
// Two shapes are banned, and the second is the subtle one:
//
//   1. `MessageDigest.getInstance(` outside the owner. Every algorithm, not only SHA-256 — the
//      buffer, the exception policy and the hex spelling are the same problem whatever the digest.
//   2. jk's own algorithm passed to `Hashing`'s multi-algorithm doors (`newDigest` / `fileHex` /
//      `hashHex`). Those exist for foreign formats — a Maven `.sha1` sidecar, Central's four
//      required checksums, Google's Android SDK feed — where the algorithm belongs at the call
//      site because the *file format* names it, right next to the `.sha1` it pairs with. Routing
//      jk's own hashing through them would re-spell `"SHA-256"` and put the ban one string away
//      from meaningless. `newSha256()` / `sha256Hex(..)` are the doors for that.
//
// The banned algorithm literal is READ FROM THE OWNER, not re-typed here: it is whatever
// `Hashing.newSha256()` asks `newDigest` for. Change jk's digest there and the guard follows in
// the same commit — the same habit as G12/G13, for the same reason.
//
// Not in scope, deliberately: `KeyStore.getInstance`, `Signature.getInstance`, `Mac.getInstance`,
// `KeyFactory.getInstance`. They are other JCA services with other vocabularies, and `Hashing` is
// a digest surface, not a JCA front door. `Sbom`'s `"SHA-256"` is untouched too — it is an SPDX
// field *value* jk writes into a document, not an algorithm it looks up.
//
// A pure ban, not a ratchet: zero sites remain and there is nothing to allow. Reachability was
// measured, not assumed — `Hashing` lives in `:host`, which `:plugin-sdk` re-exports
// with `api`, so all 15 plugin workers see it; `:host` was later added to `:jk-api`, the last module
// that could not reach it.
//
// Scope is `src/main/java`. A test that recomputes an expected digest by hand is checking jk's
// answer against an independent one, which is exactly what a test should do — two of those exist
// and are the reason this guard does not read test sources.
// ---------------------------------------------------------------------------
val checkOneDigestSurface by tasks.registering {
    group = "verification"
    description = "Fail the build on a MessageDigest lookup outside cc.jumpkick.host.Hashing"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/Hashing.java")
    inputs.file(owner).withPropertyName("hashing")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/one-digest-surface.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // jk's own algorithm, straight out of the owner (see G6 above): what newSha256() asks for.
        val ownAlgorithm = Regex("""newSha256\(\)\{returnnewDigest\("([^"]+)"\);}""")
                .find(guardText(ownerFile.readText()))
                ?.groupValues
                ?.get(1)
                ?: throw GradleException("G6 cannot read jk's algorithm out of ${ownerFile.name}:"
                        + " newSha256() is expected to be `return newDigest(\"<algorithm>\");`")

        val lookup = Regex("""MessageDigest\.getInstance\(""")
        val ownAlgorithmByName = Regex(
                """(?:newDigest|fileHex|hashHex)\(""" + Regex.escape('"' + ownAlgorithm + '"'))
        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            if (f == ownerFile) return@forEach
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            countIn(code, lookup).let {
                if (it > 0) hits.add("  $rel: $it x MessageDigest.getInstance(..)")
            }
            countIn(code, ownAlgorithmByName).let {
                if (it > 0) hits.add("  $rel: $it x \"$ownAlgorithm\" passed to a Hashing algorithm door")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("jk hashes in one place, cc.jumpkick.host.Hashing — a second digest"
                    + " site re-decides the buffer size, the exception policy and the hex spelling,"
                    + " and one of them silently substituted String.hashCode() for a cache key"
                    + ":\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Hashing.sha256Hex(bytes | String | Path) or Hashing.newSha256() for jk's own"
                    + " hashing. Hashing.newDigest/fileHex/hashHex take an algorithm name only when a"
                    + " foreign format dictates it — a .sha1 sidecar, an SDK feed — never \""
                    + ownAlgorithm + "\".")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkOneDigestSurface) }
tasks.named("jar") { dependsOn(checkOneDigestSurface) }

// ---------------------------------------------------------------------------
// Guard G9: bytes become hex in one place,
// `Hashing.hex`.
//
// Defect it prevents: a second answer to "how does jk spell bytes". Two shapes, and the second one
// is 84% of the history.
//
//   1. A PER-BYTE HEX LOOP. `KotlinCompiler:174` allocated a throwaway `Formatter` for every byte
//      of every classpath entry's digest — `String.format("%02x", b)` builds one per call, so a
//      200-entry classpath cost ~6,400 of them per compile — and `AndroidCommand:131` omitted the
//      `& 0xff` mask that its neighbour remembered. Both are one call to `Hashing.hex`.
//   2. `java.util.HexFormat` ANYWHERE BUT THE OWNER. `HexFormat.of().formatHex(digest)` is not a
//      loop, allocates no `Formatter`, and is a perfectly reasonable line of Java — which is
//      exactly why it was written SIXTEEN times before a sweep took it. It is still a second
//      spelling of the owner's one answer, and the sweep left nothing stopping the seventeenth.
//      `parseHex` is banned by the same arm: decode has no in-tree caller today, so there is no
//      door to point at, and adding `Hashing.unhex(String)` is the change the next caller makes
//      rather than dead API added on speculation.
//
// MEASURED COUNTS, so a future reader can tell a green result from a blind one (a green guard is
// evidence about the guard, not about the tree). Last measured against `src/main/java` tree-wide:
// arm 1 = 0 (`%02x`/`%02X`: 0 files; `Character.forDigit`: 1 file, `SigV4Signer`, exempt by shape);
// arm 2 = 0 (`HexFormat` appears in exactly 1 production file, `Hashing.java`, the owner). Arm 2's
// count was zero BY HISTORY, not by construction — that is the condition this ban converts.
//
// Arm 1 was the whole guard when it first reported zero, and it covered under a third of the
// problem: `HexFormat.of()` is invisible to a hex-loop regex. Widening the existing guard rather
// than allocating a letter is deliberate — same rule, same owner, same error message; a second
// guard would be a second thing to keep in sync.
//
// THE ONE EXEMPTION IN ARM 1 IS BY SHAPE, NOT BY FILENAME. `SigV4Signer:168` percent-encodes a URI
// byte as UPPERCASE hex, which the AWS canonical-request spec requires and `Hashing.hex`
// deliberately does not produce — it is a different function that happens to spell bytes in base
// 16. The pattern therefore skips `Character.forDigit` wrapped in `Character.toUpperCase`, which is
// the shape that says "uppercase on purpose" at the call site. An allowlist entry would have said
// the same thing about one path, and stopped being true the moment the file moved (the earlier
// precedent).
//
// `%02X` stays banned even though it is also uppercase: the Formatter-per-byte allocation is a
// defect in either case, and `Character.toUpperCase(Character.forDigit(..))` allocates nothing.
//
// ARM 2'S ONLY EXEMPTION IS THE OWNER, and its path is READ FROM `inputs.file(owner)` rather than
// typed into the scan — G6's shape. A filename string in the scan expires silently when the class
// moves; a declared input fails the build instead.
//
// Arm 2 matches the TYPE, not a method, and it is scanned over text that still carries the
// `import` lines — the complement of the usual `guardText`. Both choices are the "check every shape
// the bypass takes" rule: `HexFormat.of()`, `HexFormat.ofDelimiter(..)`, a `HexFormat` field, the
// fully-qualified `java.util.HexFormat.of()` and `import static java.util.HexFormat.of` are five
// spellings of one bypass, and the last of them is invisible to any pattern that drops imports.
//
// DELIBERATELY NOT COVERED, measured before deciding: `Integer.toHexString` (`PlannerSupport:592`,
// `ExecPlans:496`) and `Long.toHexString` (`StaticContent:92`) render an int/long identity or mtime
// as a short key — not bytes becoming hex, and not the owner's job. `RichText.parseHex` (`:217`,
// `:227`) parses a `#rrggbb` colour token: a different vocabulary, free to diverge, exactly like
// GraalVM's `native-image` filename under G12. A pattern wider than the defect is as wrong as one
// narrower.
//
// Scope is `src/main/java`. Two tests recompute an expected digest by hand with
// `HexFormat.of().formatHex(..)` (`AotCacheTrainerTest:139`, `BaseJreMarkerTest:63`), and that is
// what a test should do — checking jk's answer against an independent one. Widening this guard to
// test sources would break exactly those two and buy nothing; see G6, which records the same
// reasoning for the same two files.
// ---------------------------------------------------------------------------
val checkNoHandRolledHex by tasks.registering {
    group = "verification"
    description = "Fail the build on a per-byte hex loop, or java.util.HexFormat outside Hashing"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/Hashing.java")
    inputs.file(owner).withPropertyName("hashing")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-hand-rolled-hex.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // Arm 1: `%02x` in any format string (format / formatted / printf), and Character.forDigit
        // unless it is being upper-cased — see the shape exemption above.
        val hexLoop = Regex("""%02[xX]|(?<!Character\.toUpperCase\()Character\.forDigit\(""")
        // Arm 2: the type, in any spelling — call, field, FQCN, plain or static import.
        val hexFormat = Regex("""\bHexFormat\b""")
        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val src = f.readText()
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            countIn(guardText(src), hexLoop).let {
                if (it > 0) hits.add("  %5d  %s  per-byte hex loop".format(it, rel))
            }
            if (f == ownerFile) return@forEach
            // guardText drops imports; this arm must see them (see above), so blank the comments
            // and squash the whitespace without the import filter.
            val withImports = squashBetweenLiterals(blankNonCode(src, blankStrings = false))
            countIn(withImports, hexFormat).let {
                if (it > 0) hits.add("  %5d  %s  java.util.HexFormat".format(it, rel))
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("Bytes become hex in one place, cc.jumpkick.host.Hashing.hex"
                    + ". These spell it themselves:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Call Hashing.hex(byte[]) — or sha256Hex, which does the digest too."
                    + " String.format(\"%02x\", b) allocates a Formatter per byte."
                    + "\n  HexFormat.of().formatHex(digest) looks fine and is not slow; it is banned"
                    + " because it is a SECOND ANSWER to \"how does jk spell bytes\", and sixteen"
                    + " call sites had each answered it separately before the sweep."
                    + " Hashing.hex is the door, and its lowercase-always contract is the point."
                    + "\n  Going the other way (HexFormat.of().parseHex) has no owner yet: add"
                    + " Hashing.unhex(String) next to hex(byte[]) and call that, rather than"
                    + " reopening the shape here."
                    + "\n  Uppercase hex for a non-digest encoding is a different function: write it"
                    + " as Character.toUpperCase(Character.forDigit(..)), which this guard exempts by"
                    + " shape, and say at the call site which spec demands it.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoHandRolledHex) }
tasks.named("jar") { dependsOn(checkNoHandRolledHex) }

// ---------------------------------------------------------------------------
// Guard G12: a step is named once, in `TaskNames`.
//
// Defect it prevents: the silent missing-dependency edge. A step name is a producer/consumer
// contract — `Step.builder("compile-java")` on one side, `.requires("compile-java")` on the other —
// and when both ends type the string, a typo is not a compile error, it is an edge that quietly
// does not exist. `cc.jumpkick.run.TaskNames` has owned these names all along and 243 production
// references already went through it; the sweep closed the 229 that did not, taking it to 472.
//
// The ban list is READ FROM THE OWNER, not re-typed here: every `public static final String` in
// `TaskNames.java` whose value contains a hyphen. Add a constant and it is banned as a literal the
// same minute — a guard that carried its own copy of the vocabulary would be the third place to
// keep in sync, which is the defect it exists to prevent.
//
// The seven single-word values (`train`, `delete`, `install`, `prewarm`, `select`, `wizard`,
// `scaffold`) are deliberately OUT of the list. They are ordinary English — `"install"` appears in
// paths, help text and Maven scopes — so banning them by text scan would be false positives all
// the way down. The hyphenated 51 are unambiguous: nothing else in the tree spells `write-stamp`.
//
// TWO OWNERS, NO EXEMPTION AND NO ALLOWLIST. `native-image` is two vocabularies that happen to
// spell the same characters: a step name (`TaskNames.NATIVE_IMAGE`) and GraalVM's launcher FILE
// (`cc.jumpkick.host.GraalLauncher.NAME`). They are free to diverge and neither may borrow the
// other's constant, so both files are guard inputs and both are skipped; the literal is banned
// everywhere else, in either meaning.
//
// This replaces an earlier shape exemption, which blanked "a bare literal sitting directly beside
// its own `.cmd`/`.exe` sibling" before scanning. That exemption was sound but it was hiding
// something: it blanked three sites in two files, and there were FOUR encodings of the launcher
// path in the tree. The fourth, `TrainRunner`, spelled the name path-joined as `"bin/native-image"`
// and so was never a candidate for the exemption or for this guard — it evaded G12 entirely while
// the guard reported green. All four were folded into one owner; with one owner there is one
// site, an owner skip covers it, and the shape exemption is gone rather than dormant.
//
// Measured at the fold: 0 violations. Before it, the same scan without the exemption saw
// 3 (`NativePreflight:90`, `NativeImageDriver:320`, `:379`) — all filename spellings, none a step.
//
// A pure ban, not a ratchet: zero sites remain and there is nothing to allow. Reachability was
// measured, not assumed — 14 of the 30 modules carry `:jk-api` on their compile classpath (it is
// an `api` dependency of both `:core` and `:wire`, which pulls in most of the tree), and those 14
// are exactly the ones that name a step today. The other 16 are the plugin workers and the leaf
// libraries (`host`, `cli-terminal`, `dynamic-surface`, `plugin-sdk`, `web`, …). None of them
// names a step, and the guard firing on one that starts to is the right outcome, not a trap:
// either it takes the dependency — `:jk-api` is a pure model module, not `:core` — or the name
// belongs on the engine side of the wire and has no business being re-typed in a worker.
//
// Scope is `src/main/java`. Test sources keep their literals on purpose: an assertion that the
// journal rendered `run-tests` is a golden pinning the OUTPUT vocabulary, and rewriting it to
// `TaskNames.RUN_TESTS` would make a rename of the value invisible to the whole suite — the test
// would follow the rename and still pass. 519 test-side literals are therefore left alone.
// ---------------------------------------------------------------------------

val checkNoBareTaskName by tasks.registering {
    group = "verification"
    description = "Fail the build on a step name typed as a literal (use cc.jumpkick.run.TaskNames)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/jk-api/src/main/java/cc/jumpkick/run/TaskNames.java")
    inputs.file(owner).withPropertyName("taskNames")
    // The other owner of `native-image`, the launcher filename (see G12 above). Declared as an
    // input, not typed into the scan: a filename string would expire silently if the class moved,
    // and this fails the build loudly instead.
    val launcherOwner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/GraalLauncher.java")
    inputs.file(launcherOwner).withPropertyName("graalLauncher")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-bare-task-name.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        val launcherFile = launcherOwner.asFile
        // value -> constant, for the hyphenated names only (see G12 above).
        val named = Regex("""public static final String (\w+) = "([^"]+)";""")
                .findAll(ownerFile.readText())
                .map { it.groupValues[2] to it.groupValues[1] }
                .filter { (value, _) -> value.contains('-') }
                .toMap()

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            if (f == ownerFile || f == launcherFile) return@forEach
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            named.forEach { (value, constant) ->
                val n = countIn(code, Regex(Regex.escape("\"$value\"")))
                if (n > 0) hits.add("  $rel: $n x \"$value\"  ->  TaskNames.$constant")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A step name typed as a literal is a producer/consumer contract"
                    + " with no compiler behind it — a typo becomes a missing dependency edge, not"
                    + " a build error. These name a step by hand:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Reference cc.jumpkick.run.TaskNames instead; it is on every production"
                    + " module's classpath. A string that is NOT a step name — GraalVM's"
                    + " native-image launcher file, say — must not borrow the constant either: it"
                    + " has its own owner, cc.jumpkick.host.GraalLauncher, and a third vocabulary"
                    + " spelling the same characters needs a fourth owner, not a literal.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoBareTaskName) }
tasks.named("jar") { dependsOn(checkNoBareTaskName) }

// ---------------------------------------------------------------------------
// Guard G13: jk's own file names are spelled once, in `ManifestPaths`.
//
// Defect it prevents: the rename that half-lands. `jk.toml` had no owner at all — 214 production
// sites typed it, four of them inside `shared/core/layout`, the package that should have owned it —
// while its sibling `jk-lock.toml` had `LockPaths.FILE_NAME` and three sites typed it anyway. A
// name spelled in 214 places cannot be moved, and a name that cannot be moved is not a decision
// jk still owns; it is one the tree has already made. Worse, the near-misses are invisible: a
// reader looking for `jk-libs.toml` beside a writer that produced `jk-lib.toml` compiles, ships,
// and simply never finds the file.
//
// The ban list is READ FROM THE OWNER, not re-typed here: every `public static final String` in
// `ManifestPaths.java`. Add a name there and it is banned as a literal the same minute — a guard
// carrying its own copy of the vocabulary would be the second place to keep in sync, which is the
// defect it exists to prevent.
//
// A pure ban, not a ratchet: zero sites remain and there is nothing to allow. Reachability was
// measured, not assumed — every one of the eight Gradle modules that named a file already carries
// `:core` on its compile classpath, and `ManifestPaths` is a constants-only class in it. The ninth
// module in the audit's count, `clients/intellij`, is not in `settings.gradle.kts` at all: it is a
// separate wire-only build that must never see a jk jar, so its one `new File(base, "jk.toml")`
// is out of reach by design and out of this guard's scope by construction.
//
// Someone else's `config.toml` — a third-party tool's, read by a future importer — would be a
// different vocabulary that is free to diverge, exactly like GraalVM's `native-image` launcher
// under G12. It must not borrow `ManifestPaths.CONFIG`; it gets its own owner, and this guard
// then has nothing to say about it.
//
// Scope is `src/main/java`. Test sources keep their literals on purpose, for the same reason G12
// leaves step names alone: a fixture that writes a `jk.toml` and asserts on `no jk.toml in <dir>`
// is a golden pinning the on-disk vocabulary, and rewriting it to `ManifestPaths.MANIFEST` would
// make a rename of the value invisible to the whole suite — every test would follow the rename and
// still pass. 1,195 test-side literals across 228 files are therefore left alone.
// ---------------------------------------------------------------------------
val checkNoBareManifestName by tasks.registering {
    group = "verification"
    description = "Fail the build on a jk file name typed as a literal (use cc.jumpkick.lock.ManifestPaths)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/core/src/main/java/cc/jumpkick/lock/ManifestPaths.java")
    inputs.file(owner).withPropertyName("manifestPaths")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-bare-manifest-name.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // value -> constant, straight out of the owner (see G13 above).
        val named = Regex("""public static final String (\w+) = "([^"]+)";""")
                .findAll(ownerFile.readText())
                .associate { it.groupValues[2] to it.groupValues[1] }

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            if (f == ownerFile) return@forEach
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            named.forEach { (value, constant) ->
                val n = countIn(code, Regex(Regex.escape("\"$value\"")))
                if (n > 0) hits.add("  $rel: $n x \"$value\"  ->  ManifestPaths.$constant")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A file jk owns is named once, in cc.jumpkick.lock.ManifestPaths"
                    + ". These re-type the name:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Reference the constant. A file that is NOT jk's — a third-party tool's"
                    + " own config.toml, say — must not borrow it either: give that vocabulary its"
                    + " own owner.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoBareManifestName) }
tasks.named("jar") { dependsOn(checkNoBareManifestName) }

// ---------------------------------------------------------------------------
// Guard G2: a hard process exit names its code, in `Exit`.
//
// Defect it prevents: an exit status that means several things at once. `System.exit(n)` and
// `Runtime.getRuntime().halt(n)` are the only two calls whose integer a user's shell actually
// sees, and the integer `2` once reached that shell meaning eight different things — a
// malformed `jk.toml`, a wrong command line, a missing plugin spec, an unexpected `Throwable`, an
// unreachable engine, a wedged AOT trainer, and, from `GlobalCancel`, that the user had pressed
// Ctrl-C. `130` meant three. No script could branch on `$?`, and no user could tell a cancelled
// build from a broken config. A bare integer at an exit site is not a style problem: it is a
// meaning nobody had to write down.
//
// This is the one place the "0 and 1 often stay bare" clause in `Exit`'s javadoc does NOT apply.
// A `return 0` is an internal control-flow value that a caller may still translate; `System.exit(0)`
// is the observable contract. So the ban is on the shape, with no exemption for small numbers.
//
// The `return <int>` arm is deliberately NOT banned: `VscodeIdeGenerator`'s `return 3` is a file
// count, and `src/main` holds 282 `return 0` / 142 `return 1` that are overwhelmingly not exit
// codes. A text scan cannot tell those apart, and a guard that cries wolf gets an allowlist and
// then gets ignored.
//
// Suggestions are READ FROM THE OWNER, not re-typed here: every `public static final int` in
// `Exit.java` becomes a value -> constant hint in the failure message, so adding a code to the
// vocabulary teaches the guard about it the same minute. Same habit as G12/G13.
//
// A pure ban, not a ratchet: zero sites remain and there is nothing to allow. The one historical
// false positive — `HardwareProbe:340`, a `System.exit(1)` inside a TEXT BLOCK of generated probe
// source — needs no allowlist either, because this guard blanks string, char and text-block
// contents before matching (`blankNonCode`'s default). An exit code written inside a string is
// some other program's exit code; it is data, not this file's contract. That is structural, so it
// keeps holding for the next generated snippet nobody thought to allow.
//
// Reachability was measured, not assumed: the four modules with an exit site today are `:cli`,
// `:engine`, `:plugin-sdk` and `:quarkus`, and `Exit` lives in `:host`, which
// `:plugin-sdk` re-exports with `api` — so all 15 plugins can see it. `Exit`'s values are
// `static final int` and inline at compile time, so even the `compileOnly` worker jars need
// nothing extra on their runtime classpath.
//
// Scope is `src/main/java`. Test sources are out for the reason G1 gives: a fixture that generates
// a throwaway `main` calling `System.exit(0)` is writing a file, not exiting jk.
// ---------------------------------------------------------------------------
val checkNoBareExitCode by tasks.registering {
    group = "verification"
    description = "Fail the build on System.exit/halt with a literal (use cc.jumpkick.model.command.Exit)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/model/command/Exit.java")
    inputs.file(owner).withPropertyName("exitCodes")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-bare-exit-code.ok")
    outputs.file(stamp)
    doLast {
        // value -> constant, straight out of the owner (see G2 above).
        val named = Regex("""public static final int (\w+) = (-?\d+);""")
                .findAll(owner.asFile.readText())
                .associate { it.groupValues[2] to it.groupValues[1] }

        // Both spellings of "kill this process with a number". `.halt(` rather than
        // `getRuntime().halt(` so a Runtime held in a local cannot slip past.
        val banned = listOf(
                Regex("""System\.exit\((-?\d+)\)"""),
                Regex("""\.halt\((-?\d+)\)"""))
        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            // Strings blanked, not kept: an exit code inside a text block is generated source.
            val code = blankNonCode(f.readText()).replace(Regex("\\s+"), "")
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            banned.forEach { pattern ->
                pattern.findAll(code).forEach { m ->
                    val value = m.groupValues[1]
                    val hint = named[value]?.let { "Exit.$it" } ?: "a named Exit constant (add one)"
                    hits.add("  $rel: ${m.value}  ->  $hint")
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A hard process exit is the one integer a user's script sees, and a"
                    + " bare one is a meaning nobody wrote down — jk shipped an exit `2` that meant"
                    + " eight things at once, including Ctrl-C. Name the code:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  cc.jumpkick.model.command.Exit is in :host, which every module already"
                    + " reaches. If no existing constant fits, add one there with a javadoc line"
                    + " saying what it means — do not reuse a code that already means something.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoBareExitCode) }
tasks.named("jar") { dependsOn(checkNoBareExitCode) }

// ---------------------------------------------------------------------------
// Guard G8: one archive instant, and one class that stamps an entry with it.
//
// Defect it prevents: an archive whose bytes are a function of the machine that built it. A ZIP
// entry's timestamp is DOS time, and `ZipEntry.setTime` converts to it through the JVM's default
// timezone — so identical inputs written under a different $TZ produce different bytes, and the
// raw-archive fingerprints that key the action cache stop matching. `setTimeLocal` is the TZ-free
// spelling and the two differ by five characters. Round 3 found the pinned instant re-typed in
// five packagers, a sixth spelling inside the Quarkus fast-jar, and three writers that pinned
// nothing at all.
//
// Three arms, every one at zero violations, so there is no allowlist and no reason to open one:
//   1. `setTime(` is banned outright. jk dates with `java.time`, so nothing in production has a
//      receiver for it other than a zip entry, and on a zip entry it is always the wrong call.
//   2. `setTimeLocal(` is banned outside `DeterministicZip` — the one class allowed to stamp an
//      entry. Every packager, in every module, writes through it.
//   3. The epoch value itself is banned outside `DeterministicZip`, so a sixth copy cannot come
//      back as a bare number. The value is READ FROM THE OWNER rather than re-typed here: change
//      `EPOCH_SECONDS` and the guard follows in the same minute, which a copy in this script
//      would not.
//
// Reachability was measured, not assumed: `DeterministicZip` is on the host leaf, which every
// production module reaches — the plugin workers through `:plugin-sdk`, the rest through `:core`
// or a direct dependency.
//
// Scope is `src/main/java`. Test sources keep the literal on purpose: `ArchiveTimestampTest`,
// `SourcesJarTimestampTest` and `AotCacheJarTimestampTest` each assert the exact stamped value,
// and a golden that borrowed the constant would follow a change to it and still pass.
// `ClasspathFingerprintTest` calls `setTime` deliberately, to build the varying-timestamp jars
// that prove a fingerprint ignores them.
// ---------------------------------------------------------------------------
val checkSingleArchiveInstant by tasks.registering {
    group = "verification"
    description = "Fail the build on an archive entry stamped outside cc.jumpkick.host.DeterministicZip"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/DeterministicZip.java")
    inputs.file(owner).withPropertyName("deterministicZip")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/single-archive-instant.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // The banned number, straight out of the owner (see G8 above), in both spellings a Java
        // author can write it.
        val epoch = Regex("""public static final long EPOCH_SECONDS = ([0-9_]+)L;""")
                .find(ownerFile.readText())
                ?.groupValues
                ?.get(1)
                ?: throw GradleException("cc.jumpkick.host.DeterministicZip no longer declares"
                        + " EPOCH_SECONDS, so the archive-instant guard has lost the owner it reads."
                        + " Restore the constant or retire this guard deliberately.")
        val numbers = setOf(epoch, epoch.replace("_", ""))

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val byTime = countIn(code, Regex("""\.setTime\("""))
            if (byTime > 0) hits.add("  $rel: $byTime x setTime(  ->  DeterministicZip.entry")
            if (f == ownerFile) return@forEach
            val byLocal = countIn(code, Regex("""\.setTimeLocal\("""))
            if (byLocal > 0) hits.add("  $rel: $byLocal x setTimeLocal(  ->  DeterministicZip.entry")
            numbers.forEach { value ->
                val n = countIn(code, Regex(Regex.escape(value)))
                if (n > 0) hits.add("  $rel: $n x $value  ->  DeterministicZip.EPOCH_SECONDS")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("An archive entry is stamped in one place,"
                    + " cc.jumpkick.host.DeterministicZip. These stamp their own:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  setTime converts to DOS time through the default timezone, so an archive"
                    + " written with it is a function of the build host's \$TZ, and a second copy of"
                    + " the epoch is a second instant waiting to drift. Write entries through"
                    + " DeterministicZip; every production module can reach it.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkSingleArchiveInstant) }
tasks.named("jar") { dependsOn(checkSingleArchiveInstant) }

// ---------------------------------------------------------------------------
// Guard G15: a cache tier's directory is named once, in `CacheTree`.
//
// Defect it prevents: the rename that half-lands across a process boundary. A tier under the cache
// root is written by one process and reclaimed, measured and wiped by others — `base-jre` by the
// image-builder worker and reclaimed by the engine, `format-stamps` by the formatter worker and
// measured by the native client, `sha256` by the CAS and bounded by `ActionCachePrune`. While the
// table lived in `:engine` the client could not reach it, so `jk cache nuke`'s engine-unreachable
// fallback carried its own three-element array and left ten of the thirteen cached tiers on disk,
// reporting success. Rename a constant with the producers typing their own copy and the outcome is
// worse than a stale report: the producer keeps filling a directory the retention sweep now calls
// residue, or the sweep bounds a directory nothing writes.
//
// The ban list is READ FROM THE OWNER, not re-typed here: every enum constant's entry string in
// `CacheTree.java`. Add a tier and it is banned as a literal the same minute — a guard carrying its
// own copy of the vocabulary would be the third place to keep in sync, which is the defect it
// exists to prevent.
//
// Three homonyms are deliberately OUT of the list, each because another vocabulary owns the same
// characters and is free to diverge — the same call G12 makes for its seven single-word steps:
//   * `sha256` is the digest algorithm (TOML keys, JSON fields, Maven checksum extensions) and the
//     shard root `Cas` builds under BOTH the cache root and the artifact store's. `Cas` is that
//     name's owner; `CacheRetentionCoverageTest` pins the two spellings together instead.
//   * `generated` is also `build/generated`, a module output directory (`BuildLayout`), and a
//     plugin task's declared output.
//   * `projects` is also `<state>/builds/projects`, the durable build history (`ProjectBuilds`).
// `actions` stays banned despite being an ordinary word: nothing else in production spells it, and
// it was the largest cluster at 27 sites.
//
// A pure ban, not a ratchet: zero sites remain and there is nothing to allow. Reachability was
// measured, not assumed — `CacheTree` is on the host leaf, which every production module reaches
// (the plugin workers through `:plugin-sdk`, the rest through `:core` or a direct dependency), and
// the four modules that name a tier today are `:engine`, `:core`, `:cli` and two plugin workers.
//
// Scope is `src/main/java`. Test sources keep their literals on purpose, for the same reason G12
// and G13 do: a fixture that spells `.last-pruned` is a golden pinning the ON-DISK vocabulary, and
// one that borrowed the constant would follow a rename and still pass. The exception is
// `CacheRetentionCoverageTest`, which builds its fixtures through `CacheTree.under` deliberately —
// there the point is that the fixture and the bound move together; see its class javadoc.
// ---------------------------------------------------------------------------

/** Tier names another vocabulary also spells; see G15 above for who owns each. */
val tierNameHomonyms = setOf("sha256", "generated", "projects")

val checkNoBareTierName by tasks.registering {
    group = "verification"
    description = "Fail the build on a cache-tier directory typed as a literal (use cc.jumpkick.host.CacheTree)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/CacheTree.java")
    inputs.file(owner).withPropertyName("cacheTree")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-bare-tier-name.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // value -> constant, straight out of the owner (see G15 above).
        val named = Regex("""^\s{4}([A-Z][A-Z0-9_]*)\("([^"]+)"""", RegexOption.MULTILINE)
                .findAll(ownerFile.readText())
                .associate { it.groupValues[2] to it.groupValues[1] }
                .filterKeys { it !in tierNameHomonyms }
        if (named.size < 10) {
            throw GradleException("cc.jumpkick.host.CacheTree yielded only ${named.size} tier names,"
                    + " so the bare-tier-name guard has lost the owner it reads. Restore the enum's"
                    + " shape or retire this guard deliberately.")
        }

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            if (f == ownerFile) return@forEach
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            named.forEach { (value, constant) ->
                val n = countIn(code, Regex(Regex.escape("\"$value\"")))
                if (n > 0) hits.add("  $rel: $n x \"$value\"  ->  CacheTree.$constant")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A cache tier is named once, in cc.jumpkick.host.CacheTree"
                    + ". These re-type the name:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Use CacheTree.<TIER>.under(cacheRoot); it is on every production module's"
                    + " classpath. A directory that is NOT a cache tier — a module's own"
                    + " build/generated, say — must not borrow the constant either: give that"
                    + " vocabulary its own owner.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoBareTierName) }
tasks.named("jar") { dependsOn(checkNoBareTierName) }

// ---------------------------------------------------------------------------
// Guard G20: the host is read in one place, and paths join in one place.
//
// Defect it prevents: a predicate that is nearly right. `isWindows` had an owner and fourteen
// private copies, and the copies tested `os.name.contains("win")` where the owner tested
// `contains("windows")` — but "win" is a substring of **Darwin**, so every short copy called a Mac
// a Windows box. That was not theoretical: `BuildTool.binaryName()` handed `jk mvn` the string
// `mvn.cmd` on any JVM reporting `os.name=Darwin`, and the passthrough failed with "no such file"
// on a machine that had Maven installed. The mirror-image copies (`contains("mac")`) had the
// opposite hole — they miss `Darwin` — and `MemoryProbe` used one to decide whether to read macOS's
// `host_statistics64`, so a Darwin-reporting JVM silently fell back to the bean's idle-page figure
// and undersized every worker heap. Fourteen copies is fourteen chances to get the substring wrong,
// and none of them is visible from the others.
//
// Two arms, and they are deliberately different shapes.
//
//   1. `os.name` — a PURE BAN outside `cc.jumpkick.host.Os`, with no allowlist. This arm is on the
//      **property read**, not on the predicate derived from it, and that is the whole point.
//      An earlier G9 reported zero while missing 16 of 19 hand-rolled hex sites, because it matched
//      one derivation shape (`String.format("%02x")`) and could not see `HexFormat.of()`. The
//      derivations here are worse: `contains("win")`, `startsWith("Windows")`, `contains("mac")
//      || contains("darwin")`, and — in eight files — a `String os = ...` local read three
//      statements later, which no single-expression pattern can follow. There is exactly one thing
//      every copy must do first, and it is ask the JVM for the property. Ban that and the count is
//      bounded by the defect rather than by the pattern.
//
//      The test seams survive: `JkDirs`, `IntellijProbe`, `HomebrewProbe`, `IntellijSdkRegistrar`,
//      `IntellijJdkTable` and `OpenBrowser` all hand the host to a pure function so a test can pass
//      a synthetic one. They now hand it `Os.name()`, which is the same seam with an owner, so the
//      arm needs no exemption for them. `os.arch` is deliberately absent: `HostPlatform.mapArch` is
//      its only reader and there is nothing to converge.
//
//      The property name is READ FROM THE OWNER, not re-typed here — every `*_PROPERTY` constant in
//      `Os.java`. A second property this class starts owning is banned tree-wide the same minute,
//      which a copy in this script would not be. Same habit as G8/G12/G13.
//
//   2. The classpath separator — banned outside its OWNERS, plural, because the vocabulary
//      genuinely overlaps. `File.pathSeparator` is also `PATH`'s separator, and `PATH` is an
//      executable search path, not a class search path: it is joined by prepending a bin dir and
//      split against the filesystem, never handed to `-cp`. Classpaths owns the `-cp` vocabulary
//      and SearchPath owns `PATH`'s (blank entries kept — an empty entry is the current directory
//      on POSIX — nothing absolutised, order is precedence); both are exempt as declared input
//      files, so moving either fails loudly. This arm shipped as a six-entry ratchet while `PATH`
//      had no owner; SearchPath is that owner, and the ratchet below holds the sites still to be
//      swept onto it — when it empties, delete the map and the arm is a pure ban.
//
//      The banned spelling is READ FROM THE OWNER: `Classpaths.SEPARATOR`'s initialiser, plus the
//      `…Char` variant of it. `System.getProperty("path.separator")` is re-typed, and safely so —
//      it is the property `File.pathSeparator` is itself initialised from, so the two cannot drift.
//      Three spellings, because the copies used all three — and the property spelling is why the
//      first sweep counted eleven joiners and the second found twenty-five.
//
// Scope is `src/main/java`. Test sources are out for the reason G1 gives, and here it is load-
// bearing in both directions: `BuildToolTest`, `AtomicWritesTest`, `MacPrefsTest`,
// `NativeImageDriverTest`, `JdkFingerprintTest`, `EngineClientTest` and `EngineServerTest` all
// **spoof** `os.name` with `System.setProperty`, which is the only way to exercise the other host's
// branch, and a guard that banned the property in tests would delete the coverage that proves the
// predicate right.
// ---------------------------------------------------------------------------

/**
 * `PATH` sites not yet calling `cc.jumpkick.host.SearchPath`; see G20 arm 2. One join (prepend a
 * bin dir) and one split (walk the search path) — a one-line swap each. Delete the map when it
 * empties and the arm is a pure ban.
 */
val pathSeparatorRatchet = mapOf(
        "server/engine/src/main/java/cc/jumpkick/runtime/SourceProjectBuilder.java" to 1)

val checkSingleHostSurface by tasks.registering {
    group = "verification"
    description = "Fail the build on an unowned os.name read or classpath separator (use Os / Classpaths)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val osOwner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/Os.java")
    val cpOwner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/Classpaths.java")
    val spOwner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/SearchPath.java")
    inputs.file(osOwner).withPropertyName("osOwner")
    inputs.file(cpOwner).withPropertyName("classpathsOwner")
    inputs.file(spOwner).withPropertyName("searchPathOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val allowed = pathSeparatorRatchet
    val stamp = layout.buildDirectory.file("guards/single-host-surface.ok")
    outputs.file(stamp)
    doLast {
        val osOwnerFile = osOwner.asFile
        val separatorOwners = setOf(cpOwner.asFile, spOwner.asFile)

        // Arm 1's ban list, straight out of Os (see G20 above).
        val properties = Regex("""public static final String \w*_?PROPERTY = "([^"]+)";""")
                .findAll(osOwnerFile.readText())
                .map { it.groupValues[1] }
                .toList()
        if (properties.isEmpty()) {
            throw GradleException("cc.jumpkick.host.Os declares no *_PROPERTY constant, so the"
                    + " host-surface guard has lost the owner it reads. Restore the constant or"
                    + " retire this guard deliberately.")
        }

        // Arm 2's banned spelling, straight out of Classpaths.
        val sepConstant = Regex("""public static final String SEPARATOR = ([\w.]+);""")
                .find(cpOwner.asFile.readText())
                ?.groupValues
                ?.get(1)
                ?: throw GradleException("cc.jumpkick.host.Classpaths no longer initialises SEPARATOR"
                        + " from a named constant, so the host-surface guard has lost the spelling it"
                        + " reads. Restore it or retire this guard deliberately.")
        val separators = listOf(
                Regex(Regex.escape(sepConstant) + """\b"""),
                Regex(Regex.escape(sepConstant) + """Char\b"""),
                // Not read from the owner, and it cannot drift from it: this is the JDK property
                // File.pathSeparator is itself initialised from.
                Regex("""System\.getProperty\("path\.separator""""))

        val unownedOsReads = mutableListOf<String>()
        val sepHits = LinkedHashMap<String, Int>()
        mainJava.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val code = guardText(f.readText())
            if (f != osOwnerFile) {
                properties.forEach { prop ->
                    val pattern = Regex("""System\.getProperty\(""" + Regex.escape("\"$prop\""))
                    val n = countIn(code, pattern)
                    if (n > 0) unownedOsReads.add("  $rel: $n x System.getProperty(\"$prop\")")
                }
            }
            if (f !in separatorOwners) {
                val n = separators.sumOf { countIn(code, it) }
                if (n > 0) sepHits[rel] = n
            }
        }
        val (grew, unlisted, loose) = ratchetVerdict(sepHits, allowed, here)

        val problems = mutableListOf<String>()
        if (unownedOsReads.isNotEmpty()) {
            problems.add("The host is read in one place, cc.jumpkick.host.Os. These read"
                    + " the property themselves:\n"
                    + unownedOsReads.sorted().joinToString("\n")
                    + "\n  Ask Os.isWindows() / isDarwin() / isLinux(), or Os.name() when you need"
                    + " the raw string for a message or a test seam. Os is on the host leaf, which"
                    + " every production module reaches. Do NOT re-derive the predicate: a copy that"
                    + " tests contains(\"win\") calls Darwin a Windows box, which is the bug this"
                    + " guard exists to keep out.")
        }
        if (unlisted.isNotEmpty()) {
            problems.add("The separator has two owners, one per vocabulary: cc.jumpkick.host.Classpaths"
                    + " for -cp, cc.jumpkick.host.SearchPath for PATH. These name it"
                    + " themselves and are not on the ratchet:\n"
                    + unlisted.joinToString("\n")
                    + "\n  Call Classpaths.join(entries) / Classpaths.split(cp) for a classpath, or"
                    + " SearchPath.prepend(binDir, existing) / SearchPath.entries(path) for an"
                    + " executable search path — the two disagree about blank entries on purpose.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file on the path-separator ratchet may only shrink. These grew:\n"
                    + grew.joinToString("\n"))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        if (loose.isNotEmpty()) {
            logger.lifecycle("pathSeparatorRatchet is loose (these shrank — tighten it in this commit):")
            loose.forEach { logger.lifecycle(it) }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkSingleHostSurface) }
tasks.named("jar") { dependsOn(checkSingleHostSurface) }

// ---------------------------------------------------------------------------
// Guard G38: a toolchain env var is read from the request, not from the daemon.
//
// Defect it prevents: `JK_JDK=temurin-21 jk build` silently ignored. The engine is resident, so a
// `System.getenv("JK_JDK")` inside it answers from whichever shell started the daemon — possibly
// days earlier. Eight sites did that, and the user-visible consequence was not "the override is
// ignored" but "which JDK you compile against depends on how the daemon happened to be started",
// so `jk engine stop` changed build output. `BuildEnv` already layers `.env` -> request -> process
// and its own javadoc described this bug; the sites simply did not call it.
//
// Ban list is read from `BuildEnv.TOOLCHAIN`, never re-typed here, so adding a fourth
// request-scoped name extends the guard automatically.
//
// Scope: server/ and shared/ main sources only. `clients/` is exempt by *shape*, not by taste —
// the native CLI is a one-shot process running inside the caller's own shell, so there
// `System.getenv` IS the request. One narrow exemption inside the scope, keyed on the line that
// makes it correct rather than on a file name: a read that backs up `System.getProperty("java.home")`
// is asking which JVM this process runs on, which is genuinely ambient
// (`JavaHomes.runningJavaHome`).
// ---------------------------------------------------------------------------
val checkToolchainEnvFromRequest by tasks.registering {
    group = "verification"
    description = "Fail the build on a toolchain env read that bypasses BuildEnv (use BuildEnv.forModule/ambient)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/core/src/main/java/cc/jumpkick/config/BuildEnv.java")
    inputs.file(owner).withPropertyName("buildEnvOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val stamp = layout.buildDirectory.file("guards/toolchain-env-from-request.ok")
    outputs.file(stamp)
    doLast {
        // The ban list, straight out of the owner.
        val names = Regex("""public static final List<String> TOOLCHAIN = List\.of\(([^)]*)\);""")
                .find(owner.asFile.readText())
                ?.groupValues?.get(1)
                ?.let { Regex("\"([^\"]+)\"").findAll(it).map { m -> m.groupValues[1] }.toList() }
                ?: emptyList()
        if (names.isEmpty()) {
            throw GradleException("BuildEnv.TOOLCHAIN no longer declares its names as a List.of(...)"
                    + " literal, so guard G38 has lost the owner it reads. Restore it or retire the"
                    + " guard deliberately.")
        }

        // clients/ is the caller's own shell; there System.getenv is the request.
        if (here.startsWith("clients/")) {
            stamp.get().asFile.also { it.parentFile.mkdirs() }.writeText("skipped: clients/\n")
            return@doLast
        }

        val offenders = mutableListOf<String>()
        var scanned = 0
        var exempted = 0
        mainJava.forEach { f ->
            scanned++
            val lines = f.readText().lines()
            lines.forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                for (n in names) {
                    if (!line.contains("System.getenv(\"" + n + "\")")) continue
                    // Exempt by shape: a fallback for this process's own java.home.
                    val window = lines.subList(maxOf(0, i - 8), i).joinToString(" ")
                    if (window.contains("System.getProperty(\"java.home\")")) { exempted++; continue }
                    offenders += f.relativeTo(treeRoot).invariantSeparatorsPath + ":" + (i + 1) + "  " + line
                }
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("G38: toolchain env read outside BuildEnv in " + here + " —\n  "
                    + offenders.joinToString("\n  ")
                    + "\n\nThese select a toolchain, so on a resident engine System.getenv answers from"
                    + " the shell that started the daemon. Use BuildEnv.forModule(dir) where a module"
                    + " directory is in hand, or BuildEnv.ambient() where none is."
                    + "\nBan list read from BuildEnv.TOOLCHAIN: " + names.joinToString(", "))
        }
        stamp.get().asFile.also { it.parentFile.mkdirs() }
                .writeText("ok: " + scanned + " files, " + names.size + " names, " + exempted + " shape-exempt\n")
    }
}
tasks.named("check") { dependsOn(checkToolchainEnvFromRequest) }
tasks.named("jar") { dependsOn(checkToolchainEnvFromRequest) }

// ---------------------------------------------------------------------------
// Guard G43: an archive's byte sink comes from DeterministicZip.
//
// Defect it prevents: a 512-byte write buffer on every jar jk produces. ZipOutputStream inherits
// DeflaterOutputStream's 512-byte buffer, so a 9 MB jar became ~18,000 write(2) calls where 64 KB
// gives ~143. On NTFS each traverses the full filter stack, and an AV minifilter hooking writes
// rather than closes sees every one. DeterministicZip already owned entry writing — timestamps,
// order, compression — but not stream construction, so all twelve writers re-decided buffering
// independently and all twelve decided wrong. Exactly one BufferedOutputStream existed in the whole
// product, and it was the Windows console wrapper.
//
// Two arms, both keyed on shape rather than on a file list:
//   A. `new (Zip|Jar)OutputStream(Files.newOutputStream(...))` — unbuffered, on one line.
//   B. a file that builds an archive AND opens a file stream must name DeterministicZip's sink, so
//      the two-statement spelling (`OutputStream out = Files.newOutputStream(p); ... new
//      JarOutputStream(out)`) cannot slip past arm A.
// A file that writes an archive to memory (a ByteArrayOutputStream) trips neither, which is why
// arm B keys on Files.newOutputStream being present rather than on the archive alone.
// ---------------------------------------------------------------------------
val checkArchiveStreamOwner by tasks.registering {
    group = "verification"
    description = "Fail the build on an archive stream that bypasses DeterministicZip (use archiveStream/newArchive)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/DeterministicZip.java")
    inputs.file(owner).withPropertyName("deterministicZipOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/archive-stream-owner.ok")
    outputs.file(stamp)
    doLast {
        // Self-fail: the owner must still offer the sink this guard points callers at.
        val ownerText = owner.asFile.readText()
        if (!ownerText.contains("public static OutputStream archiveStream(")) {
            throw GradleException("DeterministicZip no longer declares archiveStream(...), so guard G43"
                    + " has lost the owner it points callers at. Restore it or retire the guard"
                    + " deliberately.")
        }

        val ctor = Regex("""new\s+(Zip|Jar)OutputStream\s*\(""")
        val unbuffered = Regex("""new\s+(Zip|Jar)OutputStream\s*\(\s*Files\.newOutputStream""")
        val offenders = mutableListOf<String>()
        var scanned = 0
        var archives = 0
        mainJava.forEach { f ->
            scanned++
            if (f.absolutePath == owner.asFile.absolutePath) return@forEach
            val text = f.readText()
            if (!ctor.containsMatchIn(text)) return@forEach
            archives++
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath

            // Arm A — the one-line unbuffered spelling.
            text.lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                if (unbuffered.containsMatchIn(line)) offenders += "$rel:${i + 1}  $line"
            }

            // Arm B — an archive written to a file must take its sink from the owner.
            if (text.contains("Files.newOutputStream") && !text.contains("DeterministicZip.archiveStream")
                    && !text.contains("DeterministicZip.newArchive")) {
                offenders += "$rel  builds an archive over Files.newOutputStream without DeterministicZip's sink"
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("G43: unbuffered archive stream —\n  " + offenders.joinToString("\n  ")
                    + "\n\nZipOutputStream buffers at 512 bytes. Take the sink from the one owner:"
                    + " new JarOutputStream(DeterministicZip.archiveStream(path)), or"
                    + " DeterministicZip.newArchive(path) when a ZipOutputStream will do.")
        }
        stamp.get().asFile.also { it.parentFile.mkdirs() }
                .writeText("ok: " + scanned + " files, " + archives + " building archives\n")
    }
}
tasks.named("check") { dependsOn(checkArchiveStreamOwner) }
tasks.named("jar") { dependsOn(checkArchiveStreamOwner) }

// ---------------------------------------------------------------------------
// Guard G39: cheapest rejection first in a walk's filter chain.
//
// Defect it prevents: paying a stat for every entry a free string test was about to discard. The
// walk already read each entry's attributes, and `Files::isRegularFile` re-resolves the path from
// scratch (10.3 us on NTFS against 1.0 on ext4). `BaseJre.findJava` stat'ed every file in a
// ~20,000-file unpacked JRE before a `getFileName().equals("java")` rejected almost all of them —
// ~206 ms of Windows stat time to find one file. `ImageBuilder` gated an in-memory Set lookup
// behind a syscall.
//
// The rule was already written down four times -- CasPrewriter ("cheapest rejections first"),
// JarPackager, AssemblyPackager, ClasspathFingerprint -- and obeyed by exactly one plugin of
// fifteen. That is why it needs a guard and not a fifth comment.
//
// Measured against 24 violating sites when it landed; zero after the reorder pass.
// ---------------------------------------------------------------------------
val checkCheapestRejectionFirst by tasks.registering {
    group = "verification"
    description = "Fail the build on an isRegularFile filter that precedes a free name-only predicate"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/cheapest-rejection-first.ok")
    outputs.file(stamp)
    doLast {
        val nameOnly = Regex("""getFileName|endsWith\(|startsWith\(|\.equals\(""")
        val offenders = mutableListOf<String>()
        var scanned = 0
        var chains = 0
        mainJava.forEach { f ->
            scanned++
            val lines = f.readText().lines()
            lines.forEachIndexed { i, raw ->
                if (!raw.trimEnd().endsWith(".filter(Files::isRegularFile)")) return@forEachIndexed
                chains++
                // The next line that is neither blank nor a comment.
                var j = i + 1
                while (j < lines.size && (lines[j].isBlank() || lines[j].trim().startsWith("//"))) j++
                if (j >= lines.size) return@forEachIndexed
                val next = lines[j].trim()
                if (!next.startsWith(".filter(")) return@forEachIndexed
                // A predicate that touches the filesystem is legitimately ordered after the stat.
                if (next.contains("Files.")) return@forEachIndexed
                if (!nameOnly.containsMatchIn(next)) return@forEachIndexed
                offenders += f.relativeTo(treeRoot).invariantSeparatorsPath + ":" + (i + 1) + "  " + next
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("G39: a stat runs before a free name test —\n  "
                    + offenders.joinToString("\n  ")
                    + "\n\nPut the string predicate first. The walk already paid for the entry;"
                    + " Files::isRegularFile re-resolves the path for a fresh stat, so ordering it"
                    + " first spends a syscall on every entry the name test was going to reject.")
        }
        stamp.get().asFile.also { it.parentFile.mkdirs() }
                .writeText("ok: " + scanned + " files, " + chains + " isRegularFile filters\n")
    }
}
tasks.named("check") { dependsOn(checkCheapestRejectionFirst) }
tasks.named("jar") { dependsOn(checkCheapestRejectionFirst) }

// ---------------------------------------------------------------------------
// Guard G40: executability is asked through PathUtil.isRunnable.
//
// Defect it prevents: the single most expensive filesystem predicate jk uses. Files.isExecutable
// measures 33.4 us on Windows against 0.52 on Linux -- 64x -- because the JDK implements EXECUTE
// access there as a security-descriptor read plus an AccessCheck. Windows has no executable bit;
// what decides whether a file runs is its extension, so the check answers an expensive question
// nobody asked. Thirteen sites paid it, two inside directory listings, and AotCacheTrainer probed
// four spellings per PATH entry for three tools -- ~20 ms of pure access checks per call.
//
// One exemption, and it is the interesting one: ActionCache.executableBit deliberately keeps
// Files.isExecutable because it asks a different question -- "is there a bit worth recording for
// restore" -- and isRunnable answering true for a .exe would promise a bit File.setExecutable
// cannot set on Windows. It is already guarded by !Os.isWindows(), so it never pays the 64x call
// on the platform where it costs. Keyed on that guard rather than on the file name.
// ---------------------------------------------------------------------------
val checkRunnableOwner by tasks.registering {
    group = "verification"
    description = "Fail the build on a Files.isExecutable outside PathUtil.isRunnable"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/PathUtil.java")
    inputs.file(owner).withPropertyName("runnableOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/runnable-owner.ok")
    outputs.file(stamp)
    doLast {
        if (!owner.asFile.readText().contains("public static boolean isRunnable(")) {
            throw GradleException("PathUtil no longer declares isRunnable(Path), so guard G40 has lost"
                    + " the owner it points callers at. Restore it or retire the guard deliberately.")
        }
        val offenders = mutableListOf<String>()
        var scanned = 0
        var exempted = 0
        mainJava.forEach { f ->
            scanned++
            if (f.absolutePath == owner.asFile.absolutePath) return@forEach
            f.readText().lines().forEachIndexed { i, raw ->
                val line = raw.trim()
                if (line.startsWith("//") || line.startsWith("*")) return@forEachIndexed
                if (!line.contains("Files.isExecutable(")) return@forEachIndexed
                // Exempt by shape: already skipped on the platform where the call is 64x, because
                // the caller wants the POSIX bit itself rather than "can this host run it".
                if (line.contains("!Os.isWindows()") || line.contains("!WINDOWS")) { exempted++; return@forEachIndexed }
                offenders += f.relativeTo(treeRoot).invariantSeparatorsPath + ":" + (i + 1) + "  " + line
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("G40: Files.isExecutable outside its owner —\n  "
                    + offenders.joinToString("\n  ")
                    + "\n\nUse PathUtil.isRunnable(path): an access check off Windows, an extension"
                    + " test on it. Keep Files.isExecutable only when you want the POSIX bit itself,"
                    + " and then guard it with !Os.isWindows() as ActionCache.executableBit does.")
        }
        stamp.get().asFile.also { it.parentFile.mkdirs() }
                .writeText("ok: " + scanned + " files, " + exempted + " shape-exempt\n")
    }
}
tasks.named("check") { dependsOn(checkRunnableOwner) }
tasks.named("jar") { dependsOn(checkRunnableOwner) }

// ---------------------------------------------------------------------------
// Guard G42: a tree copy goes through PathUtil.copyTree.
//
// Mirrors G37, which did this for deletes, and for the same reason: twelve callers hand-rolled a
// tree copy and all twelve shared the same three defects — createDirectories per *file* instead of
// per directory, no byte-identity check, and the walk's attributes discarded so isDirectory /
// isRegularFile re-stat what the walk already knew. The most sophisticated of them created
// directories correctly in preVisitDirectory and then still called createDirectories(dest.getParent())
// per file.
//
// The identity check is a correctness property, not a saving: re-copying bumps mtime, FreshnessStamp
// compares classpath entries by mtime, and ActionCache.restoreArtifacts' own comment records that
// re-copying an unchanged jar forced a full KSP round on every build. Ten of the twelve could do that
// to a class or resource tree.
//
// Shape: a Files.copy(...) whose enclosing method also walks a tree. That is the hand-rolled copy;
// a single-file copy, and a flat first-wins merge over Files.list, are not tree copies and do not
// trip it.
// ---------------------------------------------------------------------------
val checkTreeCopyOwner by tasks.registering {
    group = "verification"
    description = "Fail the build on a hand-rolled recursive copy (use PathUtil.copyTree)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/PathUtil.java")
    inputs.file(owner).withPropertyName("copyTreeOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/tree-copy-owner.ok")
    outputs.file(stamp)
    doLast {
        val ownerText = owner.asFile.readText()
        if (!ownerText.contains("public static void copyTree(")) {
            throw GradleException("PathUtil no longer declares copyTree(...), so guard G42 has lost the"
                    + " owner it points callers at. Restore it or retire the guard deliberately.")
        }
        // Commented exemptions, each a copy that is deliberately not the owner's shape.
        val allowed = setOf(
                // Rewrites each path segment as it goes (leading '/' and '.' handling) and refuses to
                // recreate symlinks; the owner copies a tree verbatim and has no segment policy.
                "server/engine/src/main/java/cc/jumpkick/giter8/PluginTemplates.java")
        val offenders = mutableListOf<String>()
        var scanned = 0
        var copies = 0
        mainJava.forEach { f ->
            scanned++
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (f.absolutePath == owner.asFile.absolutePath || rel in allowed) return@forEach
            val lines = f.readText().lines()
            lines.forEachIndexed { i, raw ->
                if (!raw.contains("Files.copy(")) return@forEachIndexed
                copies++
                // The shape of a *tree* copy: the target is rebuilt from a walk-relative path. A
                // single-file copy, an archive-entry extraction, and a flat first-wins merge over
                // Files.list all copy without relativizing, and none of them is what this bans.
                val window = lines.subList(maxOf(0, i - 6), i + 1).joinToString("\n")
                if (!window.contains("relativize(")) return@forEachIndexed
                offenders += rel + ":" + (i + 1)
            }
        }
        if (offenders.isNotEmpty()) {
            throw GradleException("G42: a hand-rolled recursive copy —\n  " + offenders.joinToString("\n  ")
                    + "\n\nUse PathUtil.copyTree(from, to): directories created once per directory,"
                    + " attributes taken from the walk, and a byte-identical target left alone so its"
                    + " mtime does not invalidate every downstream FreshnessStamp."
                    + "\nA copy that is genuinely not a tree copy belongs on this guard's exemption"
                    + " list with the reason, the way G37 lists its four.")
        }
        stamp.get().asFile.also { it.parentFile.mkdirs() }
                .writeText("ok: " + scanned + " files, " + copies + " using Files.copy\n")
    }
}
tasks.named("check") { dependsOn(checkTreeCopyOwner) }
tasks.named("jar") { dependsOn(checkTreeCopyOwner) }

// ---------------------------------------------------------------------------
// Guard G45: blind tree walks only shrink.
//
// The tree asked 1,194 metadata predicates (exists / isRegularFile / isDirectory) against 17
// readAttributes -- seventy narrow questions for every time it asked once for the whole answer --
// and had 5 attribute-carrying walks against 233 blind ones. On Windows a raw walk is *cheaper*
// than on Linux, because FindNextFileW returns each entry's attributes with the entry; the cost is
// discarding them and re-resolving the path to ask again, at 10.3 us against 1.0 on ext4.
// An earlier pass named `walk(...).filter(Files::isRegularFile)`, fixed it in seven hot walkers, and it had
// regrown to 72 sites -- because the fix was a call-site edit and never became an owner.
//
// A ratchet, not a ban, and deliberately so. Two hundred and twenty sites remain and each needs its
// own read: some walks want directories, some want a depth limit, some want ordering. The ticket's
// own guidance applies -- ship the ratchet and make the rule monotonic today rather than leave an
// unwinnable ban unwritten. Delete entries from walk-baseline.txt as sites migrate.
//
// Owner: PathUtil.forEachRegularFile.
// ---------------------------------------------------------------------------
val checkBlindWalkRatchet by tasks.registering {
    group = "verification"
    description = "Fail the build when a module gains a blind Files.walk (use PathUtil.forEachRegularFile)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val baseline = rootProject.layout.projectDirectory.file("walk-baseline.txt")
    inputs.file(baseline).withPropertyName("walkBaseline")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/PathUtil.java")
    inputs.file(owner).withPropertyName("walkOwner")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath
    val stamp = layout.buildDirectory.file("guards/blind-walk-ratchet.ok")
    outputs.file(stamp)
    doLast {
        if (!owner.asFile.readText().contains("public static void forEachRegularFile(")) {
            throw GradleException("PathUtil no longer declares forEachRegularFile(...), so guard G45"
                    + " has lost the owner it points callers at. Restore it or retire the guard"
                    + " deliberately.")
        }
        val allowed = baseline.asFile.readLines()
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .associate { line ->
                    val parts = line.trim().split(" ")
                    parts[0] to parts[1].toInt()
                }
        if (allowed.isEmpty()) {
            throw GradleException("walk-baseline.txt lists no modules, so guard G45 would pass over"
                    + " anything. Restore the baseline or retire the guard deliberately.")
        }
        val pattern = Regex("""Files\.(walk|walkFileTree|newDirectoryStream|list)\(""")
        var found = 0
        mainJava.forEach { f -> found += pattern.findAll(f.readText()).count() }
        val budget = allowed[here] ?: 0
        if (found > budget) {
            throw GradleException("G45: " + here + " has " + found + " blind tree walks, baseline "
                    + budget + " (+" + (found - budget) + ")."
                    + "\n\nUse PathUtil.forEachRegularFile(root, (file, attrs) -> …): the walk already"
                    + " read each entry's attributes, and re-resolving the path to ask again is the"
                    + " dominant cost of walking a large tree."
                    + "\nIf a walk genuinely cannot use it (it needs directories, a depth limit, or"
                    + " ordering), raise this module's line in walk-baseline.txt in the same change and"
                    + " say which site and why.")
        }
        if (found < budget) {
            throw GradleException("G45: " + here + " is down to " + found + " blind tree walks from a"
                    + " baseline of " + budget + " — lower the line in walk-baseline.txt so the ratchet"
                    + " tightens. A baseline that lags the tree is the same defect as a registry that"
                    + " lags the code.")
        }
        stamp.get().asFile.also { it.parentFile.mkdirs() }
                .writeText("ok: " + found + " blind walks, at baseline\n")
    }
}
tasks.named("check") { dependsOn(checkBlindWalkRatchet) }
tasks.named("jar") { dependsOn(checkBlindWalkRatchet) }

// ---------------------------------------------------------------------------
// Guard G16: Maven Central is addressed one way, and it is `RepositorySpec`'s.
//
// Defect it prevents: traffic to Central that the rate-limit machinery cannot see. `repo1.maven.org`
// is a CNAME for `repo.maven.apache.org`, so it fetches the same bytes — but `CentralMirror` and
// `HostCooldown` both key on the canonical host, and a request addressed to the alias matches
// neither. Three engine paths did exactly that (`Giter8Maven`, `HardwareProbe`, `BuildLogicGroovyHost`,
// the last two issuing four and five GETs a run), each through its own `HttpClient`, so a 429 they
// provoked opened no window, tripped no cooldown, and rerouted nothing. The resolver then hit a quota
// it had not spent. Swapping the constant alone would not have fixed it: the alias and the private
// transport are one defect with two halves, and this guard covers the half a text scan can see.
//
// The name is the other half. `central` is the `repos/<name>/` store directory, the lockfile
// `source` prefix and the repo-group entry all at once, so a store written under one spelling and
// read under another is a cache that silently never hits.
//
// The ban list is READ FROM THE OWNER, not re-typed here: every `public static final String` in
// `RepositorySpec.java`, plus the URL inside `MAVEN_CENTRAL`'s initializer. Add a constant there
// and it is banned as a literal the same minute — `GOOGLE` and `JUMPKICK_NAME` entered the list
// exactly that way when their names stopped being literals inside their own initializers. The
// alias is the one string that is NOT in the owner and is banned anyway — it has to be, because
// the whole point is that it must never appear.
//
// Two exemptions are shapes, not line lists. A reader of someone else's build file:
// `GradleImporter`, `PomImporter` and `GradleExporter` recognise the repository a Maven or Gradle
// user declared, and that user may well have typed the alias — so those three must keep matching
// every spelling, including the one jk itself must never emit. That is a different vocabulary and
// it is free to diverge, exactly like GraalVM's `native-image` launcher under G12. And a package
// path segment: `Path.of("cc", "jumpkick", ...)` spells the group's directory, not the repo name,
// so a value sitting immediately after `"cc",` is skipped by lookbehind rather than by filename —
// two sites today (`PlannerResources`, `TaskForecaster`), and a third appears pre-exempted.
//
// The formatter style named `google` (`FormatStyles.JAVA_STYLES`, `CodeFormatter`'s ktfmt switch)
// is the same spelling in a different spec — judged per G21's rule, so those two files are exempt
// BY FILE with the reason held here. Measured when the names entered the ban list: five bare
// repo-name literals in `src/main/java` (one `"google"`, four `"jumpkick"`), three format-style
// hits that must stay, two package segments.
//
// Scope is `src/main/java`. Test sources keep their literals on purpose: a fixture that stands up a
// fake Central and asserts on the URL is pinning the OUTBOUND value, and borrowing the constant
// would make a change to it invisible to the suite.
// ---------------------------------------------------------------------------

/** Files that recognise a foreign build file's declared repository. See G16 above. */
val foreignRepoReaders = setOf(
        "server/toolchain/src/main/java/cc/jumpkick/gradle/GradleImporter.java",
        "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java",
        "shared/toolchain-jdk/src/main/java/cc/jumpkick/gradle/GradleExporter.java")

/**
 * Files whose `"google"` is a formatter style name (ktfmt / google-java-format), not a repository —
 * same spelling, different spec, so they are exempt by file. See G16 above.
 */
val formatStyleVocabulary = setOf(
        "shared/core/src/main/java/cc/jumpkick/config/FormatStyles.java",
        "plugins/formatter/src/main/java/cc/jumpkick/format/CodeFormatter.java")

/**
 * Repo-name literals not yet calling `RepositorySpec` — `PluginJar.OFFICIAL_REPO` is a second
 * owner of `"jumpkick"` to be deleted, `RepoGroupBuilder` matches `"google"` inline. Both are a
 * one-line swap in `:engine`. Delete the map when it empties and the names are a pure ban.
 */
val repoNameRatchet = mapOf(
        "server/engine/src/main/java/cc/jumpkick/engine/plugin/PluginJar.java" to 1,
        "server/engine/src/main/java/cc/jumpkick/runtime/RepoGroupBuilder.java" to 1)

/** The host that resolves to Central but matches neither the mirror nor the cooldown. */
val centralAliasHost = "repo1.maven.org"

val checkSingleCentralAddress by tasks.registering {
    group = "verification"
    description = "Fail the build on a Central URL or repo name typed as a literal (use RepositorySpec)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/jk-api/src/main/java/cc/jumpkick/model/RepositorySpec.java")
    inputs.file(owner).withPropertyName("repositorySpec")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val here = layout.projectDirectory.asFile.relativeTo(treeRoot).invariantSeparatorsPath + "/"
    val exempt = foreignRepoReaders + formatStyleVocabulary
    val allowed = repoNameRatchet
    val alias = centralAliasHost
    val stamp = layout.buildDirectory.file("guards/single-central-address.ok")
    outputs.file(stamp)
    doLast {
        val ownerText = owner.asFile.readText()
        // value -> constant, straight out of the owner (see G16 above).
        val named = Regex("""public static final String (\w+) = "([^"]+)";""")
                .findAll(ownerText)
                .associate { it.groupValues[2] to it.groupValues[1] }
                .toMutableMap()
        val centralUrl = Regex("""MAVEN_CENTRAL\s*=\s*new RepositorySpec\([^;]*?URI\.create\("([^"]+)"\)""")
                .find(ownerText)
                ?.groupValues
                ?.get(1)
                ?: throw GradleException("cc.jumpkick.model.RepositorySpec no longer builds"
                        + " MAVEN_CENTRAL from a URI literal, so the Central-address guard has lost"
                        + " the owner it reads. Restore it or retire this guard deliberately.")
        named[centralUrl] = "MAVEN_CENTRAL.url()"

        val aliasHits = mutableListOf<String>()
        val nameCounts = LinkedHashMap<String, Int>()
        val nameDetails = LinkedHashMap<String, MutableList<String>>()
        mainJava.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (f == owner.asFile || rel in exempt) return@forEach
            val code = guardText(f.readText())
            val aliased = countIn(code, Regex(Regex.escape(alias)))
            if (aliased > 0) {
                aliasHits.add("  $rel: $aliased x $alias  ->  RepositorySpec.MAVEN_CENTRAL.url()")
            }
            named.forEach { (value, constant) ->
                // A value sitting immediately after `"cc",` is a package path segment
                // (Path.of("cc", "jumpkick", ...)), not a repository name — see G16 above.
                val n = countIn(code, Regex("""(?<!"cc",)""" + Regex.escape("\"$value\"")))
                if (n > 0) {
                    nameCounts.merge(rel, n, Int::plus)
                    nameDetails
                            .getOrPut(rel) { mutableListOf() }
                            .add("  $rel: $n x \"$value\"  ->  RepositorySpec.$constant")
                }
            }
        }
        val (grew, unlisted, loose) = ratchetVerdict(nameCounts, allowed, here)

        val problems = mutableListOf<String>()
        if (aliasHits.isNotEmpty() || unlisted.isNotEmpty()) {
            val offending = unlisted.map { it.trim().substringAfter("  ") }
            val details = nameDetails.filterKeys { it in offending }.values.flatten()
            problems.add("Maven Central is addressed once, through"
                    + " cc.jumpkick.model.RepositorySpec.MAVEN_CENTRAL, and a repository name is"
                    + " spelled once, in RepositorySpec. These spell it themselves:\n"
                    + (aliasHits + details).sorted().joinToString("\n")
                    + "\n  " + alias + " is a CNAME for the canonical host, so CentralMirror and"
                    + " HostCooldown match neither it nor the traffic sent to it — and reaching"
                    + " Central at all outside cc.jumpkick.http.Http misses both regardless of the"
                    + " hostname. Use the constant AND the shared transport. A reader of someone"
                    + " else's build file, which must recognise every spelling a user might have"
                    + " typed, is one exemption (foreignRepoReaders); a formatter style that shares"
                    + " a repo's spelling is the other (formatStyleVocabulary). Add to either only"
                    + " with a reason.")
        }
        if (grew.isNotEmpty()) {
            problems.add("A file on the repo-name ratchet may only shrink. These grew:\n"
                    + grew.joinToString("\n"))
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        if (loose.isNotEmpty()) {
            logger.lifecycle("repoNameRatchet is loose (these shrank — tighten it in this commit):")
            loose.forEach { logger.lifecycle(it) }
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkSingleCentralAddress) }
tasks.named("jar") { dependsOn(checkSingleCentralAddress) }

// ---------------------------------------------------------------------------
// Guard G17: a wire message type is named once, in `EngineProtocol`.
//
// Defect it prevents: the silent unhandled message. A JSONL `"type"` discriminator is a
// producer/consumer contract with no compiler behind it — the engine writes `task-finish`, the CLI
// and the dashboard switch on it — and when both ends type the string, a typo is not a build error,
// it is an event nobody handles and nobody reports. `EngineProtocol` has owned these tokens all
// along and 482 references already went through it; five files typed 35 of them anyway, and the
// SSE/JSONL renderers were the worst of it because their strings are read by a browser rather than
// by a Java `switch`, so a mismatch produces a dashboard row that quietly never updates.
//
// The ban list is READ FROM THE OWNER, not re-typed here: every `public static final String` in
// `EngineProtocol.java` whose value contains a hyphen. Add a token and it is banned as a literal
// the same minute — a guard carrying its own copy of the vocabulary would be the second place to
// keep in sync, which is the defect it exists to prevent.
//
// The single-word values are deliberately OUT of the list, for the reason G12 leaves `install` and
// `train` out: `type`, `status`, `error`, `output`, `warn`, `label`, `progress`, `auth`, `ping`,
// `eta` and `heartbeat` are ordinary English that appears in field names, help text and log lines,
// and banning them by text scan would be false positives all the way down. `SINGLE_PLAN_DIR` is the
// empty string and is excluded by the same filter. The 110 hyphenated tokens are unambiguous:
// nothing else in the tree spells `freshen-catalog-ack`.
//
// Two neighbouring vocabularies are free to diverge and must not borrow these constants. The
// dashboard's SSE stream has four frame names of its own (`request-start`, `request-finish`,
// `run-snapshot`, `plan`) and the CLI transcript has three (`session-start`, `session-finish`,
// `workspace-start`); none is a socket-protocol token, so none is on the list. The guard says
// nothing about them, which is the correct outcome, not a gap.
//
// One divergence the sweep found and deliberately did not close: a plan diagnostic is
// `EngineProtocol.ERROR_LINE` (`error-line`) on the socket and plain `error` in the CLI's `--json`
// stream and on SSE. Those are two names for one event, but reconciling them changes a documented
// output contract, so `"error"` stays a literal at those sites rather than borrowing whichever
// constant happens to match by value.
//
// A pure ban, not a ratchet: zero sites remain and there is nothing to allow.
//
// Scope is `src/main/java`. Test sources keep their literals on purpose, for the same reason G12
// and G13 leave theirs: an assertion that the stream carried `workspace-finish` is a golden pinning
// the WIRE vocabulary, and rewriting it to the constant would make a rename of the value invisible
// to the whole suite — every test would follow the rename and still pass.
// ---------------------------------------------------------------------------
val checkNoBareWireType by tasks.registering {
    group = "verification"
    description = "Fail the build on a wire message type typed as a literal (use EngineProtocol)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/wire/src/main/java/cc/jumpkick/engine/protocol/EngineProtocol.java")
    inputs.file(owner).withPropertyName("engineProtocol")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-bare-wire-type.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // value -> constant, for the hyphenated tokens only (see G17 above).
        val named = Regex("""public static final String (\w+) = "([^"]*)";""")
                .findAll(ownerFile.readText())
                .map { it.groupValues[2] to it.groupValues[1] }
                .filter { (value, _) -> value.contains('-') }
                .toMap()
        if (named.isEmpty()) {
            throw GradleException("cc.jumpkick.engine.protocol.EngineProtocol no longer declares any"
                    + " hyphenated token, so the wire-type guard has lost the owner it reads."
                    + " Restore it or retire this guard deliberately.")
        }

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            if (f == ownerFile) return@forEach
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            named.forEach { (value, constant) ->
                val n = countIn(code, Regex(Regex.escape("\"$value\"")))
                if (n > 0) hits.add("  $rel: $n x \"$value\"  ->  EngineProtocol.$constant")
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A wire message type typed as a literal is a producer/consumer"
                    + " contract with no compiler behind it — a typo becomes an event nobody"
                    + " handles, not a build error. These name one by hand:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Reference cc.jumpkick.engine.protocol.EngineProtocol instead. A string"
                    + " that is NOT a socket-protocol type — a dashboard-only SSE frame, a CLI"
                    + " transcript envelope — must not borrow the constant either: that vocabulary"
                    + " is free to diverge and keeps its own literal.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoBareWireType) }
tasks.named("jar") { dependsOn(checkNoBareWireType) }

// ---------------------------------------------------------------------------
// Guard G18: jk's build output directory is named once, in `BuildLayout.TARGET`.
//
// Defect it prevents: a scanner and a builder disagreeing about where output lives. `target/` is
// not only where jk writes — it is what nine separate "skip this directory" tests compare against
// (`GlobSet`, `TestSuites`, `PathSourceMaterializer`, `FormatPlans`, `WorkspaceFileAccess`,
// `NewParentDirGuess`, `JUnitLauncher`, `PomRuntimeClasspath`, `WorkerLaunchClasspath`), plus what
// `jk clean` deletes and what the preflight memo stats. Twenty-six sites spelled it themselves,
// including `NativePreflight`, which sat in `BuildLayout`'s own package and re-derived the whole
// workspace-member rule — the central-out-tree branch and all — rather than calling
// `moduleTargetDir`. Two copies of a layout rule is one rename away from a formatter walking a
// tree the compiler no longer writes to, or `jk clean` leaving the outputs behind.
//
// A name constant, not just the accessors: `moduleTargetDir` answers "where does this module write"
// and most of the bypasses were asking "is this directory named target", which no accessor can
// answer. Both now come from the same string.
//
// Five exemptions, each a different vocabulary that happens to spell the same seven characters —
// the same call G12 makes for GraalVM's `native-image` launcher:
//   * `ToolInstallCommand` / `ToolRunCommand` — the CLI parameter named `target` (a coordinate, a
//     file, a directory or a git URL). It is a user-facing argument name, not a path segment.
//   * `GroovyCompiler` — groovyc's `target` option (a bytecode level). Doubly exempt: it is a
//     forked plugin worker and cannot reach `:core` at all.
//   * `PomImporter` — Maven's `<target>` compiler configuration key, in someone else's file.
//   * `BspServer` — the `target` field of a BSP request, in someone else's protocol.
//   * `TestEnvValues` — the `${target}` interpolation variable name. It expands TO the output
//     directory, but the token is a variable name in `jk.toml`, and renaming the directory must
//     not silently rename the variable users wrote.
//
// Scope is `src/main/java`. Test sources keep their literals on purpose, for the same reason G13's
// do: a fixture that writes `target/classes/main` and asserts the compiler found it is pinning the
// ON-DISK layout, and borrowing the constant would make a move of that layout invisible.
// ---------------------------------------------------------------------------

/** Files where `target` means something other than jk's output directory. See G18 above. */
val targetHomonyms = setOf(
        "clients/cli/src/main/java/cc/jumpkick/cli/bsp/BspServer.java",
        "clients/cli/src/main/java/cc/jumpkick/command/ToolInstallCommand.java",
        "clients/cli/src/main/java/cc/jumpkick/command/ToolRunCommand.java",
        "plugins/groovy-compiler/src/main/java/cc/jumpkick/groovy/compiler/GroovyCompiler.java",
        "server/toolchain/src/main/java/cc/jumpkick/mvn/PomImporter.java",
        "shared/core/src/main/java/cc/jumpkick/config/TestEnvValues.java")

val checkNoBareTargetDir by tasks.registering {
    group = "verification"
    description = "Fail the build on jk's output directory typed as a literal (use BuildLayout.TARGET)"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/core/src/main/java/cc/jumpkick/layout/BuildLayout.java")
    inputs.file(owner).withPropertyName("buildLayout")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val exempt = targetHomonyms
    val stamp = layout.buildDirectory.file("guards/no-bare-target-dir.ok")
    outputs.file(stamp)
    doLast {
        // The banned name, straight out of the owner (see G18 above).
        val value = Regex("""public static final String TARGET = "([^"]+)";""")
                .find(owner.asFile.readText())
                ?.groupValues
                ?.get(1)
                ?: throw GradleException("cc.jumpkick.layout.BuildLayout no longer declares TARGET,"
                        + " so the output-directory guard has lost the owner it reads. Restore the"
                        + " constant or retire this guard deliberately.")

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (f == owner.asFile || rel in exempt) return@forEach
            val n = countIn(guardText(f.readText()), Regex(Regex.escape("\"$value\"")))
            if (n > 0) hits.add("  $rel: $n x \"$value\"")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("jk's build output directory is named once, in"
                    + " cc.jumpkick.layout.BuildLayout.TARGET. These re-type it:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Reference the constant — or better, BuildLayout.moduleTargetDir(ws, mod),"
                    + " which also knows about the workspace central out tree. A `target` that is"
                    + " NOT this directory — a CLI parameter, javac's -target, a BSP field — must"
                    + " not borrow it either: add it to targetHomonyms with a reason.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkNoBareTargetDir) }
tasks.named("jar") { dependsOn(checkNoBareTargetDir) }

// ---------------------------------------------------------------------------
// The tree-local Maven repository.
//
// Every module that publishes gains one extra repository, `build/local-maven-repo` at the repo
// root, shared by all of them. It is a real Maven layout on disk written by the real publication,
// which is what `:plugin-sdk`'s consumer-resolution test resolves the SDK out of: asserting on POM
// text proves the string changed, resolving the closure proves a consumer can actually use it.
// Declared once, here, so no module re-types the path — a module that wants it reads it back off
// `publishing.repositories`.
// ---------------------------------------------------------------------------
val treeLocalRepoName = "treeLocal"

// ---------------------------------------------------------------------------
// Guard G19: a published POM may not name a coordinate this build does not publish.
//
// Defect it prevents: the artifact that cannot be resolved at all. `cc.jumpkick:jk-plugin-sdk` —
// the one library a third-party plugin author compiles against — shipped a POM whose only
// dependency was `jk:host:unspecified`, because `:host` set no `group` and no `version` and Gradle
// rendered the project dependency from its own defaults. `jk` is not a group we own, `host` is not
// an artifactId we publish and `unspecified` is not a version, so every consumer failed in
// dependency resolution before compiling a line. A text scan catches that in milliseconds and it
// still shipped, which is the whole argument for this file.
//
// Three arms, all read from the build, none re-typed:
//   1. `Project.DEFAULT_VERSION` — Gradle's own constant for "this project set no version". Its
//      appearance in a POM means a coordinate was rendered from a default, never from a decision.
//   2. `rootProject.name` — the group Gradle falls back to for a module that sets none (the parent
//      path, which is the root project's name for a one-level module). Same tell, other axis.
//   3. Any dependency in one of THIS BUILD's own published groups must name an artifact this build
//      actually publishes. That is the arm that catches the fake fix: pinning a groupId and a
//      version onto a project dependency whose target has no publication produces a POM that looks
//      resolvable and still is not. The published set is collected from every `MavenPublication` in
//      the tree, so adding or removing a publication moves the guard the same minute.
//
// A ban with no exceptions. It shipped as a nine-entry ratchet only because sixteen worker modules
// also ran `maven-publish`, and Gradle rendered their project dependencies on `:core` / `:io` /
// `:toolchain` / `:dynamic-surface` as `jk:core:unspecified` and friends. Those publications were a
// second POM producer for a GAV whose real POM is written by `writeWorkerPom`, and nothing read
// them; they were deleted rather than allowlisted forever, so the allowlist went
// 9 → 0. Measured 2026-08-24: two publications in the tree (`cc.jumpkick:jk-host`,
// `cc.jumpkick:jk-plugin-sdk`), two generated POMs scanned, zero exceptions. A module that
// reintroduces `maven-publish` gets the same ban — give the target a group, a version and a
// publication, or do not name it.
//
// Scope is `build/publications/**/pom-default.xml`, i.e. every POM `maven-publish` generates in
// this module. The flattened worker POM written next to the jar by `writeWorkerPom` is a different
// producer with a different rule — it resolves the whole runtime classpath and stages a jar for
// every coordinate it names — and is covered instead by `PublishedWorkerPomTest` in `:auditor`,
// which resolves that closure the way a worker launch does.
// ---------------------------------------------------------------------------

/** One `<tag>value</tag>` out of a POM fragment. */
fun pomTag(fragment: String, tag: String): String? =
        Regex("<$tag>([^<]*)</$tag>").find(fragment)?.groupValues?.get(1)?.trim()

pluginManager.withPlugin("maven-publish") {
    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = treeLocalRepoName
                url = uri(rootProject.layout.buildDirectory.dir("local-maven-repo"))
            }
        }
    }

    val checkPublishedPomCoordinates by tasks.registering {
        group = "verification"
        description = "Fail the build on a generated POM naming a coordinate this build does not publish"
        val generatedPoms = fileTree(layout.buildDirectory.dir("publications").get()) {
            include("**/pom-default.xml")
        }
        inputs.files(generatedPoms).withPropertyName("generatedPoms")
        dependsOn(tasks.withType(GenerateMavenPom::class.java))
        // Arm 3's allowed set, read off the build's own publications rather than re-typed.
        val publishedCoordinates = provider {
            rootProject.allprojects
                    .mapNotNull { it.extensions.findByType(PublishingExtension::class.java) }
                    .flatMap { it.publications.withType(MavenPublication::class.java) }
                    .map { "${it.groupId}:${it.artifactId}" }
                    .toSortedSet()
        }
        inputs.property("publishedCoordinates", publishedCoordinates)
        val fallbackGroup = rootProject.name
        val fallbackVersion = Project.DEFAULT_VERSION
        val here = project.path
        val treeRoot = rootProject.layout.projectDirectory.asFile
        val stamp = layout.buildDirectory.file("guards/published-pom-coordinates.ok")
        outputs.file(stamp)
        doLast {
            val published = publishedCoordinates.get()
            val publishedGroups = published.map { it.substringBefore(':') }.toSet()
            val poms = generatedPoms.files.sorted()
            if (poms.isEmpty()) {
                throw GradleException("$here applies maven-publish but generated no POM, so the"
                        + " published-coordinate guard verified nothing. A guard a dead"
                        + " call satisfies is worse than no guard: fix the wiring or drop the"
                        + " plugin.")
            }

            val hits = mutableListOf<String>()
            fun fault(group: String?, artifact: String?, version: String?): String? = when {
                version == fallbackVersion ->
                        "version is Gradle's $fallbackVersion default — the target module sets none"
                group == fallbackGroup || group.orEmpty().startsWith("$fallbackGroup.") ->
                        "groupId is the $fallbackGroup fallback — the target module sets no group"
                group in publishedGroups && "$group:$artifact" !in published ->
                        "$group is a group this build publishes, but it publishes no $artifact"
                else -> null
            }

            poms.forEach { f ->
                val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
                val text = f.readText()
                val head = text.substringBefore("<dependencies>")
                fault(pomTag(head, "groupId"), pomTag(head, "artifactId"), pomTag(head, "version"))
                        ?.let { hits.add("  $rel: its own coordinates — $it") }
                Regex("<dependency>(.*?)</dependency>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(text)
                        .forEach { m ->
                            val block = m.groupValues[1]
                            val g = pomTag(block, "groupId")
                            val a = pomTag(block, "artifactId")
                            val v = pomTag(block, "version")
                            val reason = fault(g, a, v) ?: return@forEach
                            hits.add("  $rel: $g:$a:$v — $reason")
                        }
            }
            if (hits.isNotEmpty()) {
                throw GradleException("A published POM that names a coordinate no repository can"
                        + " serve makes the artifact unresolvable before a consumer compiles a line"
                        + ":\n"
                        + hits.sorted().joinToString("\n")
                        + "\n  Give the target module a group, a version and a publication, or stop"
                        + " depending on it from a published module. Pinning a groupId and a"
                        + " version onto a dependency whose target nobody publishes produces a POM"
                        + " that looks resolvable and still is not.")
            }
            stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
        }
    }
    // `jar` as well as `check`, like the rest of the registry: the root `checkAll` reaches every
    // module's `jar` through the test runtime classpath but never its `check`, so a guard wired
    // only to `check` sits out the merge gate.
    tasks.named("check") { dependsOn(checkPublishedPomCoordinates) }
    tasks.named("jar") { dependsOn(checkPublishedPomCoordinates) }
}

// ---------------------------------------------------------------------------
// Guard G21: JSON is escaped in one place and parsed in one place.
//
// Defect it prevents: the codec that is nearly right. Five files assembled JSON with their own
// escaper. Every copy handled `\ " \n \r \t` and stopped there or reimplemented the `\uXXXX`
// fallback beside it, so the tree carried five chances to drop a control character onto a wire
// whose peer is an IDE, a browser or an OSV endpoint — and one of them, `plugins/auditor`, was
// paying 2.65 MB and three jars for a second tree reader while `MiniJson` sat on its classpath.
// A second *parser* is worse than a second writer: `shared/dynamic-surface`'s copy had no nesting
// cap, and a third party's `reflect-config.json` could overflow the stack with a `StackOverflowError`
// — an `Error`, so the `catch (RuntimeException)` that makes a broken metadata file a no-op let it
// through and killed the package step.
//
// Both arms read their alphabet FROM THE OWNER, `Jsonl.java`, at task time:
//   * the `\uXXXX` control-escape format token, out of `Jsonl.quote`;
//   * the escape letters `Jsonl.appendEscape` decodes, out of its `case` labels.
// A guard that re-typed `"\\u%04x"` here would be the ninth copy of the thing it is hunting.
//
// EXEMPTION IS BY SPEC, NOT BY FILENAME, because `code-as-art.md` is explicit that two escapers
// can both be correct, and this tree holds the exact pair it warns about: `MinimalToml.quote` is
// character-for-character the same method as `Jsonl.quote`. Merging them would be a regression.
// So each arm carries the discriminator its direction actually has:
//
//   * WRITE. There is none in the emitted alphabet — a JSON writer need not emit `\/`, and
//     `MinimalToml` emits neither `\/` nor TOML's own `\U`. The discriminator is what the file
//     does with the escaped string: a JSON *object* literal, `\"key\":`, which no TOML, DOT,
//     `.properties`, shell or Kotlin emitter in this tree writes. So the write arm fires on
//     "escapes the quote char, emits the owner's `\uXXXX` fallback, AND assembles a JSON object",
//     and `MinimalToml` — which does the first two and never the third — is out by shape.
//   * READ. Here the spec does discriminate: `\/` is legal in JSON and illegal in a TOML basic
//     string, so an escape switch with a `'/'` label beside the owner's letters is reading JSON
//     and nothing else. `MinimalToml.unquote` and `AotManifest.unquote` decode `n r t b f u` and
//     have no `'/'`; `DotEnv` has neither `'/'` nor `'u'`. All three are out on the same rule.
//
// Verified against every escaper shape in the tree before landing, not just the one this started
// from — the recorded lesson from G9, whose count was bounded by its pattern rather than by the
// defect. At HEAD the two arms flagged exactly the six files the sweep deleted or rewrote
// (`EnvCommand`, `HttpEvents`, `ChromeTimeline`, `DynamicSurfaceIo`, `ReachabilityMetadataEmitter`,
// `surface/Json`) and none of the nine correct non-JSON escapers and unescapers beside them
// (`MinimalToml`, `AotManifest`, `MicronautPlugin`'s `.properties`, `ShellPathExpr`'s two shell
// dialects, `DotEnv`, `JavacRunner`'s argfile, `BuildLogicKtsHost`'s Kotlin literal,
// `ModuleDotGraph`'s DOT, `NativeImageDriver`'s argfile). A pure ban with an empty allowlist:
// after the sweep there is nothing left to allow.
//
// Reachability was measured, not assumed: `:host` is an `api` dependency of both `:core` and
// `:plugin-sdk`, so `Jsonl` and `MiniJson` are on every production module's classpath and inside
// the native image. `shared/dynamic-surface` was the one module that could not see them, because
// it declared no dependency at all; it now has `api(project(":host"))` and its two copies
// went with it.
//
// Scope is `src/main/java`. A test that spells an escape by hand is a golden pinning the on-disk
// or on-wire bytes — the same reason G12, G13 and G15 leave test sources alone — and a fixture
// rewritten to call the owner would follow a change of the format and still pass.
// ---------------------------------------------------------------------------

/** The two files that ARE the JSON codec; the first is also where both arms read their alphabet. */
val jsonCodecOwners = listOf(
        "shared/host/src/main/java/cc/jumpkick/jsonl/Jsonl.java",
        "shared/host/src/main/java/cc/jumpkick/jsonl/MiniJson.java")

/** A Java char literal, escaped or not. */
private val charLiteral = """'(?:\\.|[^\\'])'"""

/**
 * Every char that appears as a `case` label in [code] — multi-label arms included. Written for
 * [guardText]-squashed input, where `case '"' ->` has already become `case'"'->`.
 */
fun caseLabelChars(code: String): Set<String> =
        Regex("""(?<![\w$])case\s*((?:$charLiteral\s*,\s*)*$charLiteral)\s*(?:->|:)""")
                .findAll(code)
                .flatMap { arm -> Regex(charLiteral).findAll(arm.groupValues[1]) }
                .map { it.value.removeSurrounding("'") }
                .toSet()

/** A JSON object literal written into Java source: `"…\"key\":…"`. Nothing else spells that. */
val jsonObjectLiteral = Regex("""\\"[A-Za-z_][A-Za-z0-9_.\-]*\\"\s*:""")

val checkOneJsonCodec by tasks.registering {
    group = "verification"
    description = "Fail the build on a JSON escaper or parser outside cc.jumpkick.jsonl"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(jsonCodecOwners.first())
    inputs.file(owner).withPropertyName("jsonl")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val owners = jsonCodecOwners
    val stamp = layout.buildDirectory.file("guards/one-json-codec.ok")
    outputs.file(stamp)
    doLast {
        val ownerCode = guardText(owner.asFile.readText())
        // `String.format("\uXXXX", …)` — the control-char fallback, taken verbatim out of the
        // owner so this file never spells it.
        val unicodeEscape = Regex("""String\.format\(("[^"]*u%04[xX]")""")
                .find(ownerCode)
                ?.groupValues
                ?.get(1)
        // The letters `Jsonl.appendEscape` decodes: " \ / n r t b f u.
        val ownerEscapes = caseLabelChars(ownerCode)
        if (unicodeEscape == null || !ownerEscapes.containsAll(listOf("/", "u", "n"))) {
            throw GradleException("cc.jumpkick.jsonl.Jsonl no longer yields the escape alphabet the"
                    + " one-JSON-codec guard reads from it: unicode fallback"
                    + " ${unicodeEscape ?: "MISSING"}, decoded escapes $ownerEscapes. Restore the"
                    + " codec's shape or retire this guard deliberately — do not re-type the"
                    + " alphabet here, which is the defect the guard exists to prevent.")
        }
        // Case-insensitive on the hex conversion only: `%04X` is the same escaper, shouting.
        val unicodePattern = Regex(Regex.escape(unicodeEscape), RegexOption.IGNORE_CASE)
        val decodesJson = ownerEscapes.filter { it != "/" }

        val writers = mutableListOf<String>()
        val readers = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (rel in owners) return@forEach
            val code = guardText(f.readText())
            val labels = caseLabelChars(code)
            if (unicodePattern.containsMatchIn(code)
                    && "\"" in labels
                    && jsonObjectLiteral.containsMatchIn(code)) {
                writers.add("  $rel")
            }
            // `\/` is JSON's and no other jk format's; four more of the owner's letters beside it
            // is an escape switch, not a coincidence of a character-dispatch table.
            if ("/" in labels && labels.count { it in decodesJson } >= 4) readers.add("  $rel")
        }

        val problems = mutableListOf<String>()
        if (writers.isNotEmpty()) {
            problems.add("jk escapes a JSON string in one place, `Jsonl.quote`. These"
                    + " assemble a JSON object with an escaper of their own:\n"
                    + writers.joinToString("\n")
                    + "\n  Call cc.jumpkick.jsonl.Jsonl.quote for one string, or hand the whole"
                    + " document to MiniJson.write / writePretty. Both are in :host, which every"
                    + " production module already links. An escaper for a DIFFERENT format is not"
                    + " in scope and must not borrow Jsonl either: TOML basic strings go through"
                    + " MinimalToml.quote, XML through MinimalXml, and a new format gets its own"
                    + " owner beside them.")
        }
        if (readers.isNotEmpty()) {
            problems.add("jk parses JSON in one place, `MiniJson`. These decode JSON's"
                    + " escape alphabet themselves:\n"
                    + readers.joinToString("\n")
                    + "\n  MiniJson.parse gives you Map/List/String/Double/Boolean, with a nesting"
                    + " cap a hand-rolled recursive descent does not have — and a"
                    + " StackOverflowError is an Error, so the catch that was meant to make a bad"
                    + " document a no-op will not catch it. MiniJson.get / str / list read the"
                    + " result without an instanceof ladder at every field.")
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkOneJsonCodec) }
tasks.named("jar") { dependsOn(checkOneJsonCodec) }

// ---------------------------------------------------------------------------
// Guard G23: every @Tag is run by exactly one test task.
//
// Defect it prevents: a test that is never executed by anything, and is invisible because no task
// goes red. A `@Tag` is a routing decision, and before this the routing lived in two half-tables —
// `test` excluded four tags, `integrationTest` re-included two of them. `@Tag("bench")` was
// excluded from both, so `ForkedJavacAotBenchTest` was run by no task in the repository; and
// `@Tag("network")` reached the merge gate only because the one class carrying it also carried
// `slow`, which is how the documented pre-merge bar came to depend on Maven Central.
// Either half-table looks deliberate on its own. Only together do they show a hole.
//
// WHAT THIS GUARD WAS MEASURED AGAINST — 2026-08-24, whole tree, `src/test/java`:
//     test source files                                                    914
//     files importing org.junit.jupiter.api.Tag                            127
//     files with at least one @Tag("...") literal                          127   <- arm 3's balance
//     tagged elements (a class or method and its run of @Tag annotations)  130
//     distinct tags   integration 107 · slow 21 · network 1 · bench 1 · [slow] 1 · brackets 1
//     elements run by exactly one task, BEFORE                             129   (1 orphan: bench)
//     elements run by exactly one task, AFTER                              130
//
// Three arms. They are not independent, and the header says so rather than implying three
// detectors where there is one prover, one locator and one closure check:
//   1. TOTALITY — the prover, and it does not read the tree at all. Every subset of
//      `TestTiers.vocabulary` (every tag the table mentions, in an include, an exclude or
//      `slowTags`) — all 2^4 of them today, enumerated, not sampled — must be run by exactly one
//      tier. This is the arm that would have caught the original defect on the day `bench` was
//      added to the exclude list without being added to an include list, with no tagged test in
//      existence yet, and it is the arm that fires when a fifth tag arrives without a tier.
//   2. NO ORPHAN IN THE TREE — the locator. Each run of `@Tag` annotations on one declaration is
//      resolved through `TestTier.runs`, JUnit's own include/exclude semantics, against every
//      tier. Zero tasks is an orphan; two is a test charged to two budgets. Granularity is the
//      annotation run rather than the file, because a class-level tag and a method-level tag route
//      independently — `LauncherPathTest` has one of each. Given arm 1 green and arm 3 green this
//      arm cannot fail — the tag set of any element is then a subset of a proven-total vocabulary
//      plus tags no tier filters on. That is the point: it exists to name the FILE AND LINE when
//      arm 1 fires, because "@Tag[bench] — no task runs it" does not tell you that
//      `ForkedJavacAotBenchTest` is the class you lost, and to keep proving the scan resolves real
//      tags through the real evaluator. The arms are reported together for that reason; arm 1 does
//      not short-circuit.
//   3. NO UNOWNED VOCABULARY — the closure check, and the arm that catches a typo.
//      `@Tag("intergration")` is excluded by nothing, so it silently runs in the fast tier and arm
//      2 is happy with it; the 8-minute unit budget is not. Any tag outside
//      `TestTiers.vocabulary` must therefore be named in `testTagFixtures` below. The same arm
//      carries the blindness check: a file that imports `Tag` and yields no literal means the
//      annotation shape moved (or a tag is written as a constant, which is unmatchable and equally
//      unwanted) and arms 2 and 3 are reading nothing.
//
// The tier table is READ FROM THE OWNER, `TestTiers`, which is also what generates the
// `useJUnitPlatform { }` filters above — so the guard cannot pass against a partition the build
// does not actually use. A copy of the tag list in this script would be the second place to keep
// in sync, which is the defect it exists to prevent.
//
// Scope is `src/test/java`; production code carries no `@Tag`, and the scan is over source rather
// than over the compiled test classpath so it holds before anything is built.
// ---------------------------------------------------------------------------

/**
 * Tags that are deliberately NOT tier routing, one per line as `tag — why`.
 *
 * `plugins/test-runner`'s `LauncherPathTest.Tagged` is a fixture: jk's own test-runner worker has
 * to filter tags itself, and the fixture exists to prove it survives a tag containing regex
 * metacharacters. They are untagged as far as Gradle is concerned — nothing excludes them, so the
 * fixture runs in the fast tier with the rest of the class, which is where it belongs.
 */
val testTagFixtures = mapOf(
        "[slow]" to "LauncherPathTest fixture: a tag with regex metacharacters in it",
        "brackets" to "LauncherPathTest fixture: the sibling plain tag it is compared against")

val checkNoOrphanTestTags by tasks.registering {
    group = "verification"
    description = "Fail the build on a @Tag no test task runs, or one no tier owns"
    val testJava = fileTree(layout.projectDirectory.dir("src/test/java")) { include("**/*.java") }
    inputs.files(testJava).withPropertyName("testJava")
    // The tier table is an input in its own right: edit TestTiers.kt and every module re-checks.
    inputs.property("tierTable", TestTiers.all.toString())
    inputs.property("tagFixtures", testTagFixtures.keys.sorted().toString())
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-orphan-test-tags.ok")
    outputs.file(stamp)
    doLast {
        val problems = mutableListOf<String>()

        // --- 1. the tier table is a total partition of the tag vocabulary --------------------
        val faults = TestTiers.partitionFaults()
        if (faults.isNotEmpty()) {
            problems.add("Every tag combination must be run by exactly one test task"
                    + ". TestTiers does not partition its own vocabulary:\n"
                    + faults.joinToString("\n")
                    + "\n  Adding a tag to TestTiers.slowTags takes it out of `test`; it needs a"
                    + " tier that includes it, or the exclusion is a hole. Two tiers running the"
                    + " same combination charges one test to two budgets.")
        }

        // --- 2 + 3. what the tree actually declares ------------------------------------------
        val tagLiteral = Regex("""@Tag\("([^"]*)"\)""")
        // Between two @Tag annotations of the SAME declaration there is only whitespace and other
        // annotations. Anything else — a modifier, a type, a brace — starts a new declaration.
        val sameDeclaration = Regex("""\s*(?:@\w+(?:\([^)]*\))?\s*)*""")
        val orphans = mutableListOf<String>()
        val unowned = mutableListOf<String>()
        val blind = mutableListOf<String>()
        var importers = 0
        var literalFiles = 0
        var elements = 0
        testJava.files.sorted().forEach { f ->
            val raw = f.readText()
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val imports = Regex("""^\s*import\s+org\.junit\.jupiter\.api\.Tag\s*;""",
                    RegexOption.MULTILINE).containsMatchIn(raw)
            if (imports) importers++
            val code = blankNonCode(raw, blankStrings = false)
            val hits = tagLiteral.findAll(code).toList()
            if (hits.isEmpty()) {
                if (imports) {
                    blind.add("  $rel: imports org.junit.jupiter.api.Tag and declares no"
                            + " @Tag(\"...\") literal")
                }
                return@forEach
            }
            literalFiles++
            var i = 0
            while (i < hits.size) {
                var j = i
                while (j + 1 < hits.size
                        && sameDeclaration.matchEntire(code.substring(hits[j].range.last + 1,
                                hits[j + 1].range.first)) != null) {
                    j++
                }
                val tags = hits.subList(i, j + 1).map { it.groupValues[1] }.toSet()
                val line = code.take(hits[i].range.first).count { it == '\n' } + 1
                elements++
                val tiers = TestTiers.tiersFor(tags)
                if (tiers.size != 1) {
                    val what = if (tiers.isEmpty()) "NO task runs it" else "run by $tiers"
                    orphans.add("  $rel:$line: @Tag${tags.sorted()} — $what")
                }
                tags.filterNot { it in TestTiers.vocabulary || it in testTagFixtures }
                        .sorted()
                        .forEach { unowned.add("  $rel:$line: @Tag(\"$it\")") }
                i = j + 1
            }
        }

        if (orphans.isNotEmpty()) {
            problems.add("Every @Tag must be run by exactly one test task. These are"
                    + " not:\n" + orphans.joinToString("\n")
                    + "\n  A tag excluded from `test` and included by no other tier is a test that"
                    + " never executes and never goes red. Give the tag a tier in"
                    + " buildSrc/src/main/kotlin/TestTiers.kt, or stop excluding it.")
        }
        if (unowned.isNotEmpty()) {
            problems.add("A @Tag that no tier owns runs in the fast tier by default, which is how"
                    + " a typo becomes a slow `test` task. These tags are in neither"
                    + " TestTiers.vocabulary nor testTagFixtures:\n" + unowned.sorted().joinToString("\n")
                    + "\n  Spell it as one of " + TestTiers.vocabulary + ", give it a tier of its"
                    + " own, or — if it is a fixture for jk's own tag filtering rather than a"
                    + " routing decision — name it in testTagFixtures with the reason.")
        }
        if (blind.isNotEmpty()) {
            problems.add("This guard reads @Tag(\"...\") literals out of the source. These files"
                    + " import the annotation and yield none, so it is reading nothing about"
                    + " them:\n" + blind.joinToString("\n")
                    + "\n  Either the annotation is written as @Tag(SOME_CONSTANT) — which no scan"
                    + " can route and which hides the tier a test runs in from anyone grepping —"
                    + " or the import is dead. Write the tag as a literal, or drop the import.")
        }
        if (problems.isNotEmpty()) throw GradleException(problems.joinToString("\n\n"))

        logger.info("no-orphan-test-tags: {} files, {} importing Tag, {} with literals,"
                + " {} tagged elements, each run by exactly one of {}",
                testJava.files.size, importers, literalFiles, elements,
                TestTiers.all.map { it.task })
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
// `check` only, not `jar`: this guard reads test sources, and hanging it off `jar` would make a
// production artifact's task graph depend on them. `checkAll` depends on every
// module's `check`, so `check` alone reaches the gate.
tasks.named("check") { dependsOn(checkNoOrphanTestTags) }

// ---------------------------------------------------------------------------
// Guard G35: a test that reads a checkout file locates it from the checkout root.
//
// Defect it prevents: a test resolving a source-tree path against the process working directory.
// Gradle runs a test with CWD at the owning module; a workspace `jk build` runs it with CWD at
// ~/.jk/state/engine. `CommandDependencyLaneTest` spelled the shipped android manifest as
// `Path.of(System.getProperty("user.dir"), "../../plugins/android/jk-plugin.toml")`, which under
// jk resolved to ~/.jk/state/plugins/android/jk-plugin.toml and went red — green under Gradle
// for months.
//
// The fix was one fixture, cc.jumpkick.testing.RepoRoot, because the walk it replaced had been
// copied into fourteen test classes with a per-module marker baked into each copy. Three of those
// copies degraded to a skip when the search failed (`assumeTrue(mainOpt.isPresent())`), so a
// broken search read as a pass. This guard is why copy fifteen cannot be written.
//
// Two arms, both narrow enough to be true:
//   1. `getProtectionDomain` outside the fixture — that call IS the walk, and it has one home.
//   2. a `user.dir` line that also escapes with `..` — the exact shape of the measured defect.
// Comment-blind via guardText, so a javadoc that explains the rule is not itself a hit.
val checkTestPathsFromCheckoutRoot by tasks.registering {
    group = "verification"
    description = "Fail the build when a test locates a checkout file from CWD instead of cc.jumpkick.testing.RepoRoot"
    val testJava = fileTree(layout.projectDirectory.dir("src/test/java")) { include("**/*.java") }
    val fixtureJava = fileTree(layout.projectDirectory.dir("src/fixtures/java")) { include("**/*.java") }
    inputs.files(testJava, fixtureJava).withPropertyName("testSources")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/fixtures/java/cc/jumpkick/testing/RepoRoot.java")
    inputs.file(owner).withPropertyName("repoRoot")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/test-paths-from-checkout-root.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // Self-fail arm: the guard names a replacement, so the replacement has to still be there
        // and still be the thing that does the walk. A fixture that lost `getProtectionDomain`
        // is no longer doing the job this guard redirects people to.
        // Raw text, not guardText: guardText squashes whitespace between tokens, so a signature
        // probe has to allow for it or be written unreadably as `staticPathfind(`.
        val ownerText = ownerFile.readText()
        val ownerApi = listOf("getProtectionDomain\\(", "static\\s+Path\\s+find\\s*\\(",
                        "static\\s+Path\\s+file\\s*\\(", "static\\s+Path\\s+dir\\s*\\(")
                .filterNot { Regex(it).containsMatchIn(ownerText) }
        if (ownerApi.isNotEmpty()) {
            throw GradleException("cc.jumpkick.testing.RepoRoot no longer has ${ownerApi.joinToString(", ")},"
                    + " so this guard is redirecting tests to something that cannot serve them."
                    + " Restore the fixture or retire the guard deliberately.")
        }

        val hits = mutableListOf<String>()
        (testJava.files + fixtureJava.files).sorted().forEach { f ->
            if (f == ownerFile) return@forEach
            val code = guardText(f.readText())
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            val walks = countIn(code, Regex(Regex.escape("getProtectionDomain")))
            if (walks > 0) {
                hits.add("  $rel: $walks x getProtectionDomain  ->  RepoRoot.find/file/dir(<ThisTest>.class, \"<path-from-root>\")")
            }
            // blankNonCode, not guardText, for the per-line arm: guardText joins lines, so a line
            // number taken from it is always 1. This blanks comments in place and keeps the shape.
            blankNonCode(f.readText(), blankStrings = false).lines().forEachIndexed { i, line ->
                if (line.contains("user.dir") && (line.contains("\"..") || line.contains("/..\""))) {
                    hits.add("  $rel:${i + 1}: user.dir escaped with `..`  ->  RepoRoot.file(<ThisTest>.class, \"<path-from-root>\")")
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("A test that reads a file out of the source tree names it from the"
                    + " checkout root, via cc.jumpkick.testing.RepoRoot. These resolve it"
                    + " against the working directory instead, which differs between Gradle and a"
                    + " workspace `jk build`:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Reach the fixture with `testImplementation(testFixtures(project(\":host\")))`"
                    + " and `jk-host = { workspace = true, kind = \"tests\" }`. A path spelled from the"
                    + " root is the same under both builds; a path spelled from CWD is not.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
// `check` only, not `jar`: this guard reads test sources (see checkNoOrphanTestTags above).
tasks.named("check") { dependsOn(checkTestPathsFromCheckoutRoot) }

// ---------------------------------------------------------------------------
// Guard G36: a module's two manifests declare the same dependencies.
//
// Defect it prevents: jk.toml and build.gradle.kts drifting. The repo builds itself both ways, so
// a dependency declared only to Gradle compiles under `./gradlew check` and fails under `jk build`
// — and vice versa. Measured: thirteen edges out of step at once. `clients/web` had
// `testImplementation(project(":wire"))` and no manifest entry, so `jk test` could not compile
// `WireTokenParityTest`; the four plugin modules and the five that consume `:host`'s test fixtures
// each had a Gradle `testFixtures(...)` edge with no `fixtures = true` twin; and
// `plugins/image-builder` still declared `:core` and `:io` to Gradle after they were removed
// from jk.toml as unimported.
//
// Scope buckets are coarse on purpose — main-ish vs test-ish — because that is the distinction
// that decides whether javac can see a type. Within a bucket the two builds are free to spell a
// dependency differently (`api` vs `[dependencies]`, `runtimeOnly` vs the same table).
//
// Both directions, because both have been wrong in this repo. Plus a self-fail arm: a module that
// declares a Gradle project dependency and yields no parsed edges means the scan broke.
val checkManifestDepParity by tasks.registering {
    group = "verification"
    description = "Fail the build when build.gradle.kts and jk.toml disagree about this module's workspace dependencies"
    val ownScript = layout.projectDirectory.file("build.gradle.kts")
    val ownManifest = layout.projectDirectory.file("jk.toml")
    val settings = rootProject.layout.projectDirectory.file("settings.gradle.kts")
    inputs.file(settings).withPropertyName("settings")
    // Every manifest, because the project-path -> artifact-name map is spread across all of them.
    // Named per module rather than matched out of a tree rooted at the repo: that root contains
    // `:dist`'s output directory, and Gradle rejects the task for using `:dist`'s output without
    // declaring a dependency — which made `./gradlew build dist` fail on every run after the first.
    val allManifests = rootProject.subprojects.map { it.layout.projectDirectory.file("jk.toml") }
    inputs.files(allManifests).optional().withPropertyName("manifests")
    inputs.file(ownScript).withPropertyName("buildScript").optional(true)
    val projectPath = path
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/manifest-dep-parity.ok")
    outputs.file(stamp)
    doLast {
        val scriptFile = ownScript.asFile
        val manifestFile = ownManifest.asFile
        if (!scriptFile.isFile || !manifestFile.isFile) {
            // A module built by only one of the two builds has nothing to reconcile.
            stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
            return@doLast
        }

        // ":wire" -> "shared/wire", straight out of settings.gradle.kts.
        val dirOf = Regex("""project\("(:[\w-]+)"\)\.projectDir\s*=\s*file\("([^"]+)"\)""")
                .findAll(settings.asFile.readText())
                .associate { it.groupValues[1] to it.groupValues[2] }
        // "shared/wire" -> "jk-engine-api", straight out of that module's own manifest. The two
        // names differ often enough (:wire is jk-engine-api, :jk-api is jk-model) that guessing
        // from the project path would make this guard lie.
        val nameOf = dirOf.mapNotNull { (proj, dir) ->
            val m = File(treeRoot, "$dir/jk.toml")
            if (!m.isFile) return@mapNotNull null
            val n = Regex("""^name\s*=\s*"([^"]+)"""", RegexOption.MULTILINE).find(m.readText())
            if (n == null) null else proj to n.groupValues[1]
        }.toMap()
        if (nameOf.size < 20) {
            throw GradleException("manifest-dep-parity mapped only ${nameOf.size} project paths to"
                    + " artifact names, so it has lost settings.gradle.kts or the manifests."
                    + " Restore the shape or retire this guard deliberately.")
        }

        val testConfs = setOf("testImplementation", "testApi", "testRuntimeOnly", "testCompileOnly",
                "testFixturesApi", "testFixturesImplementation", "integrationTestImplementation")
        val mainConfs = setOf("implementation", "api", "compileOnly", "runtimeOnly",
                "annotationProcessor", "compileOnlyApi")

        // Gradle side. `fixtures` records the edges that need `kind = "tests"` specifically.
        val gradleMain = mutableSetOf<String>()
        val gradleTest = mutableSetOf<String>()
        val fixtures = mutableSetOf<String>()
        var edges = 0
        blankNonCode(scriptFile.readText(), blankStrings = false).lines().forEach { line ->
            Regex("""(\w+)\(\s*(testFixtures\(\s*)?project\("(:[\w-]+)"\)""").findAll(line).forEach { m ->
                val conf = m.groupValues[1]
                val isFixture = m.groupValues[2].isNotEmpty()
                val name = nameOf[m.groupValues[3]] ?: return@forEach
                edges++
                when (conf) {
                    in testConfs -> { gradleTest.add(name); if (isFixture) fixtures.add(name) }
                    in mainConfs -> gradleMain.add(name)
                }
            }
        }
        if (scriptFile.readText().contains("project(\":") && edges == 0) {
            throw GradleException("manifest-dep-parity found no project dependency in"
                    + " $projectPath/build.gradle.kts although the text contains one."
                    + " The scan broke; fix it rather than letting it pass.")
        }

        // Manifest side. `[test-*]` tables are the test bucket; everything else is main.
        val jkMain = mutableSetOf<String>()
        val jkTest = mutableSetOf<String>()
        val jkFixtures = mutableSetOf<String>()
        var table = ""
        manifestFile.readLines().forEach { raw ->
            val line = raw.substringBefore('#').trim()
            if (line.startsWith("[")) { table = line.trim('[', ']'); return@forEach }
            if (!table.endsWith("dependencies")) return@forEach
            val dotted = Regex("""^([\w-]+)\.workspace\s*=\s*true""").find(line)
            val inline = Regex("""^([\w-]+)\s*=\s*\{(.*)}""").find(line)
            val name = dotted?.groupValues?.get(1)
                    ?: inline?.takeIf { it.groupValues[2].contains("workspace") && it.groupValues[2].contains("true") }
                            ?.groupValues?.get(1)
                    ?: return@forEach
            val takesFixtures = inline != null && Regex("""fixtures\s*=\s*true""").containsMatchIn(inline.groupValues[2])
            if (table.startsWith("test-")) {
                jkTest.add(name)
                if (takesFixtures) jkFixtures.add(name)
            } else {
                jkMain.add(name)
            }
        }

        val problems = mutableListOf<String>()
        (gradleMain - jkMain).sorted().forEach {
            problems.add("  Gradle declares $it for the main tier; jk.toml [dependencies] does not")
        }
        (jkMain - gradleMain).sorted().forEach {
            problems.add("  jk.toml [dependencies] declares $it; build.gradle.kts does not")
        }
        // A test-tier need is satisfied by a main declaration in either build, so compare the union.
        ((gradleTest - fixtures) - jkTest - jkMain).sorted().forEach {
            problems.add("  Gradle declares $it for the test tier; jk.toml [test-dependencies] does not")
        }
        (jkTest - gradleTest - gradleMain).sorted().forEach {
            problems.add("  jk.toml [test-dependencies] declares $it; build.gradle.kts does not")
        }
        (fixtures - jkFixtures).sorted().forEach {
            problems.add("  Gradle takes $it's testFixtures; jk.toml needs"
                    + " `$it = { workspace = true, fixtures = true }` under a [test-*dependencies] table")
        }
        (jkFixtures - fixtures).sorted().forEach {
            problems.add("  jk.toml takes $it with fixtures = true; build.gradle.kts does not take"
                    + " its testFixtures")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("$projectPath declares different dependencies to its two builds."
                    + " This repo builds itself with Gradle and with jk, so an edge in"
                    + " only one of them is green in one build and broken in the other:\n"
                    + problems.joinToString("\n")
                    + "\n  jk's `fixtures = true` is Gradle's `testFixtures(...)`; jk's"
                    + " [test-dependencies] is Gradle's testImplementation. Fix whichever manifest"
                    + " is wrong — do not silence this by deleting the other declaration.")
        }
        logger.info("manifest-dep-parity: {} main + {} test edges, {} via testFixtures",
                gradleMain.size, gradleTest.size, fixtures.size)
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
// `check` only, not `jar`: parity is a repo-hygiene rule, not a property of the artifact.
tasks.named("check") { dependsOn(checkManifestDepParity) }

// ---------------------------------------------------------------------------
// Guard G37: recursive tree deletion has one owner, and it does not follow links.
//
// Defect it prevents: a delete that empties whatever a symbolic link points at. jk discovers
// host-installed toolchains and links its registry entries to them — an sdkman or IntelliJ JDK is
// exactly such a target — so a delete that follows a link reaches files jk did not install and
// must not remove.
//
// `cc.jumpkick.host.PathUtil.deleteRecursively` is that owner. It removes a link and never enters
// it, at the root or anywhere inside the tree, and PathUtilTest pins all three cases. What made
// that worth enforcing rather than merely documenting is how quiet the rule is: both `Files.walk`
// and `Files.walkFileTree` decline to follow links by DEFAULT, so the correct behaviour is the
// absence of a FileVisitOption. "Cleanup left files behind" therefore reads like a missing
// FOLLOW_LINKS, and adding that one enum constant is a one-word change that turns a cleanup into
// a data-loss bug. Nineteen files had their own copy of the walk when this landed; two of them
// were in the JDK-symlink machinery itself.
//
// Arm A bans FOLLOW_LINKS in any file that deletes — no allowlist, because there is no version of
// "follow the link and delete what is there" this tree wants. Arm B bans the hand-rolled
// children-first walk outside the owner, with four exemptions that delete *selectively* and so are
// not tree deletes at all.
val recursiveDeleteExemptions = mapOf(
        "server/engine/src/main/java/cc/jumpkick/runtime/BuildLogicSupport.java"
                to "deleteContents keeps the directory and removes only what is under it",
        "server/engine/src/main/java/cc/jumpkick/runtime/CachePlans.java"
                to "tallies bytes per tag and honours --dry-run, so it cannot delegate the walk",
        "server/engine/src/main/java/cc/jumpkick/task/ActionCache.java"
                to "pruneUnowned deletes only files absent from the owned set, plus emptied dirs",
        "server/engine/src/main/java/cc/jumpkick/task/CacheRetention.java"
                to "pruneEmptyDirs deletes a directory only when it is already empty")

val checkOneRecursiveDelete by tasks.registering {
    group = "verification"
    description = "Fail the build on a hand-rolled recursive delete, or on a delete that follows symbolic links"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val owner = rootProject.layout.projectDirectory.file(
            "shared/host/src/main/java/cc/jumpkick/host/PathUtil.java")
    inputs.file(owner).withPropertyName("pathUtil")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val exemptions = recursiveDeleteExemptions
    val stamp = layout.buildDirectory.file("guards/one-recursive-delete.ok")
    outputs.file(stamp)
    doLast {
        val ownerFile = owner.asFile
        // Self-fail arm: the owner has to still be the thing this redirects people to — a walk
        // that does not follow links, and a root check that does not follow one either.
        val ownerText = ownerFile.readText()
        val ownerNeeds = listOf("walkFileTree", "NOFOLLOW_LINKS", "deleteRecursivelyOrThrow")
                .filterNot { ownerText.contains(it) }
        if (ownerNeeds.isNotEmpty()) {
            throw GradleException("cc.jumpkick.host.PathUtil no longer has ${ownerNeeds.joinToString(", ")},"
                    + " so this guard points at something that cannot serve the callers it redirects."
                    + " Restore the owner or retire this guard deliberately.")
        }

        val hits = mutableListOf<String>()
        var scanned = 0
        var deleters = 0
        mainJava.files.sorted().forEach { f ->
            if (f == ownerFile) return@forEach
            scanned++
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            // Comments blanked, line structure kept, so a line number means something and a
            // javadoc explaining the rule is not itself a violation.
            val lines = blankNonCode(f.readText(), blankStrings = false).lines()
            // Delegation counts too: a file whose only delete is PathUtil.deleteRecursively
            // still has no business asking a walk to follow links.
            val deletes = lines.any {
                it.contains(".delete(") || it.contains("deleteIfExists(") || it.contains("deleteRecursively")
            }
            if (!deletes) return@forEach
            deleters++

            lines.forEachIndexed { i, line ->
                if (line.contains("FOLLOW_LINKS") && !line.contains("NOFOLLOW_LINKS")) {
                    hits.add("  $rel:${i + 1}: FOLLOW_LINKS in a file that deletes"
                            + "  ->  drop it; a link is removed, never entered")
                }
            }
            if (rel in exemptions) return@forEach
            lines.forEachIndexed { i, line ->
                if (!line.contains("reverseOrder")) return@forEachIndexed
                val window = lines.subList(i, minOf(i + 9, lines.size)).joinToString("\n")
                if (window.contains(".delete(") || window.contains("deleteIfExists(")) {
                    hits.add("  $rel:${i + 1}: children-first walk that deletes"
                            + "  ->  PathUtil.deleteRecursively / deleteRecursivelyOrThrow")
                }
            }
        }
        if (hits.isNotEmpty()) {
            throw GradleException("Recursive tree deletion belongs to cc.jumpkick.host.PathUtil,"
                    + " which removes a symbolic link instead of entering it. These do it"
                    + " themselves, so each one decides that question again:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  PathUtil is on every module's classpath — :host is the floor the CLI, the"
                    + " engine and every plugin worker already share. A delete that is genuinely"
                    + " selective (only empty directories, only unowned files, only the contents)"
                    + " is not a tree delete: add it to `recursiveDeleteExemptions` in this file"
                    + " with the reason, so the next reader can tell the two apart.")
        }
        logger.info("one-recursive-delete: {} files scanned, {} that delete", scanned, deleters)
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkOneRecursiveDelete) }
tasks.named("jar") { dependsOn(checkOneRecursiveDelete) }

// ---------------------------------------------------------------------------
// Guard G46: a JDK is removed only by an explicit `jk jdk` verb.
//
// Defect it prevents: an ordinary build deleting the JDK it is running on. This one is not
// hypothetical and it is not cheap — it happened twice in one afternoon on a developer machine and
// took four JDK installs with it, including both GraalVMs, which are minutes of download each and
// may be pinned by an IDE, a shell, a `.sdkmanrc`, or another project's lockfile.
//
// Both incidents were the same shape: `JdkInstaller.install()` drained `JdkGarbage` before
// installing, so a row queued by an earlier `jk jdk update` fired on the next *unrelated* build —
// and `StableJdkPointer.ensure` deleted whatever populated directory occupied the
// `<vendor>-<major>` pointer name to make room for a link. Neither was reachable from a `jk jdk`
// verb the user typed; both were on the automatic provisioning path.
//
// Removing a JDK is not a cache eviction, so the rule is about WHO may do it rather than how:
//
//   Arm A — `JdkGarbage` is named only by the explicit verb that queues into it. Anything else,
//           and in particular anything under `server/`, is on a build path by construction.
//   Arm B — `StableJdkPointer` performs no recursive delete. It owns the pointer, which is a link
//           or an empty directory; a populated directory at that path is an install and belongs to
//           somebody, possibly us, and either way not to a name-claiming routine.
//
// Ownership (`JkOwnership`) is the other half and is deliberately NOT what this
// guard checks: it answers "is this ours", which stopped jk deleting a neighbour's JDK but still
// let it delete its own automatically. This arm is about the trigger, not the target.
val jdkRemovalCallers = mapOf(
        "clients/cli/src/main/java/cc/jumpkick/command/JdkUpdateCommand.java"
                to "`jk jdk update` queues the superseded install and drains it, after asking (default yes)")

val checkJdkRemovalConfined by tasks.registering {
    group = "verification"
    description = "Fail the build when JDK removal is reachable from anything but an explicit `jk jdk` verb"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val garbage = rootProject.layout.projectDirectory.file(
            "shared/toolchain-jdk/src/main/java/cc/jumpkick/jdk/JdkGarbage.java")
    val pointer = rootProject.layout.projectDirectory.file(
            "shared/toolchain-jdk/src/main/java/cc/jumpkick/jdk/StableJdkPointer.java")
    inputs.file(garbage).withPropertyName("jdkGarbage")
    inputs.file(pointer).withPropertyName("stableJdkPointer")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val allowed = jdkRemovalCallers
    val stamp = layout.buildDirectory.file("guards/jdk-removal-confined.ok")
    outputs.file(stamp)
    doLast {
        // Self-fail arm: the guard has to keep pointing at code that still exists and still carries
        // the ownership re-check, or it silently guards nothing.
        val garbageText = garbage.asFile.readText()
        val missing = listOf("isJkOwned", "drain", "enqueue").filterNot { garbageText.contains(it) }
        if (missing.isNotEmpty()) {
            throw GradleException("JdkGarbage no longer has ${missing.joinToString(", ")}, so guard G46"
                    + " is guarding a shape that has moved. Update or retire it deliberately.")
        }
        // treeRoot.resolve, not java.io.File(...): in the Kotlin DSL `java` resolves to the
        // JavaPluginExtension accessor, so the package name is shadowed inside a build script.
        val allowedMissing = allowed.keys.filterNot { treeRoot.resolve(it).isFile }
        if (allowedMissing.isNotEmpty()) {
            throw GradleException("G46's allowlist names files that no longer exist:"
                    + " ${allowedMissing.joinToString(", ")}. The verb was renamed or removed —"
                    + " update the allowlist in the same change.")
        }

        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (rel.endsWith("/jdk/JdkGarbage.java")) return@forEach
            // Comments blanked so the javadoc that explains this rule is not itself a violation.
            val lines = blankNonCode(f.readText(), blankStrings = false).lines()

            if (rel.endsWith("/jdk/StableJdkPointer.java")) {
                lines.forEachIndexed { i, line ->
                    if (line.contains("deleteRecursively")) {
                        hits += "$rel:${i + 1}: the stable pointer must not delete a tree —" +
                                " a populated directory at the pointer name is an install"
                    }
                }
                return@forEach
            }

            if (allowed.containsKey(rel)) return@forEach
            lines.forEachIndexed { i, line ->
                if (line.contains("JdkGarbage")) {
                    hits += "$rel:${i + 1}: JdkGarbage is reachable from here"
                }
            }
        }

        if (hits.isNotEmpty()) {
            throw GradleException("G46: JDK removal reached from outside an explicit `jk jdk` verb.\n\n"
                    + hits.joinToString("\n") { "  $it" }
                    + "\n\nRemoving a JDK is minutes of download and may be pinned by an IDE, a shell,"
                    + " a .sdkmanrc, or another project's lockfile, so it happens only when the user"
                    + " asked for it. Provisioning installs; it does not collect."
                    + "\nIf a new verb legitimately removes JDKs, add it to `jdkRemovalCallers` in the"
                    + " same change and say why."
                    + "\n\nAllowed today:\n"
                    + allowed.entries.joinToString("\n") { "  ${it.key}\n      ${it.value}" })
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkJdkRemovalConfined) }
tasks.named("jar") { dependsOn(checkJdkRemovalConfined) }

// ---------------------------------------------------------------------------
// Guard G47: every case conversion passes Locale.ROOT.
//
// Defect it prevents: a parser that is wrong only in Türkiye. Under tr_TR/az, 'i' ⇄ 'I' do not
// round-trip — "MAIN".toLowerCase() is "maın", "runtime".toUpperCase() is "RUNTİME" — so a
// locale-less conversion on an identifier, enum, wire value, suffix or config key misparses for
// an entire locale family. The tree had 270 Locale.ROOT sites and 35 that drifted, several of
// them parsers (one silently rewrote an MCP client's `runtime` scope into `main`). Identifiers
// are not user language; there is exactly one right argument.
// ---------------------------------------------------------------------------

val checkCaseConversionLocale by tasks.registering {
    group = "verification"
    description = "Fail the build on a locale-less toLowerCase()/toUpperCase() in src/main"
    val mainJava = fileTree(layout.projectDirectory.dir("src/main/java")) { include("**/*.java") }
    inputs.files(mainJava).withPropertyName("mainJava")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val srcMain = layout.projectDirectory.dir("src/main/java").asFile
    val stamp = layout.buildDirectory.file("guards/case-conversion-locale.ok")
    outputs.file(stamp)
    doLast {
        if (srcMain.isDirectory && mainJava.files.isEmpty()) {
            throw GradleException("G47 scanned zero files under an existing src/main/java —"
                    + " the guard has silently lost its scope; fix the file tree.")
        }
        val bare = Regex("""\.to(?:Lower|Upper)Case\(\)""")
        val hits = mutableListOf<String>()
        mainJava.files.sorted().forEach { f ->
            val n = countIn(guardText(f.readText()), bare)
            if (n > 0) hits.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}: $n")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("G47: a case conversion without Locale.ROOT misparses under"
                    + " tr_TR/az ('i' ⇄ 'I' do not round-trip). Pass Locale.ROOT — identifiers"
                    + " are not user language:\n" + hits.joinToString("\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkCaseConversionLocale) }
tasks.named("jar") { dependsOn(checkCaseConversionLocale) }

// ---------------------------------------------------------------------------
// Guard G48: no glued Javadoc inline tag.
//
// Defect it prevents: `{@code.asc}` renders as the literal garbage `code.asc` (the tag name eats
// the payload), and 83 of them had accumulated tree-wide. Worse, an unresolvable reference inside
// a broken tag makes OpenRewrite treat the type as unattributed and decline to shorten it
// anywhere in the file. The unresolvable-link arm needs the compiler and lives in `javadoc`;
// this is the text-scannable arm — no allowlist.
// ---------------------------------------------------------------------------

val checkNoGluedInlineTag by tasks.registering {
    group = "verification"
    description = "Fail the build on {@code/link/value glued to its payload ({@code.asc})"
    val allJava = fileTree(layout.projectDirectory.dir("src")) { include("**/*.java") }
    inputs.files(allJava).withPropertyName("allJava")
    val treeRoot = rootProject.layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/no-glued-inline-tag.ok")
    outputs.file(stamp)
    doLast {
        // The payload class excludes letters so `link` cannot half-match `linkplain`; the glued
        // family is punctuation ({@code.asc}, {@code:}, {@code,}), never a letter.
        val glued = Regex("""\{@(?:code|value|linkplain|link)[^\s}a-zA-Z]""")
        val hits = mutableListOf<String>()
        allJava.files.sorted().forEach { f ->
            val n = countIn(f.readText(), glued)
            if (n > 0) hits.add("  ${f.relativeTo(treeRoot).invariantSeparatorsPath}: $n")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("G48: an inline Javadoc tag needs a space before its payload —"
                    + " {@code .asc}, not {@code.asc} (glued tags render as garbage and can block"
                    + " FQCN shortening for the whole file):\n" + hits.joinToString("\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.named("check") { dependsOn(checkNoGluedInlineTag) }
tasks.named("jar") { dependsOn(checkNoGluedInlineTag) }

// Serial execution when shuffling, applied after the modules have had their say.
//
// The orderer decides order WITHIN a JVM; Gradle decides which classes go to which fork, and that
// distribution is not seeded. With parallel forks the same seed does not reproduce a run, which
// makes "replay with this seed" a lie — and setting maxParallelForks inside the conventions'
// configureEach does not hold, because a module's own `tasks.named<Test>("test") { … }` block runs
// afterwards and overwrites it. Measured: :cli:test still forked 10 JVMs. Hence afterEvaluate.
if (providers.gradleProperty("jk.test.shuffle").isPresent) {
    afterEvaluate {
        tasks.withType<Test>().configureEach { maxParallelForks = 1 }
    }
}
