// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.wire.protocol.ProtoJobs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PlannerSupport#effectiveSelection} must honor an explicitly cleared tag list: a
 * profile's {@code exclude-tags = []} resolves to empty lists with {@code tagsResolved}, and the
 * engine must not fold the module's own {@code [test]} tags back in. An unresolved selection takes
 * the workspace root's tiers, never a member's own list over them, and the active profile's tags
 * and {@code all-suites} through {@code inherits}.
 */
class EffectiveSelectionTest {

    private static Path moduleWithExcludes(Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
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
        var cleared = TestSelection.of(List.of(), false, List.of(), List.of(), true);
        var eff = PlannerSupport.effectiveSelection(cleared, dir, null);
        assertThat(eff.excludeTags()).isEmpty();
        assertThat(eff.includeTags()).isEmpty();
    }

    @Test
    void unresolved_default_still_folds_module_tags(@TempDir Path dir) throws Exception {
        moduleWithExcludes(dir);
        var eff = PlannerSupport.effectiveSelection(TestSelection.DEFAULT, dir, null);
        assertThat(eff.excludeTags()).containsExactly("slow", "integration");
    }

    @Test
    void a_member_without_its_own_tags_takes_the_workspace_roots(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "t"
                version = "0.0.1"
                java = 25

                [workspace]
                modules = ["lib"]

                [test]
                exclude-tags = ["integration", "bench"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"
                """);
        var eff = PlannerSupport.effectiveSelection(TestSelection.DEFAULT, lib, null);
        assertThat(eff.excludeTags()).containsExactly("integration", "bench");
    }

    @Test
    void tags_resolved_survives_the_wire_round_trip(@TempDir Path dir) throws Exception {
        moduleWithExcludes(dir);
        var cleared = TestSelection.of(List.of(), false, List.of(), List.of(), true);
        String json = "{" + ProtoJobs.testSelectionFields(cleared).substring(1) + "}";
        var decoded = ProtoJobs.testSelectionOf(json);
        assertThat(decoded.tagsResolved()).isTrue();
        assertThat(PlannerSupport.effectiveSelection(decoded, dir, null).excludeTags())
                .isEmpty();

        String defJson =
                "{" + ProtoJobs.testSelectionFields(TestSelection.DEFAULT).substring(1) + "}";
        assertThat(ProtoJobs.testSelectionOf(defJson).tagsResolved()).isFalse();
    }

    @Test
    void gate_survives_module_tag_fold(@TempDir Path dir) throws Exception {
        moduleWithExcludes(dir);
        var gate = TestSelection.of(List.of("test", "integration"), false, List.of(), List.of(), false, true);
        var eff = PlannerSupport.effectiveSelection(gate, dir, null);
        assertThat(eff.guard()).isTrue();
        assertThat(eff.suites()).containsExactly("test", "integration");
        assertThat(eff.excludeTags()).containsExactly("slow", "integration");
    }

    private static Path workspaceWithMemberList(Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "t"
                version = "0.0.1"
                java = 25

                [workspace]
                modules = ["lib"]

                [test]
                exclude-tags = ["integration", "bench"]

                [profiles.integration]
                include-tags = ["integration"]
                exclude-tags = []
                all-suites = true

                [profiles.nightly]
                inherits = "integration"
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"

                [test]
                exclude-tags = ["slow"]
                """);
        return lib;
    }

    @Test
    void a_members_own_list_does_not_override_the_roots(@TempDir Path ws) throws Exception {
        Path lib = workspaceWithMemberList(ws);
        var eff = PlannerSupport.effectiveSelection(TestSelection.DEFAULT, lib, null);
        assertThat(eff.excludeTags()).containsExactly("integration", "bench");
        assertThat(eff.allSuites()).isFalse();
    }

    @Test
    void an_inheriting_profile_brings_its_parents_tags_and_all_suites(@TempDir Path ws) throws Exception {
        Path lib = workspaceWithMemberList(ws);
        var eff = PlannerSupport.effectiveSelection(TestSelection.DEFAULT, lib, "nightly");
        assertThat(eff.includeTags()).containsExactly("integration");
        assertThat(eff.excludeTags()).isEmpty();
        assertThat(eff.allSuites()).isTrue();

        var named = TestSelection.of(List.of("e2e"), false, List.of(), List.of());
        var suite = PlannerSupport.effectiveSelection(named, lib, "nightly");
        assertThat(suite.allSuites()).as("a named suite wins over the switch").isFalse();
        assertThat(suite.suites()).containsExactly("e2e");
        assertThat(suite.includeTags()).containsExactly("integration");
    }
}
