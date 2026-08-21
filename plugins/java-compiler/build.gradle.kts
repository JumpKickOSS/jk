// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-java-compiler: child-JVM worker that runs Zinc's Java-only incremental " +
        "compiler (ToolProvider javac) and streams diagnostics as JSONL. Zinc and its Scala " +
        "runtime ride the worker POM classpath, not the thin jar."

// plugin-api is tiny and dependency-free; vendor just its codec classes into the
// thin worker jar (the JDK compiler APIs the worker uses are part of the JDK).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    compileOnly(project(":plugin-sdk"))
    bundledCodec(project(":plugin-sdk"))
    bundledCodec(project(":jsonl"))
    implementation(libs.zinc)
    testImplementation(project(":plugin-sdk"))
}

tasks.jar {
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
}
