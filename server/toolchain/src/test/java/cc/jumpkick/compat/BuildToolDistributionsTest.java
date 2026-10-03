// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.gradle.GradleResolver;
import cc.jumpkick.kotlin.KotlinResolver;
import cc.jumpkick.mvn.MavenResolver;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What {@code jk tool install <tool>:latest} installs, against what a build provisions when it
 * needs the same tool and nothing has pinned it.
 *
 * <p>Compared through the resolvers rather than against two version literals: a test that spelled
 * {@code "2.4.0"} on both sides would keep passing after one of them moved, which is the whole
 * failure mode an ahead-of-time install has — you install the tool, the build downloads a different
 * one, and nothing says so.
 */
class BuildToolDistributionsTest {

    @Test
    void latest_is_what_the_engine_would_provision_on_demand() throws Exception {
        assertThat(BuildToolDistributions.of(BuildTool.KOTLIN, BuildTool.LATEST))
                .isEqualTo(KotlinResolver.defaultDistribution());
        assertThat(BuildToolDistributions.of(BuildTool.MAVEN, BuildTool.LATEST))
                .isEqualTo(MavenResolver.defaultDistribution());
        assertThat(BuildToolDistributions.of(BuildTool.GRADLE, BuildTool.LATEST))
                .isEqualTo(GradleResolver.defaultDistribution());
    }

    @Test
    void an_absent_or_blank_version_means_the_same_as_latest() throws Exception {
        for (BuildTool tool : BuildTool.values()) {
            assertThat(BuildToolDistributions.of(tool, null)).isEqualTo(BuildToolDistributions.of(tool, "latest"));
            assertThat(BuildToolDistributions.of(tool, "  ")).isEqualTo(BuildToolDistributions.of(tool, "LATEST"));
        }
    }

    @Test
    void an_explicit_version_lands_in_the_url_and_in_the_install_dir() throws Exception {
        ToolDistribution kotlin = BuildToolDistributions.of(BuildTool.KOTLIN, "2.3.1");
        assertThat(kotlin.version()).isEqualTo("2.3.1");
        assertThat(kotlin.downloadUri().toString()).contains("2.3.1");

        ToolDistribution maven = BuildToolDistributions.of(BuildTool.MAVEN, "3.9.6");
        assertThat(maven.version()).isEqualTo("3.9.6");
        assertThat(maven.downloadUri().toString()).contains("3.9.6");

        ToolDistribution gradle = BuildToolDistributions.of(BuildTool.GRADLE, "8.14");
        assertThat(gradle.version()).isEqualTo("8.14");
        assertThat(gradle.downloadUri().toString()).contains("8.14");
    }

    /**
     * Every tool a user can name round-trips by slug; the ones whose default needs no network (Node
     * and the package managers ask a catalog) resolve to a distribution of that tool.
     */
    @Test
    void every_build_tool_slug_round_trips_to_a_distribution() throws Exception {
        for (BuildTool tool : BuildTool.values()) {
            assertThat(BuildTool.bySlug(tool.slug())).contains(tool);
            assertThat(BuildTool.slugs()).contains(tool.slug());
        }
        for (BuildTool tool : List.of(BuildTool.MAVEN, BuildTool.GRADLE, BuildTool.KOTLIN)) {
            assertThat(BuildToolDistributions.of(tool, BuildTool.LATEST).tool()).isEqualTo(tool);
        }
        assertThat(BuildTool.bySlug("not-a-build-tool")).isEmpty();
        assertThat(BuildTool.bySlug(null)).isEmpty();
    }
}
