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

    /**
     * The Gradle side keeps the one task-graph letter and the two registry tasks; every other letter is self-hosted.
     */
    @Test
    fun gradle_registers_only_the_task_graph_letter_and_the_registry_tasks() {
        val registered = Guards.all.filter { it.registers }.map { it.task }
        assertThat(registered)
            .containsExactlyInAnyOrder(
                "checkNoDisabledCompile",
                "checkGuardParity",
                "checkGuardRegistry",
                "checkGateCoverage",
            )
        val g64 = Guards.named("checkNoDisabledCompile")
        assertThat(g64.letter).isEqualTo(64)
        assertThat(g64.home).isEqualTo(GuardHome.MODULE)
        assertThat(g64.attach).isEqualTo(setOf(GuardAttach.CHECK))
    }

    /** A letter with no Gradle task names its jk side, or says why it has none. */
    @Test
    fun every_self_hosted_letter_has_a_jk_side_or_a_reason() {
        val silent =
            Guards.tableRows
                .filter { it.home == GuardHome.SELF_HOSTED }
                .filter { it.ruleId == null && it.engineCode == null && it.guardTestId == null && it.jkSide == null }
                .map { it.id }
        assertThat(silent).isEmpty()
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
    fun only_the_task_graph_letter_and_the_registry_tasks_are_gradle_letters() {
        assertThat(Guards.gradleLetters).containsExactlyInAnyOrder(51, 64, 79)
    }
}
