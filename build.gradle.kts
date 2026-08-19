// SPDX-License-Identifier: Apache-2.0

// Root project. Conventions live in buildSrc/ and are applied per module.
// Library/plugin pins live in gradle/libs.versions.toml.

tasks.wrapper {
    gradleVersion = "9.5.1"
    distributionType = Wrapper.DistributionType.BIN
}

// Aggregate integration suite across all subprojects that register the task.
tasks.register("integrationTest") {
    group = "verification"
    description = "Run @Tag(integration|slow) tests in every module (not part of check)"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "integrationTest" } })
}

tasks.register("checkAll") {
    group = "verification"
    description = "Unit test + integrationTest for the whole repo"
    dependsOn(subprojects.map { it.tasks.matching { t -> t.name == "test" } }, "integrationTest")
}

// The shippable native-dist layout (docs/architecture.md "Ship layout"): the size-tuned native jk
// client next to the engine's fat jar. The engine is a JVM app, never a native image — the
// installed client spawns it on the jk-managed JDK as
// `java -cp ~/.local/share/jk/lib/jk-engine.jar EngineMain`. dist/lib carries the jar the
// installer materializes into $JK_HOME/lib (via `jk self materialize`).
val dist by tasks.registering(Sync::class) {
    description = "Assembles build/dist: the native jk client + lib/jk-engine-<version>.jar"
    group = "distribution"
    dependsOn(":cli:nativeCompile")
    from(project(":cli").layout.buildDirectory.dir("native/nativeCompile")) { include("jk", "jk.exe") }
    from(project(":engine").tasks.named("shadowJar")) { into("lib") }
    into(layout.buildDirectory.dir("dist"))
}

/**
 * Local dogfood refresh: all worker `installLocal` tasks, then `:engine:installLocal` (JK-1194).
 * Prefer: `./gradlew :cli:installDist installLocal` so the thin client exists for materialize.
 */
tasks.register("installLocal") {
    group = "distribution"
    description = "Side-load workers + materialize engine jar and bounce daemon for local dogfood"
    // Engine last: it needs a client on PATH or installDist for self materialize.
    dependsOn(
        subprojects
            .filter { it.path != ":engine" }
            .map { it.tasks.matching { t -> t.name == "installLocal" } })
    finalizedBy(":engine:installLocal")
}
