// SPDX-License-Identifier: Apache-2.0

// Root project. Conventions live in buildSrc/ and are applied per module.
// Library/plugin pins live in gradle/libs.versions.toml.

// Report which test tiers actually executed, and read their counts from TEST-*.xml.
// A gate that prints BUILD SUCCESSFUL for a cache read is not evidence about the current tree.
apply<GateReportPlugin>()

tasks.wrapper {
    gradleVersion = "9.5.1"
    distributionType = Wrapper.DistributionType.BIN
}

// Aggregate the slow tiers across all subprojects that register them. One tier per tag; the table
// is buildSrc/src/main/kotlin/TestTiers.kt and `checkAll` below runs `integrationTest` only.
tasks.register("integrationTest") {
    group = "verification"
    description = "Run @Tag(integration) tests in every module (not part of check)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "integrationTest" } })
}

// Off checkAll: framework/language e2e, 426s of the gating tier for 28 tests. What
// they assert moves with a plugin or a toolchain, not with the change under review.
tasks.register("slowTest") {
    group = "verification"
    description = "Run @Tag(slow) framework/language e2e suites in every module (nightly, not in checkAll)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "slowTest" } })
}

// Deliberately NOT reachable from checkAll. These tests talk to a real remote, and
// Sonatype enforces a per-IP quota on Maven Central that this repo has already been bitten by
// — a merge gate that needs the network fails for reasons the change did not cause.
// Runs nightly in ci-nightly.yml, where a 429 costs a re-run rather than a blocked PR.
tasks.register("networkTest") {
    group = "verification"
    description = "Run @Tag(network) tests in every module (nightly only, never in checkAll)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "networkTest" } })
}

// Also not reachable from checkAll, for the opposite reason: a microbench prints medians and
// asserts nothing about deltas, so gating on it would gate on CI noise. It still has to run
// somewhere, which it once did not — @Tag("bench") was excluded from both tiers.
tasks.register("benchTest") {
    group = "verification"
    description = "Run @Tag(bench) microbenchmarks in every module (on demand, gates nothing)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "benchTest" } })
}

val nonGateChecks = setOf("checkFast", "checkAll")
val rootBranchGuards = tasks.matching { it.name.startsWith("check") && it.name !in nonGateChecks }
val checkFast = tasks.register("checkFast") {
    group = "verification"
    description = "Run unit tests and every network-free structural guard"
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "check" } }, rootBranchGuards)
}

tasks.register("checkAll") {
    group = "verification"
    description = "checkFast + integrationTest for the whole repo"
    dependsOn(checkFast, "integrationTest")
}

// Guard G52: the contributor tier table is rendered from TestTiers, never retyped.
tasks.register("checkTestTierDocs") {
    group = "verification"
    description = "Fail when the documented test tiers differ from TestTiers"
    val model = layout.projectDirectory.file("buildSrc/src/main/kotlin/TestTiers.kt")
    val docs = layout.projectDirectory.file("docs/contributors/test-suite-tiers.md")
    inputs.files(model, docs)
    doLast {
        fun tags(values: Set<String>, fallback: String = "—") =
            if (values.isEmpty()) fallback else values.joinToString(", ") { "`$it`" }
        val expected = buildString {
            appendLine("<!-- test-tiers:start -->")
            appendLine("| Command | Includes | Excludes | In `checkAll`? |")
            appendLine("|---------|----------|----------|----------------|")
            TestTiers.all.forEach { tier ->
                appendLine(
                    "| `./gradlew ${tier.task}` | "
                            + tags(tier.include, "untagged")
                            + " | "
                            + tags(tier.exclude)
                            + " | "
                            + if (tier.task in TestTiers.gating) "yes |" else "no |")
            }
            append("<!-- test-tiers:end -->")
        }
        val actual = docs.asFile.readText()
        val block = Regex("""(?s)<!-- test-tiers:start -->.*?<!-- test-tiers:end -->""").find(actual)
            ?.value
            ?: throw GradleException("test-suite-tiers.md is missing its generated tier table markers")
        if (block != expected) {
            throw GradleException(
                "test-suite-tiers.md differs from TestTiers; replace its marked table with:\n$expected")
        }
    }
}

