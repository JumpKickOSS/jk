// SPDX-License-Identifier: Apache-2.0

// Root project. Conventions live in buildSrc/ and are applied per module.
// Library/plugin pins live in gradle/libs.versions.toml.

// Report which test tiers actually executed, and read their counts from TEST-*.xml.
// A gate that prints BUILD SUCCESSFUL for a cache read is not evidence about the current tree.
apply<GateReportPlugin>()

tasks.wrapper {
    gradleVersion = "9.7.0"
    distributionType = Wrapper.DistributionType.BIN
}

// Aggregate the slow tiers across all subprojects that register them. One tier per tag; the table
// is buildSrc/src/main/kotlin/TestTiers.kt and `checkAll` below runs `integrationTest` only.
tasks.register("integrationTest") {
    group = "verification"
    description = "Run @Tag(integration) tests in every module (not part of check)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "integrationTest" } })
}

// The branch gate's boundary lane: the integration classes curated-integration.txt names, run on
// every pull request while the rest of the tier stays nightly. Not a tier — every class here also
// runs in `integrationTest`, so nothing is reclassified to make the gate cheap.
tasks.register(CuratedIntegration.TASK) {
    group = "verification"
    description = "Run the curated integration classes (the branch gate's boundary lane)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == CuratedIntegration.TASK } })
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

// Also not reachable from checkAll: a microbench prints medians and asserts nothing about
// deltas, so gating on it would gate on CI noise. Nightly runs `benchTest`.
tasks.register("benchTest") {
    group = "verification"
    description = "Run @Tag(bench) microbenchmarks in every module (nightly, gates nothing)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "benchTest" } })
}

// JaCoCo inventory. Not a gate: no percentage, and the agent stays off unless `-Pjk.coverage`.
apply(plugin = "jacoco")
tasks.register<org.gradle.testing.jacoco.tasks.JacocoReport>("coverageReport") {
    group = "verification"
    description = "Aggregate unit-test JaCoCo XML/HTML (inventory; never a gate)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "test" } })
    // One known file per module, not a `**` walk of the whole checkout (which cannot prune and
    // fingerprinted every build/, target/ and sandbox tree to find ~30 files). The report base
    // filters missing files itself.
    executionData.from(subprojects.map { it.layout.buildDirectory.file("jacoco/test.exec") })
    // JacocoReportBase installs onlyIf("Any of the execution data files exists") in its
    // constructor, which made the empty-inventory check below unreachable: without -Pjk.coverage
    // the task was SKIPPED, BUILD SUCCESSFUL, no report. Always run, so the check can fire.
    setOnlyIf { true }
    subprojects.forEach { sub ->
        sub.pluginManager.withPlugin("java") {
            val main = sub.extensions.getByType<SourceSetContainer>().named("main")
            sourceDirectories.from(main.map { it.allSource.sourceDirectories })
            classDirectories.from(main.map { it.output.classesDirs })
        }
    }
    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/coverage.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
        csv.required.set(false)
    }
    doLast {
        val xml = reports.xml.outputLocation.get().asFile
        if (!xml.isFile || xml.length() < 200) {
            throw GradleException(
                "coverage inventory is empty — pass -Pjk.coverage so the JaCoCo agent runs")
        }
    }
}

