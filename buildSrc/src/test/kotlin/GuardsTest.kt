// SPDX-License-Identifier: Apache-2.0

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class GuardsTest {

    /** Every letter from G0 to the highest published one exists exactly once — no gaps, no reuse. */
    @Test
    fun published_letters_are_total_and_unique() {
        val letters = Guards.tableRows.mapNotNull { it.letter }
        val highest = letters.maxOf { it }
        assertThat(letters).containsExactlyElementsOf((0..highest).toList())
        assertThat(highest).isGreaterThanOrEqualTo(60)
    }

    @Test
    fun gradle_task_names_are_unique() {
        val names = Guards.all.filter { it.registers }.map { it.task }
        assertThat(names).doesNotHaveDuplicates()
        assertThat(names).noneMatch { it.isEmpty() }
    }

    @Test
    fun module_guards_that_scan_tests_do_not_attach_to_jar() {
        val checkOnly =
            Guards.all.filter { it.home == GuardHome.MODULE && it.attach == setOf(GuardAttach.CHECK) }.map { it.task }
        assertThat(checkOnly)
            .containsExactlyInAnyOrder(
                "checkNoOrphanTestTags",
                "checkTestPathsFromCheckoutRoot",
                "checkManifestDepParity",
                "checkNoDisabledCompile",
            )
    }

    @Test
    fun maven_publish_guard_is_conditional() {
        val g19 = Guards.named("checkPublishedPomCoordinates")
        assertThat(g19.letter).isEqualTo(19)
        assertThat(g19.mavenPublishOnly).isTrue()
        assertThat(g19.attach).contains(GuardAttach.CHECK, GuardAttach.JAR)
    }

    @Test
    fun table_markdown_has_markers_and_every_letter() {
        val table = Guards.tableMarkdown()
        assertThat(table).startsWith("<!-- guards:start -->")
        assertThat(table).endsWith("<!-- guards:end -->")
        assertThat(table).contains("| G0 |")
        assertThat(table).contains("| G41 | — |")
        assertThat(table).contains("| G49 |")
        assertThat(table).contains("| G54 |")
    }

    @Test
    fun jk_only_letters_are_not_gradle_letters() {
        assertThat(Guards.gradleLetters).doesNotContain(0, 4, 41, 44)
        assertThat(Guards.gradleLetters).contains(1, 19, 23, 49, 54)
    }

    @Test
    fun owned_module_paths_are_declared() {
        val owned =
            Guards.all.filter { it.home == GuardHome.MODULE_OWNED && it.inFastGate }.map { it.task to it.ownerPath }
        assertThat(owned)
            .contains(
                "checkForecastKeyParity" to ":engine",
                "checkIdeClientWiring" to ":cli",
                "checkSingleAotMarkerSpelling" to ":host",
                "checkNoRetiredWireSpelling" to ":wire",
                "checkWorkerOfflineFromSpec" to ":plugin-sdk",
                "checkCatalogLockParity" to ":android",
                "checkCliRuntimeClasspath" to ":cli",
                "checkCliNoParseTypes" to ":cli",
            )
    }
}
