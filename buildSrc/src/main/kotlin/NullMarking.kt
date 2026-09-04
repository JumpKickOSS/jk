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
        )

    /**
     * Production packages deliberately left unmarked, each with the reason and the ticket that closes it. A package
     * listed here must exist and must still be unmarked; a stale entry fails the guard.
     */
    val excludedPackages: Map<String, String> = emptyMap()

    /**
     * Compile tasks (`<project>:<task>`) whose sources are not null-marked yet, with the reason. NullAway and
     * `RequireExplicitNullMarking` are switched off for exactly these; everything else the convention plugin touches is
     * enforced at error severity.
     */
    val unmarkedCompileTasks: Map<String, String> =
        mapOf(
            "core:compileTestJava" to
                "TODO: core's unit suite feeds null through parsers on purpose to assert they reject it; " +
                    "its production and test-fixture sources are enforced."
        )
}