// Per-module line-coverage ratchet (G91). Nightly, not the branch gate: it needs the JaCoCo agent
// (-Pjk.coverage) and every module's unit tier. One line per module in coverage-baseline.txt; a module
// below its line fails, a module above it rewrites its line in the same run, a new module is added.
// The jk side of this letter waits for `jk test` to write a JaCoCo XML (guard-parity.txt).
tasks.register("checkCoverageBand") {
    group = "verification"
    description = "Fail when a module's unit-test line coverage falls below its coverage-baseline.txt line; bank an improvement"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "test" || t.name == "jacocoTestReport" } })
    val baselineFile = layout.projectDirectory.file("coverage-baseline.txt")
    val reports = subprojects.associate { sub ->
        sub.projectDir.relativeTo(layout.projectDirectory.asFile).path.replace(File.separatorChar, '/') to
            sub.layout.buildDirectory.file("reports/jacoco/test/jacocoTestReport.xml")
    }
    val coverage = project.hasProperty("jk.coverage")
    inputs.property("jk.coverage", coverage)
    doLast {
        if (!coverage) {
            throw GradleException("checkCoverageBand needs the JaCoCo agent — pass -Pjk.coverage")
        }
        val measured = reports.mapNotNull { (module, xml) ->
            val f = xml.get().asFile
            if (f.isFile) CoverageBand.parseReport(module, f.readText()) else null
        }
        if (measured.isEmpty()) {
            throw GradleException("checkCoverageBand read no JaCoCo reports — did the unit tier run with -Pjk.coverage?")
        }
        val (header, baseline) = CoverageBand.readBaseline(baselineFile.asFile)
        val verdicts = CoverageBand.judge(baseline, measured)
        val next = CoverageBand.render(header, baseline, verdicts)
        val banked = verdicts.filter { it.kind == CoverageBand.Kind.IMPROVED || it.kind == CoverageBand.Kind.ADDED }
        if (next != (if (baselineFile.asFile.isFile) baselineFile.asFile.readText() else "")) {
            baselineFile.asFile.writeText(next)
            logger.lifecycle("coverage-baseline.txt tightened for " + banked.joinToString(", ") {
                "%s (%s -> %.1f)".format(it.module, it.baseline?.let { b -> "%.1f".format(b) } ?: "new", it.measured)
            } + " — commit it")
        }
        val regressions = CoverageBand.regressions(verdicts)
        if (regressions.isNotEmpty()) {
            throw GradleException("unit-test line coverage fell below coverage-baseline.txt —\n  "
                    + regressions.joinToString("\n  ")
                    + "\n  Cover the change, or move the line and say why in the commit.")
        }
    }
}

// buildSrc's own tests (the guard catalog's invariants: letters total and unique, task names
// unique, MODULE guards that scan tests off the jar hook, owners declared) are not run by the
// root build — Gradle only compiles buildSrc — so they are run here as a nested invocation and
// the branch gate depends on it. Named test*, not check*: checkGateCoverage treats every root
// check* task as a lettered guard.
// The test homes moved out of the checkout (see jk.testing), so `clean` no longer reaches them and
// the age/size sweep in jk.testing is what bounds them. This is the manual escape hatch, scoped to
// this checkout's key so a sibling worktree's primed store is not collateral.
tasks.register<Delete>("cleanTestHomes") {
    group = "build"
    description = "Delete this checkout's out-of-tree test JK_HOMEs (jk.testing sweeps them by age and size)"
    val root = JkLayoutPaths.testHomeRoot()
    val key = JkLayoutPaths.checkoutKey(rootDir)
    delete(File(root, key))
    doLast { println("removed test homes under ${File(root, key)}") }
}

val testBuildSrc = tasks.register<Exec>("testBuildSrc") {
    group = "verification"
    description = "Run buildSrc's own tests (guard catalog invariants)"
    val windows = System.getProperty("os.name").lowercase().contains("win")
    // Absolute, not bare: Windows CreateProcess resolves a bare program name against PATH and the
    // application directory, never against the child's working directory, so "gradlew.bat" is only
    // found when the repo root happens to be on PATH.
    val wrapper = projectDir.resolve(if (windows) "gradlew.bat" else "gradlew").absolutePath
    workingDir = projectDir
    commandLine(wrapper, "-p", "buildSrc", "test", "-q")
    inputs.dir("buildSrc/src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("buildSrc/build.gradle.kts")
    val marker = layout.buildDirectory.file("buildSrc-tests.ok")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("buildSrc tests passed\n") }
}

