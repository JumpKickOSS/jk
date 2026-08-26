// SPDX-License-Identifier: Apache-2.0

// Root project. Conventions live in buildSrc/ and are applied per module.
// Library/plugin pins live in gradle/libs.versions.toml.

// JK-1018: report which test tiers actually executed, and read their counts from TEST-*.xml.
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
    description = "Run @Tag(integration|slow) tests in every module (not part of check)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "integrationTest" } })
}

// Deliberately NOT reachable from checkAll (JK-2447). These tests talk to a real remote, and
// Sonatype enforces a per-IP quota on Maven Central that this repo has already been bitten by
// (JK-1277) — a merge gate that needs the network fails for reasons the change did not cause.
// Runs nightly in ci-nightly.yml, where a 429 costs a re-run rather than a blocked PR.
tasks.register("networkTest") {
    group = "verification"
    description = "Run @Tag(network) tests in every module (nightly only, never in checkAll)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "networkTest" } })
}

// Also not reachable from checkAll, for the opposite reason: a microbench prints medians and
// asserts nothing about deltas, so gating on it would gate on CI noise. It still has to run
// somewhere, which before JK-2447 it did not — @Tag("bench") was excluded from both tiers.
tasks.register("benchTest") {
    group = "verification"
    description = "Run @Tag(bench) microbenchmarks in every module (on demand, gates nothing)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "benchTest" } })
}

// JK-2498: `check`, not just `test`. Measured 2026-08-24: every build-failing guard in
// jk.java-conventions is wired to both `check` and `jar`, and depending on `test` alone reached them
// only *transitively* — a module's test classpath pulls its dependencies' jars, which pull their
// guards. Nothing depends on `:cli`, `:formatter` or `:micronaut`, so nothing built their jars and
// their guards never ran under the documented pre-merge bar. `:cli:checkNoFqcn` was red through a
// green `checkAll` when this was written. Depend on `check` and the coupling stops being incidental.
tasks.register("checkAll") {
    group = "verification"
    description = "check (unit test + every guard) + integrationTest for the whole repo"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "check" } }, "integrationTest")
    dependsOn("checkGateCoverage")
}

// The gate's own coverage is a fact about the build, so it is checked rather than assumed: a module
// that registers guards but has no `check` task is unreachable and would sit out the gate silently.
tasks.register("checkGateCoverage") {
    group = "verification"
    description = "Fail when a module's guards are not reachable from checkAll"
    doLast {
        val guarded = subprojects.filter { p ->
            p.tasks.names.any { it == "checkFileSizeCaps" || it.startsWith("checkNo")
                    || it.startsWith("checkSingle") || it.startsWith("checkOne") }
        }
        val missing = guarded.filterNot { it.tasks.names.contains("check") }.map { it.path }
        if (missing.isNotEmpty()) {
            throw GradleException(
                    "These modules register guards that checkAll cannot reach (JK-2498): " + missing)
        }
        logger.lifecycle("checkAll reaches the guards of " + guarded.size + " guarded modules")
    }
}

// The shippable native-dist layout (docs/architecture.md "Ship layout"): the size-tuned native jk
// client next to the engine's fat jar. The engine is a JVM app, never a native image — the
// installed client spawns it on the jk-managed JDK as
// `java -cp ~/.local/share/jk/lib/jk-engine.jar EngineMain`. dist/lib carries the jar the
// installer materializes into <data>/lib (via `jk self materialize`).
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
 * Engine materialize uses the native client from `./gradlew dist` (`build/dist/jk[.exe]`).
 */
tasks.register("installLocal") {
    group = "distribution"
    description = "Side-load workers + materialize engine jar and bounce daemon for local dogfood"
    // Engine last: it needs the native client from `dist` for self materialize.
    dependsOn(
        subprojects
            .filter { it.path != ":engine" }
            .map { it.tasks.matching { t -> t.name == "installLocal" } })
    finalizedBy(":engine:installLocal")
}
