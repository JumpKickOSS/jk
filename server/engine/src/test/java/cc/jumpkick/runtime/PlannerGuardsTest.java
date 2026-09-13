// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The guard planner names a module the way every evaluator and baseline entry does. */
class PlannerGuardsTest {

    @Test
    void a_lane_key_spells_an_out_of_root_member_by_its_workspace_relative_path(@TempDir Path dir) throws Exception {
        Path root = Files.createDirectories(dir.resolve("ws"));
        Path sib = Files.createDirectories(dir.resolve("sib"));
        List<String> tokens = GuardKeys.workspaceTokens(root, List.of(root, root.resolve("app"), sib));
        assertThat(tokens)
                .as("a key with an absolute path in it is a key no other machine can hit")
                .containsExactly("facts::absent", "facts:app:absent", "facts:../sib:absent");
    }
}