val nonGateChecks = setOf("check", "checkFast", "checkAll", "checkCoverageBand")
val rootBranchGuards = tasks.matching { it.name.startsWith("check") && it.name !in nonGateChecks }
val checkFast = tasks.register("checkFast") {
    group = "verification"
    description = "Run unit tests, buildSrc's tests, and every network-free structural guard"
    dependsOn(subprojects.map { it.tasks.matching { task -> task.name == "check" } }, rootBranchGuards, testBuildSrc)
}

// The root has no `base` plugin, so `./gradlew build` and `./gradlew check` used to run every
// module's lifecycle and none of the tree-wide root guards: `build dist` passed with ticket ids
// in the tree that only checkAll caught, commits later. jk build runs the same guards on every
// build with work; the Gradle lifecycle now reaches them too. Cost on a no-op build is their
// up-to-date checks — measured in docs/contributors/code-as-art.md, "Where a guard runs".
val rootCheck = tasks.register("check") {
    group = "verification"
    description = "Every tree-wide root guard"
    dependsOn(rootBranchGuards)
}
tasks.register("build") {
    group = "build"
    description = "Root lifecycle: the tree-wide root guards"
    dependsOn(rootCheck)
}

tasks.register("checkAll") {
    group = "verification"
    description = "checkFast + integrationTest for the whole repo"
    dependsOn(checkFast, "integrationTest")
}

