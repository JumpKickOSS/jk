// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
    id("jk.nullmarked-conventions")
}

description = "jk-android: the built-in Android build plugin's code layer — the aapt2 " +
        "resource step (R generation before compile), the d8 dex step, and the APK " +
        "packager, run in a forked worker JVM over the build-plugin harness. The SPI " +
        "stress test (build-plugins plan P6): everything here rides the same public " +
        "surface a third-party plugin gets — plugin-api, client-io's Http for its one download, " +
        "and the plugin's own bundled libraries (apksig for v1+v2 signing)."

dependencies {
    implementation(project(":plugin-sdk"))
    // Http — the one client jk routes through the configured proxy; the SDK license feed is a
    // download like any other. The `plugin-sdk-boundary` rule carries this edge by name.
    implementation(project(":client-io"))
    // The plugin's own signing library — bundled into the worker jar exactly the way
    // a third-party plugin ships its private deps. Never touches the engine classpath.
    // Versions come from the catalog, which checkCatalogLockParity holds to jk-lock.toml's
    // answer — the self-host build must compile this module against the same bytes.
    implementation(libs.apksig)
    // ASM, for the Hilt superclass transform (android-hilt-transform) — same bundling story.
    implementation(libs.asm)
    // `FakeBuildIo` — the shared engine-side fake for a packager/step body. Replaces
    // this module's own FakePackageIo + FakeTaskExec, which were the same fake written twice
    // because neither SPI interface extends the other.
    testImplementation(testFixtures(project(":plugin-sdk")))
}

