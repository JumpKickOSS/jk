// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "Client TTY session, VT style, and keys — FFM POSIX/Windows, no JLine"

dependencies {
    // Os only — the binders branch on the host, and :host is the leaf that owns that
    // predicate. Nothing heavier belongs on a module the native image links wholesale.
    implementation(project(":host"))
}

tasks.named<Test>("test") {
    // JDK 25: FFM downcalls warn-then-fail without this.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.matching { it.name == "integrationTest" }.configureEach {
    (this as Test).jvmArgs("--enable-native-access=ALL-UNNAMED")
}
