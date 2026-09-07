// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-protobuf: the built-in protobuf build plugin's code layer — runs the " +
        "provisioned protoc binary over the module's proto sources and contributes the " +
        "generated Java to the compiler's source set, in a forked worker JVM over the " +
        "build-plugin harness. Ecosystem-neutral: the second real consumer of the " +
        "before-compile codegen SPI after the android plugin."

dependencies {
    implementation(project(":plugin-sdk"))
    // `FakeBuildIo` — the shared engine-side fake for a step body.
    testImplementation(testFixtures(project(":plugin-sdk")))
}