// Guard: the published stage taxonomy is read off BuildStage, never retyped. The list drifted the
// same way twice in four days (train, then publish, landed without a doc touch), and
// machine-output.md is a JSONL contract consumers integrate against — a "closed set" missing live
// values is a promise jk breaks on any `jk train` or `jk install` run.
tasks.register("checkStageDocs") {
    group = "verification"
    val enumFile = layout.projectDirectory.file("shared/jk-api/src/main/java/cc/jumpkick/run/BuildStage.java")
    val docs = listOf(
        "docs/user/machine-output.md" to "list",
        "docs/user/explain.md" to "list",
        "docs/contributors/architecture.md" to "arrow",
    )
    inputs.files(enumFile, docs.map { layout.projectDirectory.file(it.first) })
    val root = layout.projectDirectory.asFile
    doLast {
        val wires = Regex("""^\s{4}[A-Z_]+\("([a-z]+)"\)""", RegexOption.MULTILINE)
            .findAll(enumFile.asFile.readText()).map { it.groupValues[1] }.toList()
        if (wires.size < 8) throw GradleException("checkStageDocs read ${wires.size} stages from BuildStage.java — the parse broke, fix the guard")
        val asList = wires.joinToString(", ") { "`$it`" }
        val asArrows = wires.joinToString(" → ")
        val stale = docs.filter { (path, shape) ->
            val text = root.resolve(path).readText().replace(Regex("""\s+"""), " ")
            val expected = if (shape == "list") asList else asArrows
            !text.contains(expected)
        }
        if (stale.isNotEmpty()) {
            throw GradleException("BuildStage has ${wires.size} wire values ($asArrows) and these docs "
                    + "publish a different list — update them to match the enum, in pipeline order: "
                    + stale.joinToString(", ") { it.first })
        }
    }
}

