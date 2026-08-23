// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "Client TTY session, VT style, and keys — FFM POSIX/Windows, no JLine"

tasks.named<Test>("test") {
    // JDK 25: FFM downcalls warn-then-fail without this.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
tasks.matching { it.name == "integrationTest" }.configureEach {
    (this as Test).jvmArgs("--enable-native-access=ALL-UNNAMED")
}
