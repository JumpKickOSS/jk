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

// buildSrc's own tests (the guard catalog's invariants: letters total and unique, task names
// unique, MODULE guards that scan tests off the jar hook, owners declared) are not run by the
// root build — Gradle only compiles buildSrc — so they are run here as a nested invocation and
// the branch gate depends on it. Named test*, not check*: checkGateCoverage treats every root
// check* task as a lettered guard.
val testBuildSrc = tasks.register<Exec>("testBuildSrc") {
    group = "verification"
    description = "Run buildSrc's own tests (guard catalog invariants)"
    val windows = System.getProperty("os.name").lowercase().contains("win")
    workingDir = projectDir
    commandLine(if (windows) "gradlew.bat" else "./gradlew", "-p", "buildSrc", "test", "-q")
    inputs.dir("buildSrc/src").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("buildSrc/build.gradle.kts")
    val marker = layout.buildDirectory.file("buildSrc-tests.ok")
    outputs.file(marker)
    doLast { marker.get().asFile.writeText("buildSrc tests passed\n") }
}

val nonGateChecks = setOf("check", "checkFast", "checkAll")
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

// Guard G53: every production package in the three enforced API boundary modules is @NullMarked.
tasks.register("checkNullMarkedApiPackages") {
    group = "verification"
    description = "Fail when an enforced API boundary package lacks package-level @NullMarked"
    val roots = listOf(
        layout.projectDirectory.dir("shared/jk-api/src/main/java"),
        layout.projectDirectory.dir("shared/wire/src/main/java"),
        layout.projectDirectory.dir("shared/plugin-sdk/src/main/java"))
    val sources = roots.map { root -> fileTree(root) { include("**/*.java") } }
    inputs.files(sources)
    val stamp = layout.buildDirectory.file("guards/null-marked-api-packages.ok")
    outputs.file(stamp)
    doLast {
        val packagePattern = Regex("""(?m)^\s*package\s+([A-Za-z_][\w.]*)\s*;""")
        val packages = sources.flatMap { it.files }
            .filterNot { it.name == "package-info.java" }
            .mapNotNull { packagePattern.find(it.readText())?.groupValues?.get(1) }
            .toSortedSet()
        if (packages.size != 14) {
            throw GradleException(
                "The null-marked API guard found ${packages.size} production packages; it was measured against 14."
                    + " The source roots or package parser drifted, so do not trust a green result.")
        }
        val missing = packages.filter { pkg ->
            val relative = pkg.replace('.', '/') + "/package-info.java"
            val marker = roots.asSequence().map { it.file(relative).asFile }.firstOrNull(File::isFile)
            marker == null
                || !marker.readText().contains("@NullMarked")
                || packagePattern.find(marker.readText())?.groupValues?.get(1) != pkg
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Production API packages must declare package-level @NullMarked:\n"
                    + missing.joinToString("\n") { "  $it" })
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard G54: first-party plugins compile against the SDK unless a current product invariant owns
// the narrower exception. Server implementation modules may never enter a plugin runtime closure.
tasks.register("checkPluginSdkBoundary") {
    group = "verification"
    description = "Reject unclassified plugin project dependencies and server runtime leaks"
    val pluginBuilds = fileTree(layout.projectDirectory.dir("plugins")) {
        include("*/build.gradle.kts")
    }
    inputs.files(pluginBuilds)
    val stamp = layout.buildDirectory.file("guards/plugin-sdk-boundary.ok")
    outputs.file(stamp)
    doLast {
        val allowed = mapOf(
            "auditor|implementation|:core" to "lockfile and audit report model",
            "publisher|implementation|:core" to "manifest, lockfile, and build-layout model",
            "publisher|implementation|:client-io" to "repository upload transports",
            "publisher|testImplementation|:core" to "session-boundary test fixtures",
            "image-builder|implementation|:jk-api" to "image and repository credential model",
            "image-builder|testImplementation|:host" to "repository-root test fixture",
            "minified|implementation|:dynamic-surface" to "shared reachability and keep-rule model",
            "grails|implementation|:spring-boot" to "Boot jar packaging composition")
        val edgePattern = Regex(
            """(?m)^\s*(implementation|api|compileOnly|runtimeOnly|testImplementation|testRuntimeOnly|bundledCodec)\s*\(\s*(?:testFixtures\s*\(\s*)?project\s*\(\s*"(:[^"]+)"""")
        val seenExceptions = mutableSetOf<String>()
        val violations = mutableListOf<String>()
        val files = pluginBuilds.files.sorted()
        if (files.size != 15) {
            throw GradleException(
                "The plugin SDK boundary guard found ${files.size} plugin builds; it was measured against 15.")
        }
        files.forEach { file ->
            val plugin = file.parentFile.name
            edgePattern.findAll(file.readText()).forEach { match ->
                val configuration = match.groupValues[1]
                val target = match.groupValues[2]
                val key = "$plugin|$configuration|$target"
                val baseline = target == ":plugin-sdk"
                    || configuration == "bundledCodec" && target == ":host"
                when {
                    baseline -> Unit
                    key in allowed -> seenExceptions.add(key)
                    else -> violations.add("  $plugin $configuration -> $target")
                }
            }
        }
        val stale = allowed.keys - seenExceptions
        if (stale.isNotEmpty()) {
            violations.addAll(stale.sorted().map { "  stale allowlist entry: $it" })
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "First-party plugin dependencies must stay behind plugin-sdk or a documented"
                    + " current exception; server modules are never allowed at runtime:\n"
                    + violations.sorted().joinToString("\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
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

// Guard G55: engine configuration docs are rendered from EngineControls, never retyped.
tasks.register("checkEngineConfigDocs") {
    group = "verification"
    description = "Fail when docs/user/engine.md differs from EngineControls"
    val model = layout.projectDirectory.file(
        "shared/core/src/main/java/cc/jumpkick/config/EngineControls.java")
    val docs = layout.projectDirectory.file("docs/user/engine.md")
    inputs.files(model, docs)
    doLast {
        val modelText = model.asFile.readText()
        val row = Regex("""control\(\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*"([^"]*)"\s*,\s*(?:"([^"]*)"|([A-Z_]+))\s*,\s*"([^"]*)"\s*\)""")
        // The fourth argument is a literal or one of the class's own String constants (ENGINE_START).
        val constants = Regex("""static final String ([A-Z_]+) = "([^"]*)";""")
            .findAll(modelText).associate { it.groupValues[1] to it.groupValues[2] }
        val rows = row.findAll(modelText).map {
            val read = it.groupValues[4].ifEmpty {
                constants[it.groupValues[5]] ?: throw GradleException("EngineControls: unknown constant ${it.groupValues[5]}")
            }
            listOf(it.groupValues[1], it.groupValues[2], it.groupValues[3], read, it.groupValues[6])
        }.toList()
        val table = rows.filter { it[0].isNotEmpty() }
        val process = rows.filter { it[0].isEmpty() }
        if (table.size < 5) {
            throw GradleException("EngineControls TABLE parsed ${table.size} rows; expected at least 5")
        }
        if (process.size < 6) {
            throw GradleException("EngineControls PROCESS parsed ${process.size} rows; expected at least 6")
        }
        fun block(id: String, header: String, rule: String, body: List<List<String>>, line: (List<String>) -> String): String {
            val sb = StringBuilder()
            sb.appendLine("<!-- $id:start -->")
            sb.appendLine(header)
            sb.appendLine(rule)
            body.forEach { sb.appendLine(line(it)) }
            sb.append("<!-- $id:end -->")
            return sb.toString()
        }
        val expectedTable = block(
            "engine-config",
            "| Key | Env | Default | Read | Meaning |",
            "|---|---|---|---|---|",
            table,
        ) { "| `${it[0]}` | `${it[1]}` | ${it[2]} | ${it[3]} | ${it[4]} |" }
        val expectedProcess = block(
            "engine-process",
            "| Env | Default | Meaning |",
            "|---|---|---|",
            process,
        ) { "| `${it[1]}` | ${it[2]} | ${it[4]} |" }
        val actual = docs.asFile.readText()
        fun present(id: String): String =
            Regex("""(?s)<!-- $id:start -->.*?<!-- $id:end -->""").find(actual)?.value
                ?: throw GradleException("docs/user/engine.md is missing its $id table markers")
        val drift = mutableListOf<String>()
        if (present("engine-config") != expectedTable) {
            drift.add("replace the engine-config table with:\n$expectedTable")
        }
        if (present("engine-process") != expectedProcess) {
            drift.add("replace the engine-process table with:\n$expectedProcess")
        }
        if (drift.isNotEmpty()) {
            throw GradleException("docs/user/engine.md differs from EngineControls:\n" + drift.joinToString("\n"))
        }
    }
}

// Guard G56: the wrapper task version and gradle-wrapper.properties must match, and every
// setup-node step must read .nvmrc.
tasks.register("checkBootstrapVersions") {
    group = "verification"
    description = "Fail when Gradle wrapper versions or the Node pin disagree"
    val wrapperProps = layout.projectDirectory.file("gradle/wrapper/gradle-wrapper.properties")
    val rootBuild = layout.projectDirectory.file("build.gradle.kts")
    val nvmrc = layout.projectDirectory.file(".nvmrc")
    val workflows = fileTree(layout.projectDirectory.dir(".github/workflows")) { include("*.yml") }
    inputs.files(wrapperProps, rootBuild, nvmrc, workflows)
    doLast {
        val problems = mutableListOf<String>()
        val fromProps = Regex("""gradle-(\d+\.\d+(?:\.\d+)?)""")
            .find(wrapperProps.asFile.readText())?.groupValues?.get(1)
        val fromTask = Regex("""gradleVersion\s*=\s*"([^"]+)"""")
            .find(rootBuild.asFile.readText())?.groupValues?.get(1)
        if (fromProps.isNullOrBlank()) {
            problems.add("gradle-wrapper.properties has no gradle-N.N.N distribution")
        }
        if (fromTask.isNullOrBlank()) {
            problems.add("tasks.wrapper in build.gradle.kts does not set gradleVersion")
        }
        if (!fromProps.isNullOrBlank() && !fromTask.isNullOrBlank() && fromProps != fromTask) {
            problems.add("wrapper task is $fromTask but gradle-wrapper.properties is $fromProps")
        }
        val pin = if (nvmrc.asFile.isFile) nvmrc.asFile.readText().trim() else ""
        if (!Regex("""^\d+(\.\d+)*$""").matches(pin)) {
            problems.add(".nvmrc must be a Node version token (got ${pin.ifBlank { "missing" }})")
        }
        val setupNode = workflows.files.filter { it.readText().contains("actions/setup-node") }
        if (setupNode.isEmpty()) {
            problems.add("no workflow uses actions/setup-node — the dashboard JS gate would skip Node")
        }
        setupNode.forEach { wf ->
            val body = wf.readText()
            if (!Regex("""node-version-file:\s*['\"]\.nvmrc['\"]""").containsMatchIn(body)) {
                problems.add("${wf.name}: setup-node must set node-version-file: '.nvmrc'")
            }
            if (Regex("""(?m)^\s+node-version:\s""").containsMatchIn(body)) {
                problems.add("${wf.name}: setup-node must not also set node-version (the pin is .nvmrc)")
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Bootstrap versions disagree:\n  " + problems.joinToString("\n  "))
        }
    }
}

// Guard G57: nightly CI must keep running coverage, benches, and the macOS/Windows product smoke.
tasks.register("checkCiCadence") {
    group = "verification"
    description = "Fail when nightly CI drops coverage, benches, or OS smoke"
    val nightly = layout.projectDirectory.file(".github/workflows/ci-nightly.yml")
    val branch = layout.projectDirectory.file(".github/workflows/ci.yml")
    val smoke = layout.projectDirectory.file("scripts/ci-product-smoke.sh")
    val rootBuild = layout.projectDirectory.file("build.gradle.kts")
    inputs.files(nightly, branch, smoke, rootBuild)
    doLast {
        val problems = mutableListOf<String>()
        val nightlyText = nightly.asFile.readText()
        val branchText = branch.asFile.readText()
        if (!smoke.asFile.isFile) {
            problems.add("scripts/ci-product-smoke.sh is missing")
        }
        if (!nightlyText.contains("./gradlew benchTest")) {
            problems.add("ci-nightly.yml must run ./gradlew benchTest")
        }
        if (!nightlyText.contains("coverageReport") || !nightlyText.contains("-Pjk.coverage")) {
            problems.add("ci-nightly.yml must run coverageReport -Pjk.coverage")
        }
        if (!nightlyText.contains("macos-")) {
            problems.add("ci-nightly.yml must have a macOS smoke runner")
        }
        if (!nightlyText.contains("windows-")) {
            problems.add("ci-nightly.yml must have a Windows smoke runner")
        }
        if (!nightlyText.contains("ci-product-smoke.sh")) {
            problems.add("ci-nightly.yml must run scripts/ci-product-smoke.sh")
        }
        if (branchText.contains("coverageReport")
                || branchText.contains("-Pjk.coverage")
                || branchText.contains("benchTest")) {
            problems.add("ci.yml must not run coverage or benches (they are nightly, non-gating)")
        }
        if (!rootBuild.asFile.readText().contains("\"coverageReport\"")) {
            problems.add("build.gradle.kts must register coverageReport")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("CI cadence is incomplete:\n  " + problems.joinToString("\n  "))
        }
    }
}

// Guard G58: GitHub SECURITY.md and docs/user/security.md stay the reporting path.
tasks.register("checkSecurityDocs") {
    group = "verification"
    description = "Fail when security-reporting docs or the advisory URL drop"
    val policy = layout.projectDirectory.file("SECURITY.md")
    val page = layout.projectDirectory.file("docs/user/security.md")
    val index = layout.projectDirectory.file("docs/user/README.md")
    inputs.files(policy, page, index)
    doLast {
        val problems = mutableListOf<String>()
        val advisory = "security/advisories"
        if (!policy.asFile.isFile) {
            problems.add("SECURITY.md is missing")
        } else {
            val body = policy.asFile.readText()
            if (!body.contains("docs/user/security.md")) {
                problems.add("SECURITY.md must point at docs/user/security.md")
            }
            if (!body.contains(advisory)) {
                problems.add("SECURITY.md must name the GitHub security/advisories URL")
            }
        }
        if (!page.asFile.isFile) {
            problems.add("docs/user/security.md is missing")
        } else if (!page.asFile.readText().contains(advisory)) {
            problems.add("docs/user/security.md must name the GitHub security/advisories URL")
        }
        if (!index.asFile.readText().contains("](security.md)")) {
            problems.add("docs/user/README.md must link security.md")
        }
        if (problems.isNotEmpty()) {
            throw GradleException("Security docs are incomplete:\n  " + problems.joinToString("\n  "))
        }
    }
}

// The corpus every tree-wide text guard scans, pruned the way .jk/after-build.kts prunes it: build
// output and tool directories are skipped only when they sit OUTSIDE a source root, because `build`
// and `target` also name Java packages under src/ (cc.jumpkick.plugin.build is the plugin SPI) and a
// `**/build/**` exclude silently scanned one package less than the jk gate did — a gap G51's letter
// parity cannot see. Extension-blind: every scope a rule was given by extension was how it was missed
// the next time; binaries are skipped by their own extensions.
fun rootTextTree(): ConfigurableFileTree = fileTree(layout.projectDirectory) {
    val pruned = setOf("build", "target", ".git", ".gradle", ".firebase", "node_modules", ".board", ".kotlin")
    val treeRootFile = layout.projectDirectory.asFile
    // A nested checkout — a git worktree, which CONTRIBUTING recommends for parallel work — holds
    // another branch's source. Recognised by what it is (a directory carrying its own `.git`)
    // rather than by name, because a name list is exactly what let one through.
    val nested = HashMap<File, Boolean>()
    fun inNestedCheckout(f: File): Boolean {
        var d: File? = f.parentFile
        while (d != null && d != treeRootFile) {
            if (nested.getOrPut(d) { File(d, ".git").exists() }) return true
            d = d.parentFile
        }
        return false
    }
    exclude { element ->
        var underSrc = false
        var prune = false
        for (segment in element.relativePath.segments) {
            if (segment == "src") underSrc = true
            else if (!underSrc && segment in pruned) { prune = true; break }
        }
        prune || inNestedCheckout(element.file)
    }
    exclude("**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.webp", "**/*.ico",
            "**/*.jar", "**/*.zip", "**/*.xz", "**/*.gz", "**/*.class", "**/*.aot",
            "**/*.woff", "**/*.woff2", "**/*.ttf", "**/*.pdf", "**/*.so", "**/*.dylib", "**/*.exe")
}

// Guard G59: comments and docs state the current type, not the previous design.
tasks.register("checkNoHistoricalNarration") {
    group = "verification"
    description = "Fail when comments or docs narrate a previous design"
    val scanned = rootTextTree()
    inputs.files(scanned).withPropertyName("scanned")
    val treeRoot = layout.projectDirectory.asFile
    val exempt = mapOf(
        "AGENTS.md" to "names the banned narration phrases as the policy",
        "docs/contributors/comments.md" to "names the banned narration phrases as the policy",
    )
    val stamp = layout.buildDirectory.file("guards/no-historical-narration.ok")
    outputs.file(stamp)
    doLast {
        val missing = exempt.keys.filterNot { treeRoot.resolve(it).isFile }
        if (missing.isNotEmpty()) {
            throw GradleException("checkNoHistoricalNarration exempts files that no longer exist: "
                    + missing.sorted().joinToString(", "))
        }
        // Split so this file does not itself contain the banned phrases.
        val u = "used " + "to"
        val narration = Regex(
            "(?i)(?:this $u|it $u|they $u|javadoc $u"
                + "|$u (?:be|say|live|scan)"
                + "|former" + "ly|back" + "-compat|for future " + "agents|kept for " + "migration|do not " + "revert"
                + "|as they $u|as it $u)")
        var candidates = 0
        val hits = mutableListOf<String>()
        scanned.files.sorted().forEach { f ->
            candidates++
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (exempt.containsKey(rel)) return@forEach
            val text = try { f.readText() } catch (_: Exception) { return@forEach }
            val found = narration.findAll(text).map { it.value }.distinct().take(3).toList()
            if (found.isNotEmpty()) hits.add("  $rel: ${found.joinToString(", ")}")
        }
        if (candidates == 0) {
            throw GradleException("checkNoHistoricalNarration scanned zero files")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("historical narration in comments or docs:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  State the current invariant. History belongs in the commit body.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard G62: the ship layout is one shape, and three files have to agree on it.
//
// `install.sh <binary>` reads the engine from `<dir-of-binary>/lib/`. Two builds write that
// directory — the `dist` task below and `.jk/after-build-dist.kts` — and neither can see the other.
// A rename in one is silent in the other: the installer keeps reading `lib/`, one build keeps
// filling it, and the other produces a directory the installer walks straight past. The result is a
// local install that silently pairs a freshly built client with the RELEASED engine.
tasks.register("checkShipLayoutAgrees") {
    group = "verification"
    description = "Fail when the two builds and install.sh disagree on the ship layout"
    val files = listOf("install.sh", "build.gradle.kts", ".jk/after-build-dist.kts")
            .map { layout.projectDirectory.file(it) }
    inputs.files(files).withPropertyName("shipLayoutReaders")
    val stamp = layout.buildDirectory.file("guards/ship-layout-agrees.ok")
    outputs.file(stamp)
    doLast {
        val readers = listOf(
            Triple("install.sh", Regex("""SRC_LIB=.*pwd\)/([A-Za-z0-9_-]+)""""), "the installer's engine dir"),
            Triple("build.gradle.kts", Regex("""shadowJar"\)\) \{ into\("([A-Za-z0-9_-]+)"\)"""), "Gradle's dist task"),
            Triple(".jk/after-build-dist.kts", Regex("""dist\.resolve\("([A-Za-z0-9_-]+)"\)"""), "jk's dist script"))
        val found = LinkedHashMap<String, String>()
        val lost = mutableListOf<String>()
        readers.forEach { (rel, pattern, label) ->
            val f = layout.projectDirectory.file(rel).asFile
            val hit = if (f.isFile) pattern.find(f.readText()) else null
            if (hit == null) lost.add("  $rel — $label") else found[rel] = hit.groupValues[1]
        }
        if (lost.isNotEmpty()) {
            throw GradleException("checkShipLayoutAgrees can no longer read the ship layout out of"
                    + " these, so it is comparing nothing:\n" + lost.joinToString("\n")
                    + "\n  Re-anchor the scan on how the file spells it now, or retire the guard.")
        }
        if (found.values.toSet().size != 1) {
            throw GradleException("the ship layout is one directory and these disagree about its"
                    + " name:\n" + found.entries.joinToString("\n") { "  ${it.key}: ${it.value}" }
                    + "\n  install.sh reads <dir-of-binary>/<name>/jk-engine-<version>.jar.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard G61: a test that runs the install verb redirects the Maven local repo.
//
// `[m2] install` defaults on, so an install's primary destination is the Maven local repo — and a
// test JVM inherits the real home, so that is the developer's own ~/.m2. A workspace-install test
// with no --m2-dir published its fixture jar into ~/.m2/repository/cc/jumpkick on every run, and the
// same gap hid a defect: --m2-dir never rode the workspace wire, so the redirect the tests that DID
// pass it asked for was not applied by the engine either. Neither is visible from a green suite —
// the write lands outside the checkout, in a directory no assertion looks at.
//
// Every install invocation, not only the ones whose branch writes ~/.m2 today. A test cannot see
// which branch its install takes, and the file / coordinate modes are one refactor from the m2 one;
// a flag that is inert on those paths is cheaper than a rule with exceptions.
tasks.register("checkInstallTestsRedirectM2") {
    group = "verification"
    description = "Fail when a test runs the install verb without --m2-dir"
    val scanned = rootTextTree()
    inputs.files(scanned).withPropertyName("scanned")
    val treeRoot = layout.projectDirectory.asFile
    val stamp = layout.buildDirectory.file("guards/install-tests-redirect-m2.ok")
    outputs.file(stamp)
    doLast {
        // The two in-process entry points — JkRun.run delegates to Jk.execute — leading with the
        // install verb, in either of its two spellings. `jk tool install <dir>` delegates to the
        // same app pipeline as `jk install`, and that is the one that published a fixture into the
        // real ~/.m2 after the first-argument-only version of this scan called the tree clean.
        // `jk jdk install` and `jk bsp install` are different verbs that publish nothing, and
        // `List.of("install", …)` is data — none of the three match.
        val call = Regex(
            """\b(?:execute|run)\s*\(\s*(?:new\s+String\s*\[\s*]\s*\{\s*)?(?:"tool"\s*,\s*)?"install"\s*[,)}]""")
        var sites = 0
        val hits = mutableListOf<String>()
        scanned.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (!rel.endsWith(".java") || !rel.contains("/src/test/java/")) return@forEach
            val src = try { f.readText() } catch (_: Exception) { return@forEach }
            // Two views of the same offsets: the verb and the flag are string literals, so the
            // search needs them intact, while the walk to the end of the argument list must not be
            // unbalanced by a brace inside a fixture manifest. blankNonCode preserves length, so an
            // index found in one view means the same character in the other.
            val code = GuardScan.blankNonCode(src, blankStrings = false)
            val blank = GuardScan.blankNonCode(src)
            call.findAll(code).forEach { m ->
                val open = code.indexOf('(', m.range.first)
                var depth = 0
                var i = open
                while (i < blank.length) {
                    val c = blank[i]
                    if (c == '(' || c == '{') depth++
                    else if (c == ')' || c == '}') {
                        depth--
                        if (depth == 0) break
                    }
                    i++
                }
                sites++
                if (!code.substring(open, minOf(i + 1, code.length)).contains("--m2-dir")) {
                    hits.add("  $rel:${code.take(open).count { it == '\n' } + 1}")
                }
            }
        }
        // Measured when written: 25 install invocations across 5 test classes, all redirected.
        if (sites < 20) {
            throw GradleException("checkInstallTestsRedirectM2 found only $sites in-process `install`"
                    + " invocations in the test corpus — the entry-point pattern no longer matches how"
                    + " tests drive the CLI, and the guard is passing vacuously.")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("a test runs the install verb without redirecting the Maven local"
                    + " repo:\n" + hits.sorted().joinToString("\n")
                    + "\n  Pass --m2-dir into the test's own temp dir. `[m2] install` is on by default,"
                    + " so without it the install publishes into the developer's real ~/.m2 — outside"
                    + " the checkout, where no assertion and no clean task will ever look.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Guard G60: a JSON object is spliced in one place, Jsonl.append.
tasks.register("checkOneJsonSplicer") {
    group = "verification"
    description = "Fail when a JSON object is spliced by hand outside Jsonl.append"
    val scanned = rootTextTree()
    inputs.files(scanned).withPropertyName("scanned")
    val treeRoot = layout.projectDirectory.asFile
    val owner = "shared/host/src/main/java/cc/jumpkick/jsonl/Jsonl.java"
    val stamp = layout.buildDirectory.file("guards/one-json-splicer.ok")
    outputs.file(stamp)
    doLast {
        // The two hand shapes: chop an object's closing brace and append to it, or open a literal
        // brace and append another object from its second character. Both skip the validation and
        // the separator rule the owner carries, and both emit `{,"b":2}` for an empty object.
        val chop = Regex("(?s)\\.substring\\(0,\\s*\\w+\\.length\\(\\)\\s*-\\s*1\\)[^;]*\"\\}\"")
        val insert = Regex("(?s)\"\\{(?:\\\\\"|[^\"])*\"[^;]*\\.substring\\(1\\)")
        var candidates = 0
        var ownerSplices = false
        val hits = mutableListOf<String>()
        scanned.files.sorted().forEach { f ->
            val rel = f.relativeTo(treeRoot).invariantSeparatorsPath
            if (!rel.endsWith(".java") || !rel.contains("/src/main/java/")) return@forEach
            candidates++
            val text = f.readText()
            if (rel == owner) {
                ownerSplices = chop.containsMatchIn(text)
                return@forEach
            }
            val n = chop.findAll(text).count() + insert.findAll(text).count()
            if (n > 0) hits.add("  $rel: $n")
        }
        // Measured when written: 1,378 main sources, one splice (the owner's), none elsewhere.
        if (candidates < 500) {
            throw GradleException("checkOneJsonSplicer scanned only $candidates main sources; the tree walk broke")
        }
        if (!ownerSplices) {
            throw GradleException("$owner no longer splices with the shape this guard bans, so the guard"
                    + " has lost the owner it exempts. Move the exemption with the splicer or retire the"
                    + " guard deliberately.")
        }
        if (hits.isNotEmpty()) {
            throw GradleException("a JSON object spliced by hand:\n"
                    + hits.sorted().joinToString("\n")
                    + "\n  Call Jsonl.append(object, fields): it validates the object and owns the"
                    + " separator, which is what every hand chop got wrong on an empty object.")
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
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
    val catalog = layout.projectDirectory.file("buildSrc/src/main/kotlin/Guards.kt")
    val jkGate = layout.projectDirectory.file(".jk/after-build.kts")
    val exceptions = layout.projectDirectory.file("guard-parity.txt")
    inputs.file(catalog).withPropertyName("catalog")
    inputs.file(jkGate).withPropertyName("jkGate")
    inputs.file(exceptions).withPropertyName("exceptions")
    val stamp = layout.buildDirectory.file("guards/guard-parity.ok")
    outputs.file(stamp)
    doLast {
        val marker = Regex("""guard\("G(\d+)"""")
        val gradle = Guards.gradleLetters
        val jk = marker.findAll(jkGate.asFile.readText()).map { it.groupValues[1].toInt() }.toSortedSet()
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
                    + " — add the twin to Guards, or give it a guard-parity.txt entry saying why"
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
    description = "Fail when code-as-art.md's guard table differs from Guards"
    val catalog = layout.projectDirectory.file("buildSrc/src/main/kotlin/Guards.kt")
    val registry = layout.projectDirectory.file("docs/contributors/code-as-art.md")
    inputs.file(catalog).withPropertyName("catalog")
    inputs.file(registry).withPropertyName("registry")
    val stamp = layout.buildDirectory.file("guards/guard-registry.ok")
    outputs.file(stamp)
    doLast {
        val expected = Guards.tableMarkdown()
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
    val scanned = rootTextTree()
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
// The allowlist below names the files that read XDG / Known Folders to find OTHER programs' files,
// each with the reason; they are correct and exempt. Extension-blind like G50: install.cmd, TOML,
// CI workflows and templates are readers' surfaces too.
tasks.register("checkSingleHomeRoot") {
    group = "verification"
    description = "Fail the build on an old-layout path or a retired JK_*_DIR spelling"
    val scanned = rootTextTree()
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
            Regex("""(?i)\bdata root\b"""), Regex("""(?i)\bdata/lib\b"""), Regex("""(?i)\bdata/store\b"""),
            Regex("""\$\{?JK_HOME\}?[/\\]data\b"""), Regex("""--data(?:-dir)?\b"""),
            Regex("""(?:homeDir\(\)|JkDirs\.home\(\))\.resolve\("data"\)"""),
            Regex("""JK_DATA_DIR|JK_BUILDS_DIR|JK_TMP_DIR|JK_BIN_DIR|JK_INSTALL_DIR"""),
            Regex("""JK_CONFIG_DIR|JK_CONFIG_FILE"""),
        )
        val fixtures = listOf(
            "the data root",
            "\$JK_HOME/data/lib",
            "test-jk-home/data/store/git/",
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