// Guard: the published guard registry lists exactly the letters the build enforces.
//
// `docs/contributors/code-as-art.md` is where a contributor learns what this build checks, and it
// has now fallen behind the code twice — first by six letters, then by eleven, which is how
// allocating G49 nearly collided with a live guard. Two reconciliations by hand is the argument
// for the third not being by hand. Same shape as `checkStageDocs`: read one side out of the code,
// read the other out of the doc, diff.
//
// Letters are never reused, so a retired or never-issued one keeps a row saying so — the set has
// to be total for the diff to mean anything.
// Guard G51: both builds enforce the same house rules.
//
// `./gradlew build` and `jk build` are supposed to check the same charter. Five letters — G46,
// G47, G48, G49, G50 — were written on the Gradle side and never grew a twin in
// `.jk/after-build.kts`, so for as long as that lasted the self-hosted build printed "house rules
// clean" while enforcing 36 of the 41 it claimed. A contributor running `jk build` got a green
// gate and a rule violation.
//
// That is not a drift a reader can notice: both gates print a count, and neither count is wrong
// about itself. So it is a ratchet, and the exception list lives in `guard-parity.txt` where each
// entry has to carry the reason the letter cannot live in both — "not ported yet" is not one.
//
// Checked from both sides, because a parity check that only one build runs has the shape of the
// problem it exists to prevent.
tasks.register("checkGuardParity") {
    group = "verification"
    description = "Fail when a guard letter is enforced by one build and not the other"
    val gradleScripts = fileTree(layout.projectDirectory) {
        include("buildSrc/src/main/kotlin/*.kts", "**/*.gradle.kts")
        exclude("**/build/**", "**/target/**")
    }
    val jkGate = layout.projectDirectory.file(".jk/after-build.kts")
    val exceptions = layout.projectDirectory.file("guard-parity.txt")
    inputs.files(gradleScripts).withPropertyName("gradleScripts")
    inputs.file(jkGate).withPropertyName("jkGate")
    inputs.file(exceptions).withPropertyName("exceptions")
    val stamp = layout.buildDirectory.file("guards/guard-parity.ok")
    outputs.file(stamp)
    doLast {
        // Same four spellings checkGuardRegistry scans for, and for the same reason: a scan for
        // any subset silently misses the rest.
        val marker = Regex("""(?m)^\s*(?://|/\*)?\s*(?:Guard G(\d+)\b|G(\d+)\s*—)|guard\("G(\d+)"""")
        fun lettersIn(text: String): Set<Int> = marker.findAll(text)
            .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() }.toInt() }
            .toSortedSet()
        val gradle = lettersIn(gradleScripts.files.sortedBy { it.path }.joinToString("\n") { it.readText() })
        val jk = lettersIn(jkGate.asFile.readText())
        if (gradle.isEmpty() || jk.isEmpty()) {
            throw GradleException("checkGuardParity read no guard letters from one of the two sides"
                    + " (gradle=${gradle.size}, jk=${jk.size}) — the scan broke and the guard is"
                    + " passing vacuously. Fix the pattern.")
        }
        val excused = Regex("""(?m)^G(\d+)\s""")
            .findAll(exceptions.asFile.readText()).map { it.groupValues[1].toInt() }.toSet()
        if (excused.isEmpty()) {
            throw GradleException("guard-parity.txt lists no letters, so this guard would pass over"
                    + " anything. Fix the file or the pattern.")
        }
        val gradleOnly = (gradle - jk - excused).sorted()
        val jkOnly = (jk - gradle - excused).sorted()
        // An exception nobody needs is a rule quietly weakened: the letter now exists in both, so
        // the entry is telling a reader parity was impossible when it is a fact.
        val stale = excused.filter { it in gradle && it in jk }.sorted()
        val problems = mutableListOf<String>()
        if (gradleOnly.isNotEmpty()) {
            problems.add("enforced by Gradle only: " + gradleOnly.joinToString(", ") { "G$it" }
                    + " — add the twin to .jk/after-build.kts")
        }
        if (jkOnly.isNotEmpty()) {
            problems.add("enforced by the jk gate only: " + jkOnly.joinToString(", ") { "G$it" }
                    + " — add the twin to buildSrc, or give it a guard-parity.txt entry saying why"
                    + " it is self-hosted-only (G0 and G44 are)")
        }
        if (stale.isNotEmpty()) {
            problems.add("excused in guard-parity.txt but present on BOTH sides: "
                    + stale.joinToString(", ") { "G$it" } + " — drop the entry, parity is real now")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("the two builds do not enforce the same house rules —\n  "
                    + problems.joinToString("\n  ")
                    + "\n  A contributor runs `jk build`; a gate that enforces less than it claims"
                    + " is worse than no gate. Port the rule, or record in guard-parity.txt why the"
                    + " letter cannot live in both.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.register("checkGuardRegistry") {
    group = "verification"
    description = "Fail when code-as-art.md's guard letters differ from the ones in buildSrc"
    // Guards live in buildSrc's convention plugins, in module build scripts, in this file, and in
    // `.jk/after-build.kts` — jk's self-hosted build logic. That last one was excluded once, on
    // the reasoning that it mirrors buildSrc. It mostly does: 38 of its 40 letters have a Gradle
    // twin. The two that do not are `checkCorpus` and the cross-build module-parity check, which
    // only make sense in the self-hosted build — so the exclusion hid exactly the guards that
    // motivated the file's existence, and left G44's row claiming the letter was never allocated.
    val scripts = fileTree(layout.projectDirectory) {
        include("buildSrc/src/main/kotlin/*.kts", "**/*.gradle.kts", ".jk/*.kts")
        exclude("**/build/**", "**/target/**")
    }
    val registry = layout.projectDirectory.file("docs/contributors/code-as-art.md")
    inputs.files(scripts).withPropertyName("guardScripts")
    inputs.file(registry).withPropertyName("registry")
    val stamp = layout.buildDirectory.file("guards/guard-registry.ok")
    outputs.file(stamp)
    doLast {
        // Four spellings of the letter exist in the tree — the comment forms `Guard G<n>:`,
        // `Guard G<n>.` and `G<n> — …`, plus `guard("G<n>", "task")`, which is a call rather than a
        // comment and is how the self-hosted build declares one. A scan for any subset silently
        // misses the rest; that is how G0 and G44 stayed invisible to both a hand reconciliation
        // and the first version of this task.
        val marker = Regex(
            """(?m)^\s*(?://|/\*)?\s*(?:Guard G(\d+)\b|G(\d+)\s*—)|guard\("G(\d+)"""")
        val declared = marker
            .findAll(scripts.files.sortedBy { it.path }.joinToString("\n") { it.readText() })
            .map { m -> m.groupValues.drop(1).first { it.isNotEmpty() }.toInt() }
            .toSortedSet()
        val listed = Regex("""(?m)^\| G(\d+) \|""")
            .findAll(registry.asFile.readText())
            .map { it.groupValues[1].toInt() }.toList()
        if (declared.isEmpty()) {
            throw GradleException("checkGuardRegistry found no `Guard G<n>` in buildSrc — the scan"
                    + " broke and the guard is passing vacuously. Fix the pattern.")
        }
        val duplicates = listed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        if (duplicates.isNotEmpty()) {
            throw GradleException("code-as-art.md lists these guard letters more than once: "
                    + duplicates.joinToString(", ") { "G$it" }
                    + ". A letter is allocated once; two rows means one of them is stale.")
        }
        val listedSet = listed.toSortedSet()
        val unlisted = declared - listedSet
        // A row with no code behind it is fine ONLY when it says so — retired and never-issued
        // letters are the reason the doc can be ahead of the scan.
        val rowSaysNone = Regex("""(?m)^\| G(\d+) \| (?:—|\*\(folded)""")
            .findAll(registry.asFile.readText()).map { it.groupValues[1].toInt() }.toSet()
        val phantom = listedSet - declared - rowSaysNone
        // The row says "no guard behind this letter" and there is one. Worse than a missing row:
        // it tells a reader the letter is free. G44's row claimed exactly this while
        // `checkBothBuildsSeeEveryModule` was live in the self-hosted build.
        val contradicted = rowSaysNone.filter { it in declared }.sorted()
        val problems = mutableListOf<String>()
        if (contradicted.isNotEmpty()) {
            problems.add("marked retired or never-issued in the table, but declared in the build: "
                    + contradicted.joinToString(", ") { "G$it" })
        }
        if (unlisted.isNotEmpty()) {
            problems.add("in the build, missing from the table: "
                    + unlisted.sorted().joinToString(", ") { "G$it" })
        }
        if (phantom.isNotEmpty()) {
            problems.add("in the table with no guard behind them, and not marked retired: "
                    + phantom.sorted().joinToString(", ") { "G$it" })
        }
        if (problems.isNotEmpty()) {
            throw GradleException("the guard registry in docs/contributors/code-as-art.md and the"
                    + " guards in buildSrc disagree —\n  " + problems.joinToString("\n  ")
                    + "\n  Add the row (id, task, rule, form), or give a retired letter a `| G<n> | — |`"
                    + " row saying it is not reusable. This table is where a contributor learns what"
                    + " the build enforces; one that lags is worse than none.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard G50: no KanArtist ticket id in the tree.
//
// Readers of this repository do not have the tracker and never will, so an id is a reference
// nobody can follow — and it has now leaked three times, each into a surface the previous sweep
// did not think to scan. First `*.java` / `*.kt` / `*.md` were cleaned and reported clean; then
// `*.kts` turned out to hold 153, 26 of them inside build-failure text; then the web client's CSS
// and JS held ~50, and a shipped Giter8 template wrote one into a *user's own new project*.
//
// That third one is why this is a guard rather than a fourth sweep. The rule was previously
// declined as "a small, closed set of files; the value is the strip, not a new ratchet" — the set
// was neither small nor closed, and the deciding evidence is jk emitting a ticket id into someone
// else's source tree.
//
// Extension-blind on purpose. Every scope this rule was given by extension was the reason it was
// missed the next time.
tasks.register("checkNoTicketIds") {
    group = "verification"
    description = "Fail the build on a KanArtist ticket id anywhere in the tree"
    val scanned = fileTree(layout.projectDirectory) {
        exclude("**/build/**", "**/target/**", ".git/**", ".gradle/**", ".firebase/**",
                "**/node_modules/**", ".board/**")
        // Binaries: nothing to read, and decoding them as text is noise.
        exclude("**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.webp", "**/*.ico",
                "**/*.jar", "**/*.zip", "**/*.xz", "**/*.gz", "**/*.class", "**/*.aot",
                "**/*.woff", "**/*.woff2", "**/*.ttf", "**/*.pdf")
    }
    inputs.files(scanned).withPropertyName("scanned")
    val treeRoot = layout.projectDirectory.asFile
    // The two places an id is the subject rather than a reference.
    val exempt = mapOf(
        "AGENTS.md" to "documents the board protocol an agent follows, ids included",
        "docs/contributors/comments.md" to "states the ban, using ids as its own examples",
    )
    val stamp = layout.buildDirectory.file("guards/no-ticket-ids.ok")
    outputs.file(stamp)
    doLast {
        val missing = exempt.keys.filterNot { treeRoot.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("checkNoTicketIds exempts files that no longer exist: "
                    + missing.sorted().joinToString(", ") + ". Drop the entries, or fix the paths.")
        }
        val id = Regex("""\bJK-\d{4}\b""")
        var candidates = 0
        val hits = mutableListOf<String>()
        scanned.files.sorted().forEach { f ->
            candidates++
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (exempt.containsKey(rel)) return@forEach
            val text = try { f.readText() } catch (e: Exception) { return@forEach }
            val found = id.findAll(text).map { it.value }.distinct().take(4).toList()
            if (found.isNotEmpty()) hits.add("  $rel: ${found.joinToString(", ")}")
        }
        if (candidates == 0) {
            throw GradleException("checkNoTicketIds scanned zero files — the file tree wiring broke"
                    + " and the guard is passing vacuously. Fix the exclude patterns.")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("a ticket id names a tracker this repository's readers do not"
                    + " have:\n" + hits.sorted().joinToString("\n")
                    + "\n  State the invariant or the defect instead — history belongs in the commit"
                    + " message, not the tree. Worst case is a Giter8 template, which writes the id"
                    + " into a user's own project.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard G49: jk has one home directory and the tree says so exactly once.
//
// Everything jk owns lives under $HOME/.jk on every platform. Before that it was XDG on
// Linux/macOS, Known Folders on Windows and a JK_HOME umbrella mirroring XDG, and the cost was
// never on disk — it was that ~130 comments, doc paragraphs and help strings had to carry an "it
// depends", and each of them could rot independently. This is the ratchet on that: reintroduce an
// old-layout spelling anywhere a reader can see it and the build says so.
//
// Comment-blind on purpose: the primary surface being protected IS comments and docs. A Javadoc
// describing a resolver that no longer exists is the defect, not an exception to it.
//
// Four files read XDG variables to find OTHER programs' files. They are correct and exempt.
tasks.register("checkSingleHomeRoot") {
    group = "verification"
    description = "Fail the build on an old-layout path or a retired JK_*_DIR spelling"
    val scanned = fileTree(layout.projectDirectory) {
        include("**/*.java", "**/*.kt", "**/*.kts", "**/*.md", "**/*.sh", "**/*.ps1", "**/*.bat",
                // The dashboard renders paths to users; it was outside this ban until a
                // hard-coded `~/.config/jk/config.toml` fallback shipped in its config panel.
                "**/*.html", "**/*.js", "**/*.mjs", "**/*.css")
        exclude("**/build/**", "**/target/**", ".git/**", ".gradle/**", ".firebase/**", "**/node_modules/**")
    }
    inputs.files(scanned).withPropertyName("scanned")
    val treeRoot = layout.projectDirectory.asFile
    val allowedLines = mapOf(
        "shared/core/src/main/java/cc/jumpkick/config/TerminalFonts.java"
            to Regex("""env\.apply\("XDG_CONFIG_HOME"\)"""),
        "shared/toolchain-jdk/src/main/java/cc/jumpkick/discovery/MiseProbe.java"
            to Regex("""MISE_DATA_DIR|XDG_DATA_HOME|\.local/share/mise"""),
        "shared/toolchain-jdk/src/test/java/cc/jumpkick/discovery/MiseProbeTest.java"
            to Regex("""XDG_DATA_HOME|\.local/share/mise"""),
        "shared/toolchain-jdk/src/main/java/cc/jumpkick/jdk/IntellijJdkTable.java"
            to Regex("""env\.apply\("(?:APPDATA|XDG_CONFIG_HOME)"\)"""),
        "shared/toolchain-jdk/src/test/java/cc/jumpkick/jdk/IntellijJdkTableTest.java"
            to Regex("""XDG_CONFIG_HOME"""),
        "shared/core/src/test/java/cc/jumpkick/util/JkDirsTest.java"
            to Regex(""""(?:XDG_[A-Z_]+|LOCALAPPDATA|APPDATA)""""),
        "clients/cli/src/main/java/cc/jumpkick/cli/engine/RetiredEngineLayouts.java"
            to Regex("""env\.apply\("(?:XDG_DATA_HOME|XDG_STATE_HOME|LOCALAPPDATA)"\)"""),
        "clients/cli/src/test/java/cc/jumpkick/cli/engine/EngineFleetTest.java"
            to Regex(""""LOCALAPPDATA""""),
        "clients/cli/src/test/java/cc/jumpkick/command/WrapperTemplateTest.java"
            to Regex("""doesNotContain\("(?:XDG_|JK_BIN_DIR|JK_INSTALL_DIR)"""),
    )
    val stamp = layout.buildDirectory.file("guards/single-home-root.ok")
    outputs.file(stamp)
    doLast {
        val missing = allowedLines.keys.filterNot { treeRoot.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("checkSingleHomeRoot exempts files that no longer exist, so the"
                    + " allowlist is stale and the guard is weaker than it reads: "
                    + missing.sorted().joinToString(", ")
                    + ". Drop the entries, or fix the paths.")
        }
        val banned = listOf(
            Regex("""\.local/share"""), Regex("""\.local/state"""), Regex("""\.local/bin"""),
            Regex("""\.cache/jk"""), Regex("""\.config/jk"""),
            Regex("""XDG_[A-Z_]+"""), Regex("""LOCALAPPDATA"""), Regex("""APPDATA"""),
            Regex("""<data>"""),
            Regex("""(?i)\bdata root\b"""), Regex("""(?i)\bdata/lib\b"""),
            Regex("""\$\{?JK_HOME\}?[/\\]data\b"""), Regex("""--data(?:-dir)?\b"""),
            Regex("""(?:homeDir\(\)|JkDirs\.home\(\))\.resolve\("data"\)"""),
            Regex("""JK_DATA_DIR|JK_BUILDS_DIR|JK_TMP_DIR|JK_BIN_DIR|JK_INSTALL_DIR"""),
            Regex("""JK_CONFIG_DIR|JK_CONFIG_FILE"""),
        )
        val fixtures = listOf(
            "the data root",
            "\$JK_HOME/data/lib",
            "\${JK_HOME}/data",
            """homeDir().resolve("data")""",
            "jk self nuke --data",
        )
        val missedFixtures = fixtures.filter { sample -> banned.none { it.containsMatchIn(sample) } }
        if (missedFixtures.isNotEmpty()) {
            throw GradleException("checkSingleHomeRoot no longer catches its fixtures: $missedFixtures")
        }
        var candidates = 0
        val hits = mutableListOf<String>()
        scanned.files.sorted().forEach { f ->
            candidates++
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            var text = f.readText()
            val markers = when (rel) {
                "build.gradle.kts" -> "// Guard G49:" to "// Guard: the published installers"
                ".jk/after-build.kts" -> "val singleHomeAllowedLines =" to "// The two places a ticket id"
                else -> null
            }
            if (markers != null) {
                var inside = false
                var starts = 0
                var ends = 0
                text = text.lineSequence().filter { line ->
                    if (!inside && line.startsWith(markers.first)) {
                        inside = true
                        starts++
                    }
                    if (inside && line.startsWith(markers.second)) {
                        inside = false
                        ends++
                    }
                    !inside && !line.startsWith(markers.first)
                }.joinToString("\n")
                if (starts != 1 || ends != 1) {
                    throw GradleException("checkSingleHomeRoot cannot isolate its definition in $rel")
                }
            }
            val allowed = allowedLines[rel]
            val found = text.lineSequence().withIndex().flatMap { (index, line) ->
                val visible = allowed?.replace(line, "") ?: line
                banned.asSequence().mapNotNull { it.find(visible)?.value?.let { value -> "$value:${index + 1}" } }
            }.distinct().toList()
            if (found.isNotEmpty()) hits.add("  $rel: ${found.joinToString(", ")}")
        }
        if (candidates == 0) {
            throw GradleException("checkSingleHomeRoot scanned zero files — the file tree wiring"
                    + " broke and the guard is passing vacuously. Fix the include patterns.")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("jk lives under \$HOME/.jk, on every platform, and these name a"
                    + " layout it does not have:\n" + hits.sorted().joinToString("\n")
                    + "\n  Resolve paths through cc.jumpkick.util.JkDirs (JK_HOME, plus"
                    + " JK_STORE_DIR / JK_CACHE_DIR / JK_STATE_DIR / JK_JDKS_DIR). If you are"
                    + " reading ANOTHER program's files, allow only the exact line shape here.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard: the published installers are byte-identical to the ones in the repo root. They were kept
// in sync by hand, so every edit to the bin-dir or store resolution had to be made twice and the
// two copies could — and did — disagree about where jk installs. A diff is cheaper than a
// convention nobody enforces; regenerate with `cp install.sh hosting/public/install.sh` (same for
// install.ps1) rather than editing the copy under hosting/.
tasks.register("checkPublishedInstallers") {
    group = "verification"
    val pairs = listOf("install.sh", "install.ps1")
    inputs.files(pairs.flatMap {
        listOf(layout.projectDirectory.file(it), layout.projectDirectory.file("hosting/public/$it"))
    })
    val root = layout.projectDirectory.asFile
    doLast {
        val stale = pairs.filter { name ->
            val src = root.resolve(name)
            val published = root.resolve("hosting/public/$name")
            if (!src.isFile) throw GradleException("checkPublishedInstallers: $name is missing from the repo root")
            !published.isFile || src.readText() != published.readText()
        }
        if (stale.isNotEmpty()) {
            throw GradleException("hosting/public/ has drifted from the repo-root installers: "
                    + stale.joinToString(", ")
                    + ". These are served to `curl | bash` users, so a stale copy installs jk somewhere "
                    + "the CLI does not look. Re-copy them: "
                    + stale.joinToString("; ") { "cp $it hosting/public/$it" })
        }
    }
}

// Guard: every relative link under docs/ resolves. The redirect stubs and the user/contributors
// split multiplied the ways a link can rot, and nothing caught one. Code spans and
// fenced blocks are stripped first — an example link inside backticks is prose, not navigation.
tasks.register("checkDocLinks") {
    group = "verification"
    val docsTree = fileTree(layout.projectDirectory.dir("docs")) { include("**/*.md") }
    inputs.files(docsTree)
    val root = layout.projectDirectory.asFile
    doLast {
        val link = Regex("""\[[^\]]*\]\(([^)\s]+)\)""")
        val broken = mutableListOf<String>()
        docsTree.files.sorted().forEach { f ->
            val prose = f.readText()
                .replace(Regex("""(?s)```.*?```"""), "")
                .replace(Regex("""`[^`\n]*`"""), "")
            link.findAll(prose).forEach { m ->
                val target = m.groupValues[1]
                if (target.startsWith("http://") || target.startsWith("https://")
                        || target.startsWith("mailto:") || target.startsWith("#")) {
                    return@forEach
                }
                val path = target.substringBefore('#')
                if (path.isEmpty()) return@forEach
                if (!f.parentFile.resolve(path).normalize().exists()) {
                    broken.add(f.relativeTo(root).invariantSeparatorsPath + ": " + target)
                }
            }
        }
        if (broken.isNotEmpty()) {
            throw GradleException("These docs/ links point at nothing — fix the path or the moved "
                    + "file:\n" + broken.joinToString("\n"))
        }
    }
}

tasks.register("checkGateCoverage") {
    group = "verification"
    description = "Fail when a structural guard is not reachable from checkFast"
    doLast {
        val moduleGuards = subprojects.flatMap { p ->
            p.tasks.filter { it.name.startsWith("check") && it.name !in nonGateChecks && it.name != "check" }
        }
        val scheduled = gradle.taskGraph.allTasks.toSet()
        val missingModuleGuards = moduleGuards.filterNot(scheduled::contains).map { it.path }
        val missingRootGuards = rootBranchGuards.filterNot(scheduled::contains).map { it.path }
        if (missingModuleGuards.isNotEmpty() || missingRootGuards.isNotEmpty()) {
            throw GradleException(buildString {
                append("checkFast does not reach every structural guard")
                if (missingModuleGuards.isNotEmpty()) append("; missing module guards: $missingModuleGuards")
                if (missingRootGuards.isNotEmpty()) append("; missing root guards: $missingRootGuards")
            })
        }
        logger.lifecycle("checkFast reaches ${moduleGuards.size} module guards and ${rootBranchGuards.size} root guards")
    }
}

// The shippable native-dist layout (docs/architecture.md "Ship layout"): the size-tuned native jk
// client next to the engine's fat jar. The engine is a JVM app, never a native image — the
// installed client spawns it on the jk-managed JDK as
// `java -cp ~/.jk/lib/jk-engine.jar EngineMain`. dist/lib carries the jar the
// installer materializes into <home>/lib (via `jk self materialize`).
val dist by tasks.registering(Sync::class) {
    description = "Assembles build/dist: the native jk client + lib/jk-engine-<version>.jar"
    group = "distribution"
    dependsOn(":cli:nativeCompile")
    from(project(":cli").layout.buildDirectory.dir("native/nativeCompile")) { include("jk", "jk.exe") }
    from(project(":engine").tasks.named("shadowJar")) { into("lib") }
    into(layout.buildDirectory.dir("dist"))
}

/**
 * Local dogfood refresh: all worker `installLocal` tasks, then `:engine:installLocal`.
 * Engine materialize prefers the native client from `./gradlew dist` (`build/dist/jk[.exe]`)
 * when that file exists; otherwise the thin `:cli:installDist` launcher. `:engine:installLocal`
 * mustRunAfter `dist` / `:cli:nativeCompile` so `./gradlew dist installLocal` does not exec a
 * binary still open for writing (Linux ETXTBSY).
 */
tasks.register("installLocal") {
    group = "distribution"
    description = "Side-load workers + materialize engine jar and bounce daemon for local dogfood"
    dependsOn(
        subprojects
            .filter { it.path != ":engine" }
            .map { it.tasks.matching { t -> t.name == "installLocal" } })
    finalizedBy(":engine:installLocal")
}
