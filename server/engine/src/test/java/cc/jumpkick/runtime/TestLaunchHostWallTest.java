// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.http.Http;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default test tier cannot reach Maven Central: its JVMs get {@link Http#DENY_HOSTS_ENV} naming
 * Central and its mirror, a tagged tier does not, and a module's own value stands.
 */
class TestLaunchHostWallTest {

    private static TestSelection tier(List<String> include, List<String> exclude) {
        return TestSelection.of(List.of(), false, include, exclude);
    }

    @Test
    void the_default_tier_walls_off_central_and_its_mirror() {
        WorkerEnv env = TestLaunch.withHostWall(WorkerEnv.strict(), tier(List.of(), List.of("integration", "slow")));

        assertThat(env.extras())
                .containsEntry(
                        Http.DENY_HOSTS_ENV, "repo.maven.apache.org,maven-central.storage-download.googleapis.com");
    }

    @Test
    void a_tier_that_includes_a_tag_may_fetch() {
        WorkerEnv env = TestLaunch.withHostWall(WorkerEnv.strict(), tier(List.of("integration"), List.of("slow")));

        assertThat(env.extras()).doesNotContainKey(Http.DENY_HOSTS_ENV);
    }

    @Test
    void a_launch_with_no_tag_filter_at_all_is_not_the_default_tier() {
        WorkerEnv env = TestLaunch.withHostWall(WorkerEnv.strict(), tier(List.of(), List.of()));

        assertThat(env.extras()).doesNotContainKey(Http.DENY_HOSTS_ENV);
    }

    @Test
    void a_module_that_sets_the_list_itself_keeps_its_value() {
        WorkerEnv declared = WorkerEnv.strict().with(Map.of(Http.DENY_HOSTS_ENV, ""));

        WorkerEnv env = TestLaunch.withHostWall(declared, tier(List.of(), List.of("integration")));

        assertThat(env.extras()).containsEntry(Http.DENY_HOSTS_ENV, "");
    }
}
