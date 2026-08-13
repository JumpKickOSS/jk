// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.protocol.ProtoJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link BuildPlanner#effectiveSelection} must honor an explicitly cleared tag list (JK-1809): a
 * profile's {@code exclude-tags = []} resolves to empty lists with {@code tagsResolved}, and the
 * engine must not fold the module's own {@code [test]} tags back in.
 */
class EffectiveSelectionTest {

    private static Path moduleWithExcludes(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                [project]
                name = "demo"
                group = "t"
                version = "0.0.1"
                java = 25

                [test]
                exclude-tags = ["slow", "integration"]
                """);
        return dir;
    }

    @Test
    void cleared_selection_stays_cleared(@TempDir Path dir) throws Exception {
        moduleWithExcludes(dir);
        var cleared = TestSelection.of(java.util.List.of(), false, java.util.List.of(), java.util.List.of(), true);
        var eff = BuildPlanner.effectiveSelection(cleared, dir);
        assertThat(eff.excludeTags()).isEmpty();
        assertThat(eff.includeTags()).isEmpty();
    }

    @Test
    void unresolved_default_still_folds_module_tags(@TempDir Path dir) throws Exception {
        moduleWithExcludes(dir);
        var eff = BuildPlanner.effectiveSelection(TestSelection.DEFAULT, dir);
        assertThat(eff.excludeTags()).containsExactly("slow", "integration");
    }

    @Test
    void tags_resolved_survives_the_wire_round_trip(@TempDir Path dir) throws Exception {
        moduleWithExcludes(dir);
        var cleared = TestSelection.of(java.util.List.of(), false, java.util.List.of(), java.util.List.of(), true);
        String json = "{" + ProtoJobs.testSelectionFields(cleared).substring(1) + "}";
        var decoded = ProtoJobs.testSelectionOf(json);
        assertThat(decoded.tagsResolved()).isTrue();
        assertThat(BuildPlanner.effectiveSelection(decoded, dir).excludeTags()).isEmpty();

        String defJson =
                "{" + ProtoJobs.testSelectionFields(TestSelection.DEFAULT).substring(1) + "}";
        assertThat(ProtoJobs.testSelectionOf(defJson).tagsResolved()).isFalse();
    }
}
