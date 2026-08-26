// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-android: the built-in Android build plugin's code layer — the aapt2 " +
        "resource step (R generation before compile), the d8 dex step, and the APK " +
        "packager, run in a forked worker JVM over the build-plugin harness. The SPI " +
        "stress test (build-plugins plan P6): everything here rides the same public " +
        "surface a third-party plugin gets — plugin-api plus the plugin's own bundled " +
        "libraries (apksig for v1+v2 signing)."

dependencies {
    implementation(project(":plugin-sdk"))
    // The plugin's own signing library — bundled into the worker jar exactly the way
    // a third-party plugin ships its private deps. Never touches the engine classpath.
    // Versions come from the catalog, which checkCatalogLockParity holds to jk-lock.toml's
    // answer — the self-host build must compile this module against the same bytes.
    implementation(libs.apksig)
    // ASM, for the Hilt superclass transform (android-hilt-transform) — same bundling story.
    implementation(libs.asm)
    // `FakeBuildIo` — the shared engine-side fake for a packager/step body (JK-2443). Replaces
    // this module's own FakePackageIo + FakeTaskExec, which were the same fake written twice
    // because neither SPI interface extends the other.
    testImplementation(testFixtures(project(":plugin-sdk")))
}

// ---------------------------------------------------------------------------
// Guard (letter allocated at landing): the Gradle version catalog and jk-lock.toml agree on
// every coordinate they share.
//
// Defect it prevents: the Gradle build and the self-host build compiling the same sources
// against different bytes. This module bundled apksig from an 8.7.3 version literal in this
// file while the self-host lock resolved 9.3.2 — a full major apart — so the two builds shipped
// worker jars signing with different apksig, and a 9.x-only API in Signing would compile under
// one build and break the other. The invariant is catalog↔lock agreement per module, and a text
// scan answers it. (Version literals outside the catalog — java-compiler's scala3 test pins —
// are invisible here until they are cataloged; this guard closes the catalog, not the tree.)
//
// Known drift rides the checked-in list below, a ratchet: each entry was already mismatched
// when the guard landed and is deleted when its versions reconcile — a NEW mismatch fails
// naming coordinate and both versions, and a listed entry that no longer mismatches fails until
// it is removed. Self-fails when either file parses to an implausibly small table.
// Measured 2026-08-26: 34 catalog libraries, 202 lock artifacts, 28 shared modules,
// 12 mismatched (the list below). The OpenRewrite pair left when the lock stopped resolving a
// tree the manifests no longer declare (8a0abe45) — no longer shared, so no longer drift.
//
// jkParityCatalog exists for the guard's own revert check: it points the scan at a scratch copy
// of the catalog so a deliberately skewed version can be seen to fail without mutating the real
// file under concurrent builds. It is a self-test seam, not a bypass — CI never sets it.
val knownCatalogLockDrift = setOf(
        "com.diffplug.spotless:spotless-lib",
        "com.google.cloud.tools:jib-core",
        "dev.sigstore:sigstore-java",
        "org.apache.groovy:groovy",
        "org.bouncycastle:bcpg-jdk18on",
        "org.eclipse.jgit:org.eclipse.jgit",
        "org.graalvm.sdk:nativeimage",
        "org.junit.jupiter:junit-jupiter",
        "org.junit.platform:junit-platform-engine",
        "org.junit.platform:junit-platform-launcher",
        "org.slf4j:slf4j-api",
        "org.slf4j:slf4j-nop",
)

// Guard G33 (JK-2557).
val checkCatalogLockParity by tasks.registering {
    group = "verification"
    description = "Fail the build when gradle/libs.versions.toml and jk-lock.toml disagree on a shared module"
    val catalogFile = rootProject.layout.projectDirectory.file("gradle/libs.versions.toml")
    val lockFile = rootProject.layout.projectDirectory.file("jk-lock.toml")
    val catalogOverride = providers.gradleProperty("jkParityCatalog")
    inputs.file(catalogFile).withPropertyName("versionCatalog")
    inputs.file(lockFile).withPropertyName("selfHostLock")
    inputs.property("jkParityCatalog", catalogOverride.orElse(""))
    val stamp = layout.buildDirectory.file("guards/catalog-lock-parity.ok")
    outputs.file(stamp)
    doLast {
        val catalogText = (catalogOverride.orNull?.let { File(it) } ?: catalogFile.asFile).readText()
        val versionKeys = Regex("""(?m)^([A-Za-z0-9-]+)\s*=\s*"([^"]+)"""")
                .findAll(catalogText.substringBefore("[libraries]"))
                .associate { it.groupValues[1] to it.groupValues[2] }
        val catalog = Regex(
                """(?m)^[A-Za-z0-9-]+\s*=\s*\{\s*module\s*=\s*"([^"]+)"\s*,\s*(?:version\.ref\s*=\s*"([A-Za-z0-9-]+)"|version\s*=\s*"([^"]+)")""")
                .findAll(catalogText)
                .mapNotNull { m ->
                    val version = m.groupValues[2].takeIf { it.isNotEmpty() }?.let(versionKeys::get)
                            ?: m.groupValues[3].takeIf { it.isNotEmpty() }
                    version?.let { m.groupValues[1] to it }
                }
                .toMap()
        val lock = mutableMapOf<String, MutableSet<String>>()
        Regex("""name\s*=\s*"([^"]+)"\s*\n\s*version\s*=\s*"([^"]+)"""").findAll(lockFile.asFile.readText())
                .forEach { m ->
                    val parts = m.groupValues[1].split(":")
                    if (parts.size >= 2) {
                        lock.getOrPut("${parts[0]}:${parts[1]}") { mutableSetOf() }.add(m.groupValues[2])
                    }
                }
        if (catalog.size < 25 || lock.size < 180) {
            throw GradleException("The catalog↔lock parity guard parsed ${catalog.size} catalog"
                    + " libraries and ${lock.size} lock artifacts; it was measured against 34 and"
                    + " 202. A regex has stopped seeing its file — fix it before trusting a green"
                    + " run.")
        }
        val mismatches = catalog.filterKeys { it in lock }.filter { (module, v) -> v !in lock.getValue(module) }
        val unexpected = mismatches.keys - knownCatalogLockDrift
        val healed = knownCatalogLockDrift - mismatches.keys
        if (unexpected.isNotEmpty() || healed.isNotEmpty()) {
            val lines = unexpected.sorted().map {
                "  NEW MISMATCH $it: catalog=${catalog[it]} lock=${lock.getValue(it).sorted()} — " +
                        "the two builds compile against different bytes; align the catalog " +
                        "with the lock (the lock is the pin), or re-lock"
            } + healed.sorted().map {
                "  RECONCILED $it: no longer mismatched — delete it from knownCatalogLockDrift " +
                        "so the ratchet tightens"
            }
            throw GradleException(
                    "gradle/libs.versions.toml and jk-lock.toml disagree:\n" + lines.joinToString("\n"))
        }
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}
tasks.named("check") { dependsOn(checkCatalogLockParity) }
tasks.named("jar") { dependsOn(checkCatalogLockParity) }
