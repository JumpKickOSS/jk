// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TierTags;
import cc.jumpkick.runtime.base.ProjectInfoPlans;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A member's own {@code [test]} and {@code [profiles.<name>]} tag lists override nothing the
 * workspace root sets: the {@code --class} tier hints and {@code jk info} answer with the root's
 * tiers, as every run does.
 */
class MemberTierTagsTest {

    private static Path workspace(Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                name = "ws"
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

                [profiles.nightly]
                inherits = "integration"
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"
                group = "t"
                version = "0.0.1"
                java = 25

                [test]
                exclude-tags = ["slow"]

                [profiles.integration]
                include-tags = ["slow"]
                exclude-tags = ["integration"]
                """);
        return lib;
    }

    @Test
    void the_class_tier_hint_uses_the_roots_default_tier(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws);
        assertThat(PlannerTest.defaultTier(lib))
                .isEqualTo(new TestClassMatch.Tier(List.of(), List.of("integration", "bench")));
    }

    @Test
    void the_class_tier_hint_names_the_roots_profiles_through_inherits(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws);
        String dropped = "--class named 1 class the tag filter excluded: a.OrdersIT [integration];"
                + " pass --include-tags integration (or a -p profile that includes it) to run it";
        String explained = TestClassMatch.explainTagExcluded(
                dropped,
                List.of("integration", "nightly"),
                name -> TierTags.profile(TierTags.rootOf(lib), name),
                null,
                PlannerTest.defaultTier(lib));
        assertThat(explained)
                .as("the member's own integration profile excludes the tag; the root's runs it")
                .isEqualTo(dropped + "; -p integration or -p nightly runs it");
    }

    @Test
    void jk_info_shows_the_roots_tags_for_a_member(@TempDir Path ws) throws Exception {
        Path lib = workspace(ws);
        var info = ProjectInfoPlans.projectInfo(lib);
        assertThat(info.error()).isNull();
        assertThat(info.testIncludeTags()).isEmpty();
        assertThat(info.testExcludeTags()).containsExactly("integration", "bench");
    }
}
