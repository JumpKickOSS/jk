// SPDX-License-Identifier: Apache-2.0

// Root project of the bootstrap build: `./gradlew dist installLocal` produces the first jk on a host
// with no hosted client. It compiles, tests and packages; it judges nothing — the house rules, their
// registry and the gate are `jk guard` and `jk build` (docs/contributors/self-host.md, "Bootstrap").
// Conventions live in buildSrc/ and are applied per module; library/plugin pins live in
// gradle/libs.versions.toml, which the gradle-bootstrap-parity guard holds to jk-lock.toml.

// Report which test tiers actually executed, and read their counts from TEST-*.xml.
// A gate that prints BUILD SUCCESSFUL for a cache read is not evidence about the current tree.
apply<GateReportPlugin>()

tasks.wrapper {
    gradleVersion = "9.7.0"
    distributionType = Wrapper.DistributionType.BIN
}

// Aggregate the slow tiers across all subprojects that register them. One tier per tag; the table
// is buildSrc/src/main/kotlin/TestTiers.kt. Which tier gates a merge is `jk test --profile …`'s
// business (docs/contributors/test-suite-tiers.md); these aggregates only run a tier on demand.
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

// Framework/language e2e, 426s of the gating tier for 28 tests. What they assert moves with a
// plugin or a toolchain, not with the change under review.
tasks.register("slowTest") {
    group = "verification"
    description = "Run @Tag(slow) framework/language e2e suites in every module (nightly, on demand)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "slowTest" } })
}

// These tests talk to a real remote, and
// Sonatype enforces a per-IP quota on Maven Central that this repo has already been bitten by
// — a merge gate that needs the network fails for reasons the change did not cause.
// Runs nightly in ci-nightly.yml, where a 429 costs a re-run rather than a blocked PR.
tasks.register("networkTest") {
    group = "verification"
    description = "Run @Tag(network) tests in every module (nightly only)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "networkTest" } })
}

// A microbench prints medians and asserts nothing about deltas, so gating on it would gate on
// CI noise. Nightly runs `benchTest`.
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

// buildSrc's own tests (the layout mirror, the tree-version reader, the tier keys) are not run by
// the root build — Gradle only compiles buildSrc — so they are run here as a nested invocation.
tasks.register<Exec>("testBuildSrc") {
    group = "verification"
    description = "Run buildSrc's own tests"
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
