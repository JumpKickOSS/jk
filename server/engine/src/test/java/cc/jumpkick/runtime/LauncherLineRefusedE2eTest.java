// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.resolver.ResolveObserver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher pinned to the Platform 1 line beside Jupiter 6: the launcher's own floor is met,
 * but a Platform 1 launcher cannot start a Jupiter 6 engine, so {@code jk lock} refuses the pin
 * with the one that aligns the lines instead of writing a lock whose run would fail before any
 * test ran.
 */
// Out of the unit tier: network resolve of JUnit.
@Tag("integration")
class LauncherLineRefusedE2eTest {

    @Test
    void a_launcher_pin_off_the_jupiters_line_is_refused_at_lock_with_the_aligning_pin(@TempDir Path project)
            throws Exception {
        Path cache = TestCaches.dir("android-spike-cache");
        Files.writeString(project.resolve("jk.toml"), """
                name    = "pinned"
                group   = "com.example"
                version = "1.0.0"
                java    = 25

                [test-dependencies]
                junit-jupiter           = { group = "org.junit.jupiter", name = "junit-jupiter", version = "6.1.3" }
                junit-platform-launcher = { group = "org.junit.platform", name = "junit-platform-launcher", version = "=1.13.4" }  # off the train: Platform 1 beside Jupiter 6

                [repositories]
                central = "https://repo.maven.apache.org/maven2/"
                """);

        JkBuild build = JkBuildParser.parse(project.resolve("jk.toml"));
        BuildPlan lock = LockPlans.lockBuildPlan(
                project, build, cache, null, List.of(), true, false, ResolveObserver.NOOP, null);
        BuildPlanResult result = lock.run();

        assertThat(result.success())
                .as("the lines disagree, so the lock is refused")
                .isFalse();
        assertThat(result.errors()).anySatisfy(d -> assertThat(d.message())
                .contains("junit-jupiter 6.1.3 runs on JUnit Platform 6.1.3")
                .contains("junit-platform-launcher resolved to 1.13.4")
                .contains("pin junit-platform-launcher to 6.1.3"));
        assertThat(project.resolve("jk-lock.toml")).doesNotExist();
    }
}
