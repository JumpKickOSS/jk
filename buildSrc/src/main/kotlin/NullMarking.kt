// SPDX-License-Identifier: Apache-2.0

/**
 * Where JSpecify nullness is enforced, and the narrow, temporary places it is not.
 *
 * The convention plugin (`jk.nullmarked-conventions`), guard G53, and the self-hosted gate all read this object, so
 * "which code is null-checked" has one answer rather than three.
 */
object NullMarking {

    /** Production source roots whose every package must carry a package-level `@NullMarked`. */
    val enforcedRoots: List<String> =
        listOf(
            "shared/jk-api/src/main/java",
            "shared/wire/src/main/java",
            "shared/plugin-sdk/src/main/java",
            "shared/core/src/main/java",
            "clients/cli/src/main/java",
            "shared/guard-api/src/main/java",
            "server/guard/src/main/java",
            "plugins/auditor/src/main/java",
            "plugins/grails/src/main/java",
            "plugins/groovy-compiler/src/main/java",
            "plugins/micronaut/src/main/java",
            "plugins/minified/src/main/java",
            "plugins/protobuf/src/main/java",
            "plugins/spring-boot/src/main/java",
            "plugins/test-runner/src/main/java",
            "plugins/android/src/main/java",
            "plugins/formatter/src/main/java",
            "plugins/image-builder/src/main/java",
            "plugins/java-compiler/src/main/java",
            "plugins/kotlin-compiler/src/main/java",
            "plugins/publisher/src/main/java",
            "plugins/quarkus/src/main/java",
        )

    /**
     * Production packages inside an enforced root that are deliberately left unmarked, each with the reason. A package
     * listed here must exist and must still be unmarked; a stale entry fails guard G53.
     */
    val excludedPackages: Map<String, String> = emptyMap()

    /**
     * Compile tasks (`<project>:<task>`) whose sources are not null-marked yet, with the reason. NullAway and
     * `RequireExplicitNullMarking` are switched off for exactly these; everything else the convention plugin touches is
     * enforced at error severity.
     */
    val unmarkedCompileTasks: Map<String, String> =
        mapOf(
            "engine:compileTestJava" to
                "TODO: 101 findings, and roughly half of them are one idiom: the MCP and HTTP suites read a " +
                    "decoded JSON response as Map<String, Object> and assert on its contents, so every " +
                    "result.get(\"x\") is nullable by Map's contract and non-null by the protocol the test " +
                    "exists to pin. Annotating those would put a requireNonNull between the reader and the " +
                    "assertion in fifty places. The rest is the same shape as core's: null fed in on purpose " +
                    "(an absent [http] table, a job with no module selector) to assert the engine handles it. " +
                    "Production is enforced."
        )

    /**
     * Modules the convention plugin does not apply to yet, and what turning it on costs. Measured by marking every
     * package and compiling; the counts are production code only. No guard can detect this from sources — a module that
     * never applies the plugin looks the same as one with nothing to fix — so this is where the remaining work is
     * written down rather than inferred.
     */
    val unenforcedModules: Map<String, String> = emptyMap()
}
