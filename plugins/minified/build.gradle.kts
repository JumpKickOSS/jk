// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-minified: the built-in minified build plugin's code layer — R8 in --classfile " +
        "mode over the app + runtime closure, packaged as a slim self-contained jar in a " +
        "forked worker JVM over the build-plugin harness. The generic-JVM counterpart of " +
        "the android plugin's release shrinking, sharing the same r8 artifact."

dependencies {
    implementation(project(":plugin-sdk"))
    implementation(project(":dynamic-surface"))
}
