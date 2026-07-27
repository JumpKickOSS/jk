// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-groovy-compiler: child-JVM worker that drives the Groovy 5 compiler " +
        "(CompilationUnit / JavaAwareCompilationUnit for joint mode) in-process, isolated from " +
        "jk's own classpath. Reads a line-oriented spec, streams JSONL diagnostics back to jk."

// plugin-api is tiny and dependency-free; vendor just its codec classes into the
// thin worker jar (not the whole runtime closure — the Groovy jar stays external).
val bundledCodec by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // Compile against the Groovy compiler API only. The Groovy runtime closure is
    // resolved at runtime — version-matched to the project's Groovy — and placed on
    // the worker's classpath by jk's launcher. It must NOT be bundled here, so the
    // worker jar stays tiny and Groovy's transitive deps never leak into jk's own
    // classpath.
    compileOnly(libs.groovy)
    // Tests drive the worker against the real compiler.
    testImplementation(libs.groovy)
    // The shared JSONL protocol codec. compileOnly so it doesn't drag onto the
    // worker's resolved runtime closure; its classes are vendored into the jar
    // via `bundledCodec` below so the codec is present in the worker JVM.
    compileOnly(project(":plugin-sdk"))
    bundledCodec(project(":plugin-sdk"))
    testImplementation(project(":plugin-sdk"))
}

tasks.jar {
    dependsOn(bundledCodec)
    from(bundledCodec.map { zipTree(it) })
}
