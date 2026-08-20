// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.runtime.ModuleOutcome;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**wedge wording for built vs checked vs fully up to date. */
class BuildCommandSuccessTailTest {

    @Test
    void empty_plan_is_all_up_to_date() {
        String t = BuildCommand.successTail(List.of(), 0, null, System.nanoTime());
        assertThat(t).contains("all modules up to date");
        assertThat(t).doesNotContain("for ");
        assertThat(t).doesNotContain("checked");
    }

    @Test
    void empty_dirty_set_is_all_up_to_date() {
        String t = BuildCommand.successTail(List.of(), 0, List.of(), System.nanoTime());
        assertThat(t).contains("all modules up to date");
    }

    @Test
    void all_checked_modules() {
        var modules = List.of(
                new ModuleOutcome("a:b", Path.of("/a"), true, 0, 10, false),
                new ModuleOutcome("a:c", Path.of("/c"), true, 0, 12, false));
        String t = BuildCommand.successTail(modules, 2, null, System.nanoTime());
        assertThat(t).contains("checked");
        assertThat(t).contains("all up to date");
        assertThat(t).doesNotContain("for ");
        assertThat(t).doesNotContain("built");
    }

    @Test
    void all_built_modules() {
        var modules = List.of(new ModuleOutcome("a:b", Path.of("/a"), true, 0, 100, true));
        String t = BuildCommand.successTail(modules, 1, null, System.nanoTime());
        assertThat(t).contains("for ");
        assertThat(t).contains("1");
        assertThat(t).doesNotContain("checked");
    }

    @Test
    void mix_built_and_checked() {
        var modules = List.of(
                new ModuleOutcome("a:b", Path.of("/a"), true, 0, 100, true),
                new ModuleOutcome("a:c", Path.of("/c"), true, 0, 10, false));
        String t = TestAnsi.strip(BuildCommand.successTail(modules, 2, null, System.nanoTime()));
        assertThat(t).contains("built 1 module");
        assertThat(t).contains("checked 1 module");
        assertThat(t).doesNotContain("all up to date");
    }
}
