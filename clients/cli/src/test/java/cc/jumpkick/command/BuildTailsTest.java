// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Wedge wording for built vs checked vs fully up to date, and the shared module completion line. */
class BuildTailsTest {

    @Test
    void empty_plan_is_all_up_to_date() {
        String t = BuildTails.successTail(List.of(), 0, System.nanoTime());
        assertThat(t).contains("all modules up to date");
        assertThat(t).doesNotContain("for ");
        assertThat(t).doesNotContain("checked");
    }

    @Test
    void all_checked_modules() {
        var modules = List.of(
                new ModuleOutcome("a:b", Path.of("/a"), true, 0, 10, false),
                new ModuleOutcome("a:c", Path.of("/c"), true, 0, 12, false));
        String t = BuildTails.successTail(modules, 2, System.nanoTime());
        assertThat(t).contains("checked");
        assertThat(t).contains("all up to date");
        assertThat(t).doesNotContain("for ");
        assertThat(t).doesNotContain("built");
    }

    @Test
    void all_built_modules() {
        var modules = List.of(new ModuleOutcome("a:b", Path.of("/a"), true, 0, 100, true));
        String t = BuildTails.successTail(modules, 1, System.nanoTime());
        assertThat(t).contains("for ");
        assertThat(t).contains("1");
        assertThat(t).doesNotContain("checked");
    }

    @Test
    void mix_built_and_checked() {
        var modules = List.of(
                new ModuleOutcome("a:b", Path.of("/a"), true, 0, 100, true),
                new ModuleOutcome("a:c", Path.of("/c"), true, 0, 10, false));
        String t = TestAnsi.strip(BuildTails.successTail(modules, 2, System.nanoTime()));
        assertThat(t).contains("built 1 module");
        assertThat(t).contains("checked 1 module");
        assertThat(t).doesNotContain("all up to date");
    }

    @Test
    void completion_line_zero_pads_the_numerator_to_the_denominator_width() {
        String t = TestAnsi.strip(BuildTails.completionLine(true, 1, 16, "g:a", 16));
        assertThat(t).contains("[01 of 16]");
        assertThat(t).contains("g:a");
    }

    @Test
    void failed_completion_line_says_failed() {
        String t = TestAnsi.strip(BuildTails.completionLine(false, 2, 3, "g:a", 5));
        assertThat(t).contains("[2 of 3]");
        assertThat(t).contains("— failed");
    }
}
