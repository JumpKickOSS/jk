// SPDX-License-Identifier: Apache-2.0

/**
 * Test tasks that exec a tool this build does not produce, and the tool each one runs.
 *
 * Keyed `project-path:task-name` because the answer is per tier, not per module. Adding `protoc` or `bundletool` is a
 * line here, not a new pattern in a module script.
 */
object ExternalTestRuntimes {

    val table: Map<String, List<Pair<String, String?>>> =
        mapOf(
            ":web:test" to listOf("node" to null),
            ":engine:test" to listOf("git" to "JK_GIT"),
            ":engine:integrationTest" to listOf("git" to "JK_GIT"),
        )
}
