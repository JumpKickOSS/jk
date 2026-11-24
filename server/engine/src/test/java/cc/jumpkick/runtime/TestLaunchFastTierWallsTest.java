// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.engine.plugin.WorkerEnv;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.test.SuiteFilter;
import cc.jumpkick.wire.EnginePaths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The default test tier cannot reach Maven Central or start an engine: in a module that routes
 * tiers, the fork spec of the launch that runs the default suite (beside no tag suite) gives its JVMs
 * {@link Http#DENY_HOSTS_ENV} refusing every remote host and {@link EnginePaths#NO_SPAWN_ENV}, a tag
 * suite's launch or a tagged run gets neither, and a module's own value stands.
 */
class TestLaunchFastTierWallsTest {

    private static final JkBuild UNTIERED = JkBuildParser.parse("""
            group = "t"
            name = "m"
            version = "1.0.0"
            """);

    private static final JkBuild TIERED = JkBuildParser.parse("""
            group = "t"
            name = "m"
            version = "1.0.0"

            [test.suites.slow]
            tags = ["slow"]
            """);

    private static final Set<String> DEFAULT = Set.of("test");

    @Test
    void the_default_tier_walls_off_every_remote_host() {
        WorkerEnv env = walls(DEFAULT, TestSelection.DEFAULT, TIERED);

        assertThat(env.extras())
                .containsEntry(Http.DENY_HOSTS_ENV, Http.ANY_REMOTE_HOST)
                .containsEntry(EnginePaths.NO_SPAWN_ENV, "1");
    }

    /** Under -g the default suite's launch (beside a directory suite such as e2e) is the fast tier too. */
    @Test
    void the_default_suites_launch_beside_a_directory_suite_is_walled() {
        WorkerEnv env = walls(Set.of("test", "e2e"), TestSelection.DEFAULT, TIERED);

        assertThat(env.extras()).containsEntry(Http.DENY_HOSTS_ENV, Http.ANY_REMOTE_HOST);
    }

    /**
     * The guard's selection (test, integration, e2e, tree) walls the launch that runs the default
     * suite, as a plain run does: an engine a class there started would key every class after it in
     * that JVM by the engine's bytes.
     */
    @Test
    void the_guards_launch_of_the_default_suite_is_walled() {
        JkBuild guarded = JkBuildParser.parse("""
                group = "t"
                name = "m"
                version = "1.0.0"

                [test.suites.integration]
                tags = ["integration"]
                timeout = "5m"

                [test.suites.tree]
                tags = ["tree"]
                """);
        Set<String> withDefault =
                SuiteFilter.launches(
                                List.of("test", "integration", "e2e", "tree"),
                                guarded.build().runSuites())
                        .stream()
                        .map(SuiteFilter.Launch::suites)
                        .filter(suites -> suites.contains("test"))
                        .findFirst()
                        .orElseThrow();

        assertThat(walls(withDefault, TestSelection.DEFAULT, guarded).extras())
                .containsEntry(Http.DENY_HOSTS_ENV, Http.ANY_REMOTE_HOST)
                .containsEntry(EnginePaths.NO_SPAWN_ENV, "1");
    }

    @Test
    void a_tag_suites_launch_or_an_included_tag_may_fetch() {
        assertThat(walls(Set.of("integration"), TestSelection.DEFAULT, TIERED).extras())
                .as("the integration suite's own launch")
                .doesNotContainKeys(Http.DENY_HOSTS_ENV, EnginePaths.NO_SPAWN_ENV);
        assertThat(walls(Set.of("test", "slow"), TestSelection.DEFAULT, TIERED).extras())
                .as("a launch of the default suite beside a tag suite")
                .doesNotContainKeys(Http.DENY_HOSTS_ENV, EnginePaths.NO_SPAWN_ENV);
        TestSelection smoke = TestSelection.of(List.of(), false, List.of("smoke"), List.of());
        assertThat(walls(DEFAULT, smoke, TIERED).extras())
                .as("a run that includes a tag")
                .doesNotContainKeys(Http.DENY_HOSTS_ENV, EnginePaths.NO_SPAWN_ENV);
    }

    @Test
    void a_module_that_routes_no_tier_has_no_default_tier_to_wall() {
        WorkerEnv env = walls(DEFAULT, TestSelection.DEFAULT, UNTIERED);

        assertThat(env.extras()).doesNotContainKey(Http.DENY_HOSTS_ENV);
    }

    @Test
    void a_module_that_sets_the_list_itself_keeps_its_value() {
        WorkerEnv declared = WorkerEnv.strict().with(Map.of(Http.DENY_HOSTS_ENV, "", EnginePaths.NO_SPAWN_ENV, "0"));

        WorkerEnv env = declared.withDefaults(
                TestLaunch.forkSpec(TIERED, TestSelection.DEFAULT, DEFAULT).env());

        assertThat(env.extras()).containsEntry(Http.DENY_HOSTS_ENV, "").containsEntry(EnginePaths.NO_SPAWN_ENV, "0");
    }

    /** The environment a launch of {@code suites} under {@code selection} gives its JVMs on top of none. */
    private static WorkerEnv walls(Set<String> suites, TestSelection selection, JkBuild project) {
        return WorkerEnv.strict()
                .withDefaults(TestLaunch.forkSpec(project, selection, suites).env());
    }
}
