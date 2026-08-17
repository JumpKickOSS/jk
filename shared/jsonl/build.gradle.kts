// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.java-conventions")
}

description = "jk JSONL / MiniJson codec — the one string codec shared by the CLI wire (S1) " +
        "and the plugin-worker protocol (S7). Zero deps; JDK 17 floor so workers on a project " +
        "JDK can load the same classes."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}
