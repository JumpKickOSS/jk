// SPDX-License-Identifier: Apache-2.0

plugins {
    id("jk.nullmarked-conventions")
}

description = "jk IDE project files: the IntelliJ IDEA and VS Code generators over the engine's " +
        "IDE model, the BSP connection file, and the source-root view they share. Pure model-to-files; " +
        "no terminal, no engine. Linked by the CLI (jk ide) and by the engine (the jk_ide MCP tool)."

dependencies {
    // Scope (jk-api); MinimalXml, AtomicWrites, ModuleLayout/TestSuites (core); Jsonl, Os, PathUtil (host).
    api(project(":jk-api"))
    api(project(":core"))
    api(project(":host"))
    // IdeWireModel is the generators' input and the sibling-scope vocabulary they read.
    api(project(":wire"))
    // IntellijJdkTable: the read side of the jdk.table.xml the SDK registrar writes.
    api(project(":toolchain-jdk"))
    testImplementation(testFixtures(project(":host")))
}
