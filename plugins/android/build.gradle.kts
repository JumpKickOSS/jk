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
    implementation("com.android.tools.build:apksig:8.7.3")
    // ASM, for the Hilt superclass transform (android-hilt-transform) — same bundling story.
    implementation("org.ow2.asm:asm:9.8")
}
