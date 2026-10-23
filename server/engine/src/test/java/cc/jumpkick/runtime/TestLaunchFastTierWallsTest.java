// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.http.Http;
import cc.jumpkick.wire.EnginePaths;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The default test tier cannot reach Maven Central or start an engine: its JVMs get {@link
 * Http#DENY_HOSTS_ENV} refusing every remote host and {@link EnginePaths#NO_SPAWN_ENV}, a tagged
 * tier gets neither, and a module's own value stands.
 */
class TestLaunchFastTierWallsTest {

    private static TestSelection tier(List<String> include, List<String> exclude) {
        return TestSelection.of(List.of(), false, include, exclude);
    }

    @Test
    void the_default_tier_walls_off_every_remote_host() {
        WorkerEnv env =
                TestLaunch.withFastTierWalls(WorkerEnv.strict(), tier(List.of(), List.of("integration", "slow")));

        assertThat(env.extras())
                .containsEntry(Http.DENY_HOSTS_ENV, Http.ANY_REMOTE_HOST)
                .containsEntry(EnginePaths.NO_SPAWN_ENV, "1");
    }

    @Test
    void a_tier_that_includes_a_tag_may_fetch() {
        WorkerEnv env = TestLaunch.withFastTierWalls(WorkerEnv.strict(), tier(List.of("integration"), List.of("slow")));

        assertThat(env.extras()).doesNotContainKeys(Http.DENY_HOSTS_ENV, EnginePaths.NO_SPAWN_ENV);
    }

    @Test
    void a_launch_with_no_tag_filter_at_all_is_not_the_default_tier() {
        WorkerEnv env = TestLaunch.withFastTierWalls(WorkerEnv.strict(), tier(List.of(), List.of()));

        assertThat(env.extras()).doesNotContainKey(Http.DENY_HOSTS_ENV);
    }

    @Test
    void a_module_that_sets_the_list_itself_keeps_its_value() {
        WorkerEnv declared = WorkerEnv.strict().with(Map.of(Http.DENY_HOSTS_ENV, "", EnginePaths.NO_SPAWN_ENV, "0"));

        WorkerEnv env = TestLaunch.withFastTierWalls(declared, tier(List.of(), List.of("integration")));

        assertThat(env.extras()).containsEntry(Http.DENY_HOSTS_ENV, "").containsEntry(EnginePaths.NO_SPAWN_ENV, "0");
    }
}
