// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.plugin-conventions")
}

description = "jk-formatter: child-JVM worker that formats Java/Kotlin (and, later, " +
        "prettier-backed) sources via the Spotless engine. Only spotless-lib is bundled — " +
        "the underlying formatter impls (palantir-java-format, ktfmt, …) are resolved at " +
        "runtime by jk and handed in, keeping them out of the main jk binary."

dependencies {
    implementation(project(":plugin-sdk"))
    // The Spotless formatting engine. Zero runtime deps of its own; the actual
    // formatter implementations are loaded at runtime via a Provisioner from
    // jar paths jk resolves and passes in the spec.
    implementation(libs.spotless.lib)
    // OpenRewrite: on the worker *runtime classpath* (thin jar + .classpath sidecar), not fat-merged
    // (JK-1347). rewrite-java-21 is the parser impl for JDK 21+; see JavaParser.fromJavaVersion().
    implementation(libs.openrewrite.java)
    implementation(libs.openrewrite.java21)
    // spotless-lib needs slf4j-api at runtime (it declares it compileOnly); a
    // no-op binding keeps the worker quiet (we report results via JSONL).
    implementation(libs.slf4j.api)
    runtimeOnly(libs.slf4j.nop)
}
