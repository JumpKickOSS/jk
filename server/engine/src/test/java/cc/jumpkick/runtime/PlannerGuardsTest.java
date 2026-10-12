// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.guard.rules.GuardsPresence;
import cc.jumpkick.guard.rules.LoadResult;
import cc.jumpkick.guard.rules.RuleSet;
import cc.jumpkick.model.GuardsConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard planner names a module the way every evaluator and baseline entry does, and parses the
 * rule layers once per content stamp.
 */
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

    /**
     * A rules file saved while the load runs moves the stamp. The memo is stored under the stamp
     * the load began with, so the next call reloads instead of serving the earlier read under the
     * new stamp for the engine's lifetime.
     */
    @Test
    void a_rules_file_saved_during_the_load_is_reloaded_by_the_next_call(@TempDir Path root) throws Exception {
        Path file = GuardsPresence.rulesFile(root);
        Files.writeString(file, "# first\n", StandardCharsets.UTF_8);
        PlannerGuards.GuardsPlan plan = new PlannerGuards.GuardsPlan(true, root, GuardsConfig.ABSENT);
        List<LoadResult> loads = new ArrayList<>();
        Supplier<LoadResult> loader = () -> {
            LoadResult load = new LoadResult(new RuleSet(Map.of(), Map.of(), GuardsConfig.ABSENT), List.of());
            loads.add(load);
            if (loads.size() == 1) {
                try {
                    Files.writeString(file, "# saved while the first load ran\n", StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return load;
        };

        assertThat(PlannerGuards.rules(plan, loader)).isSameAs(loads.get(0));
        assertThat(PlannerGuards.rules(plan, loader))
                .as("the file moved under the first load, so its result is not served again")
                .isSameAs(loads.get(1));
        assertThat(PlannerGuards.rules(plan, loader))
                .as("the second load read the file as it is, so it is memoized")
                .isSameAs(loads.get(1));
        assertThat(loads).hasSize(2);
    }
}
