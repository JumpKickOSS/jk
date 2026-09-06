// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "jk-guards-junit: the guard-test library a project's src/guard suite compiles against — " +
        "@Guard, @GuardSuite, @Allow, @Fixture and the read-only Facts / Model / Text / Output / " +
        "Violations views the engine injects. Depends on :host and the JUnit Jupiter API only; JDK 17 " +
        "because it runs inside the project's forked test JVM."

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}
tasks.compileJava {
    options.release.set(17)
}

dependencies {
    api(project(":host"))
}