// Guard G79: the published guard registry lists exactly the letters the build enforces.
//
// `docs/contributors/code-as-art.md` is where a contributor learns what this build checks, and it
// has now fallen behind the code twice — first by six letters, then by eleven, which is how
// allocating G49 nearly collided with a live guard. Two reconciliations by hand is the argument
// for the third not being by hand: read one side out of the code, read the other out of the doc,
// diff.
//
// Letters are never reused, so a retired or never-issued one keeps a row saying so — the set has
// to be total for the diff to mean anything.
// Guard G51: both builds enforce the same house rules.
//
// `./gradlew build` and `jk build` check the same charter, and the registry (`Guards`) is the one
// record of where each letter lives: a Gradle task, a jk-guards.toml rule, an engine validation, a
// guard test. A letter with a Gradle task and no jk side is enforced half the time, and neither
// gate's count is wrong about itself, so this is a ratchet: `guard-parity.txt` carries the reason a
// letter is Gradle-only, and "not ported yet" is not one. The jk side runs the same check from the
// same registry as the `guard-parity` guard test.
tasks.register("checkGuardParity") {
    group = "verification"
    description = "Fail when a guard letter is enforced by one build and not the other"
    val catalog = layout.projectDirectory.file("buildSrc/src/main/kotlin/Guards.kt")
    val jkRules = layout.projectDirectory.file("jk-guards.toml")
    val exceptions = layout.projectDirectory.file("guard-parity.txt")
    val nullMarking = layout.projectDirectory.file("buildSrc/src/main/kotlin/NullMarking.kt")
    val manifests = layout.projectDirectory.files(
        NullMarking.enforcedRoots.map { it.substringBefore("/src/") + "/jk.toml" }
            + fileTree(layout.projectDirectory) {
                include("*/*/jk.toml")
                exclude("**/build/**", "**/target/**")
            }.files.map { it.relativeTo(layout.projectDirectory.asFile).path })
    inputs.file(catalog).withPropertyName("catalog")
    inputs.file(nullMarking).withPropertyName("nullMarking")
    inputs.files(manifests).withPropertyName("manifests")
    inputs.files(jkRules).withPropertyName("jkRules")
    // The engine's record of the rules file it loaded (GuardsPresence.RULES_HASH_FILE under the
    // build output); an input only when it exists.
    val jkRulesHashRelPath = "target/jk-guards.sha256"
    inputs.files(fileTree(layout.projectDirectory) { include(jkRulesHashRelPath) }).withPropertyName("jkRulesHash")
    inputs.file(exceptions).withPropertyName("exceptions")
    val stamp = layout.buildDirectory.file("guards/guard-parity.ok")
    outputs.file(stamp)
    doLast {
        val gradle = Guards.gradleLetters
        // A letter is jk-enforced when its registered jk-guards.toml rule exists, when the engine
        // validates it under a reserved code, or when a guard test declares it. A registered rule
        // with no table, or a table no letter claims, is the registry lagging the code.
        val tables = if (jkRules.asFile.isFile) {
            Regex("""(?m)^\[guards\.([a-z0-9][a-z0-9-]*)]""").findAll(jkRules.asFile.readText())
                .map { it.groupValues[1] }.toSortedSet()
        } else sortedSetOf<String>()
        val mapped = Guards.tomlLetters
        val jk = (mapped.filterValues { it in tables }.keys
                + Guards.engineLetters.keys
                + Guards.guardTestLetters.keys).toSortedSet()
        val registryProblems = mutableListOf<String>()
        mapped.filterValues { it !in tables }.forEach { (n, id) ->
            registryProblems.add("Guards says G$n is enforced by [guards.$id] but jk-guards.toml has no such table")
        }
        (tables - mapped.values.toSet()).forEach { id ->
            registryProblems.add("jk-guards.toml declares [guards.$id] but no Guards letter claims it (ruleId = \"$id\")")
        }
        if (registryProblems.isNotEmpty()) {
            throw GradleException("the guard registry and jk-guards.toml disagree —\n  " + registryProblems.joinToString("\n  "))
        }
        // Rule-file parity: `jk build` records the digest of the rules file it loaded under its build
        // output. A digest that differs from the file Gradle sees means the two builds enforced
        // different rules; no digest means no jk build has run on this checkout, which is not drift.
        val recorded = File(jkRules.asFile.parentFile, jkRulesHashRelPath)
        if (recorded.isFile) {
            val seen = java.security.MessageDigest.getInstance("SHA-256").digest(jkRules.asFile.readBytes())
                .joinToString("") { "%02x".format(it) }
            val theirs = recorded.readText().trim()
            if (seen != theirs) {
                throw GradleException("jk-guards.toml is not the rule file the last `jk build` enforced"
                        + " (recorded $theirs, now $seen) — run `jk build` so both builds see the same rules")
            }
        }
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
        // A letter the registry places on the jk side alone (SELF_HOSTED, or a guard test with no
        // Gradle task) needs no excuse: the registry is its record. An excuse nobody needs is a rule
        // quietly weakened: the letter has both sides, so the entry says parity is impossible when
        // it is a fact.
        val stale = excused.filter { it in jk }.sorted()
        val unknown = (excused - Guards.all.mapNotNull { it.letter }.toSet()).sorted()
        val problems = mutableListOf<String>()
        if (gradleOnly.isNotEmpty()) {
            problems.add("enforced by Gradle only: " + gradleOnly.joinToString(", ") { "G$it" }
                    + " — give the letter a jk side in Guards (ruleId, engineCode or guardTestId),"
                    + " or a guard-parity.txt entry saying why it is Gradle-only")
        }
        if (stale.isNotEmpty()) {
            problems.add("excused in guard-parity.txt but enforced on the jk side too: "
                    + stale.joinToString(", ") { "G$it" } + " — drop the entry, parity is real now")
        }
        if (unknown.isNotEmpty()) {
            problems.add("excused in guard-parity.txt but not a registry letter: " + unknown.joinToString(", ") { "G$it" })
        }
        if (problems.isNotEmpty()) {
            throw GradleException("the two builds do not enforce the same house rules —\n  "
                    + problems.joinToString("\n  ")
                    + "\n  A contributor runs `jk build`; a gate that enforces less than it claims"
                    + " is worse than no gate. Port the rule, or record in guard-parity.txt why the"
                    + " letter cannot live in both.")
        }
        // Nullness parity: the convention plugin enforces NullAway on exactly NullMarking.enforcedRoots;
        // the jk side is a `[javac.plugins.ErrorProne]` table naming NullAway at error severity in the
        // same module's jk.toml. A module on one list and not the other is a rule enforced by one build.
        val nullAwayOn = Regex("""(?m)^\[javac\.plugins\.ErrorProne]""")
        val nullAwayError = Regex(""""-Xep:NullAway:ERROR"""")
        val gradleNull = NullMarking.enforcedRoots.map { it.substringBefore("/src/") }.toSortedSet()
        val jkNull = manifests.files.filter { it.isFile }
            .filter { f -> f.readText().let { nullAwayOn.containsMatchIn(it) && nullAwayError.containsMatchIn(it) } }
            .map { it.parentFile.relativeTo(layout.projectDirectory.asFile).path }.toSortedSet()
        val nullProblems = mutableListOf<String>()
        (gradleNull - jkNull).forEach { nullProblems.add("$it: NullMarking.enforcedRoots lists it, its jk.toml has no [javac.plugins.ErrorProne] with -Xep:NullAway:ERROR") }
        (jkNull - gradleNull).forEach { nullProblems.add("$it: jk.toml runs NullAway at error severity, NullMarking.enforcedRoots does not list it") }
        if (nullProblems.isNotEmpty()) {
            throw GradleException("nullness is enforced by one build and not the other —\n  "
                    + nullProblems.joinToString("\n  ")
                    + "\n  Add the module to both NullMarking.enforcedRoots and its jk.toml [javac] table, or to neither.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.register("checkGuardRegistry") {
    group = "verification"
    description = "Fail when code-as-art.md's guard table differs from Guards"
    val catalog = layout.projectDirectory.file("buildSrc/src/main/kotlin/Guards.kt")
    val registry = layout.projectDirectory.file("docs/contributors/code-as-art.md")
    inputs.file(catalog).withPropertyName("catalog")
    inputs.file(registry).withPropertyName("registry")
    val stamp = layout.buildDirectory.file("guards/guard-registry.ok")
    outputs.file(stamp)
    val jkRules = layout.projectDirectory.file("jk-guards.toml")
    inputs.file(jkRules).withPropertyName("jkRules")
    doLast {
        // rule id → kind, so the table says which TOML kind enforces a letter on the jk side
        val kinds = Regex("""(?m)^\[guards\.([a-z0-9][a-z0-9-]*)]\s*\n\s*kind\s*=\s*"([a-z-]+)"""")
            .findAll(jkRules.asFile.readText()).associate { it.groupValues[1] to it.groupValues[2] }
        val expected = Guards.tableMarkdown(kinds)
        val actual = registry.asFile.readText()
        val block = Regex("""(?s)<!-- guards:start -->.*?<!-- guards:end -->""").find(actual)?.value
            ?: throw GradleException(
                "docs/contributors/code-as-art.md is missing its generated guard table markers")
        if (block != expected) {
            throw GradleException(
                "docs/contributors/code-as-art.md differs from Guards; replace its marked table with:\n$expected")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

tasks.register("checkGateCoverage") {
    group = "verification"
    description = "Fail when a structural guard is not reachable from checkFast"
    doLast {
        val lifecycle = setOf("check", "checkAll", "checkFast")
        val registered = Guards.all.filter { it.registers }.map { it.task }.toSet()
        // Reachability from checkFast is a property of how the build is wired, not of whatever a
        // given invocation happens to schedule. Reading gradle.taskGraph.allTasks conflated the
        // two: inside `./gradlew checkFast` the graph is the closure and the verdict was right,
        // but run by name on its own — which its verification group and description both invite —
        // the graph held only this task, and it reported ~900 correctly-wired guards as
        // unreachable. A guard whose answer depends on how it was reached teaches everyone to
        // distrust it, and this one is the guard that guards the others.
        //
        // One level, not a closure, because one level is the whole of the wiring: checkFast takes
        // every subproject `check` and every root check* task, and a guard joins the gate by
        // hanging off one of those. Walking transitively instead reaches `check` -> `test` ->
        // `jar` and resolves a configuration at execution time, which Gradle refuses without an
        // exclusive lock — a build failure where a verdict was asked for.
        val reachable = mutableSetOf<Task>()
        rootBranchGuards.forEach(reachable::add)
        allprojects.forEach { p ->
            val lifecycleCheck = p.tasks.findByName("check") ?: return@forEach
            reachable.addAll(lifecycleCheck.taskDependencies.getDependencies(lifecycleCheck))
        }
        fun Project.taskPath(name: String): String = if (path == ":") ":$name" else "$path:$name"
        val expected =
            allprojects.flatMap { p ->
                p.tasks.names.filter { it in registered && Guards.named(it).inFastGate }.map { name ->
                    p.tasks.getByName(name)
                }
            }
        val missing = expected.filterNot(reachable::contains).map { it.path }
        val unregistered =
            allprojects.flatMap { p ->
                p.tasks.names.filter {
                    it.startsWith("check") && it !in lifecycle && it !in registered
                }.map { p.taskPath(it) }
            }
        val host = project(":host")
        val missingOnHost =
            Guards.all.filter {
                it.home == GuardHome.MODULE &&
                    it.inFastGate &&
                    !it.mavenPublishOnly &&
                    it.task !in host.tasks.names
            }.map { it.task }
        val auditor = project(":auditor")
        val missingOnPlugin =
            Guards.all.filter {
                it.home == GuardHome.PLUGIN_MODULE && it.task !in auditor.tasks.names
            }.map { it.task }
        val missingOwned =
            Guards.all.filter { it.home == GuardHome.MODULE_OWNED && it.inFastGate }.mapNotNull { spec ->
                val owner = spec.ownerPath ?: return@mapNotNull spec.task
                spec.task.takeIf { it !in project(owner).tasks.names }?.let { "$owner:$it" }
            }
        val missingRoot =
            Guards.all.filter {
                it.home == GuardHome.ROOT && it.inFastGate && it.task !in tasks.names
            }.map { it.task }
        val problems = mutableListOf<String>()
        if (missing.isNotEmpty()) problems.add("not in the checkFast graph: $missing")
        if (unregistered.isNotEmpty()) problems.add("check* tasks missing from Guards: $unregistered")
        if (missingOnHost.isNotEmpty()) problems.add(":host is missing module guards: $missingOnHost")
        if (missingOnPlugin.isNotEmpty()) problems.add(":auditor is missing plugin guards: $missingOnPlugin")
        if (missingOwned.isNotEmpty()) problems.add("owned-module guards were not registered: $missingOwned")
        if (missingRoot.isNotEmpty()) problems.add("root guards were not registered: $missingRoot")
        if (problems.isNotEmpty()) {
            throw GradleException("checkFast does not reach every structural guard —\n  "
                    + problems.joinToString("\n  "))
        }
        logger.lifecycle(
            "checkFast reaches ${expected.size} registered guards (${rootBranchGuards.size} root check* tasks)")
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
 * Engine materialize runs through a client that reports the engine jar's own version —
 * `:cli:nativeCompile` output first, then the thin `:cli:installDist` launcher, then a
 * ship-layout `build/dist/jk[.exe]` — and fails when none does. `:engine:installLocal`
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

// Every resolvable configuration is locked: gradle.lockfile beside each build script is the Gradle
// side of what jk-lock.toml is for jk. `./gradlew resolveAndLockAll --write-locks` re-locks after a
// catalog edit; a resolution that disagrees with the lock fails the build instead of drifting.
allprojects {
    dependencyLocking { lockAllConfigurations() }
    tasks.register("resolveAndLockAll") {
        description = "Resolves every configuration so --write-locks / --write-verification-metadata see them all"
        notCompatibleWithConfigurationCache("resolves every configuration at execution time")
        doLast { configurations.filter { it.isCanBeResolved }.forEach { it.resolve() } }
    }
}

// Prints the generated guard table so a registry change can be pasted between the markers in
// docs/contributors/code-as-art.md; `checkGuardRegistry` is the check, this is the pen.
tasks.register("printGuardRegistry") {
    group = "verification"
    description = "Print the generated guard table for docs/contributors/code-as-art.md"
    doLast { println(Guards.tableMarkdown()) }
}
