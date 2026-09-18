// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.git.GitFetcher;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The assembly of the module jk installs as its own product lib carries the commit time of the
 * checkout it was packaged from as {@code Build-Time}, so two engines of one version are ordered by
 * their source rather than by when a jar was copied; every other assembly, and a checkout git does
 * not describe, carries no such attribute — the jar stays a pure function of its inputs.
 */
class AssemblyBuildTimeTest {

    private static final Instant COMMITTED = Instant.parse("2026-09-17T15:57:16Z");
    private static final GitFetcher.Worktree WORKTREE =
            new GitFetcher.Worktree("4ee07400a592deadbeef", "main", COMMITTED, false, Optional.empty());

    private static JkBuild productLib() {
        return JkBuild.builder(new Project("cc.jumpkick", "jk-engine", "0.13.7", 25))
                .install(new JkBuild.Install("jk-engine", null))
                .build();
    }

    @Test
    void the_product_lib_assembly_is_stamped_with_the_commit_time() {
        Map<String, String> attrs = PlannerTails.assemblyAttributes(productLib(), Optional.of(WORKTREE));
        assertThat(attrs).containsEntry(BuildIdentity.BUILD_TIME_ATTRIBUTE, "2026-09-17T15:57:16Z");
    }

    @Test
    void an_ordinary_application_and_an_undescribed_checkout_carry_no_build_time() {
        JkBuild app = JkBuild.builder(new Project("com.example", "app", "1.0.0", 25))
                .manifest(Map.of("Implementation-Title", "app"))
                .build();
        assertThat(PlannerTails.assemblyAttributes(app, Optional.of(WORKTREE)))
                .containsOnly(Map.entry("Implementation-Title", "app"));
        assertThat(PlannerTails.assemblyAttributes(productLib(), Optional.empty()))
                .doesNotContainKey(BuildIdentity.BUILD_TIME_ATTRIBUTE);
    }
}
