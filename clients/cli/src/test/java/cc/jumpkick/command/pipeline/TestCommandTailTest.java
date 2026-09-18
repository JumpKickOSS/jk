// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The workspace test wedge names how many modules' suites were served from the action cache. */
class TestCommandTailTest {

    private static WorkspaceResult modules(int n) {
        List<ModuleOutcome> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new ModuleOutcome("g:m" + i, Path.of("/ws/m" + i), true, 0, 10, true));
        }
        return new WorkspaceResult(true, 0, out, List.of());
    }

    @Test
    void a_run_that_served_nothing_reads_as_before() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(3), 3, 0, 1200));
        assertThat(t).startsWith("Tests passed for 3 modules");
        assertThat(t).doesNotContain("served");
    }

    @Test
    void a_partly_served_run_names_the_count() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(49), 49, 47, 4600));
        assertThat(t).contains("49 modules, 47 served from cache");
    }

    @Test
    void a_wholly_served_run_says_so() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(4), 4, 4, 300));
        assertThat(t).contains("4 modules, all served from cache");
    }

    @Test
    void one_module_served() {
        String t = TestAnsi.strip(TestCommand.workspaceTestSuccessTail(modules(1), 1, 1, 30));
        assertThat(t).startsWith("Tests passed, served from cache");
    }
}
