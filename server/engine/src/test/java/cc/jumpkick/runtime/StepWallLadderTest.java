// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.builds.AggregatedMetrics;
import cc.jumpkick.builds.ProjectBuilds;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.runtime.base.BuildMetrics;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A step prices from the ledger's trimmed mean once it has two samples; the single sample when it
 * has one. The last sample of a full rebuild is the most contended wall the ledger holds, so
 * preferring it added the widest build's contention to every critical path that followed.
 */
class StepWallLadderTest {

    @TempDir
    Path state;

    private String prevStateDir;

    @BeforeEach
    void isolateState() {
        prevStateDir = System.getProperty("jk.env.JK_STATE_DIR");
        System.setProperty("jk.env.JK_STATE_DIR", state.resolve("state").toString());
        BuildMetrics.clearSessionAggregatesMemo();
    }

    @AfterEach
    void restoreState() {
        if (prevStateDir == null) System.clearProperty("jk.env.JK_STATE_DIR");
        else System.setProperty("jk.env.JK_STATE_DIR", prevStateDir);
        BuildMetrics.clearSessionAggregatesMemo();
    }

    private long priced(Path moduleDir, String mean, String last, String count) throws Exception {
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, moduleDir);
        Files.createDirectories(home);
        String key = "module." + AggregatedMetrics.sanitize(moduleDir.toString()) + ".task.compile-test.wall-ms";
        Files.writeString(home.resolve(ProjectBuilds.PROJECT_METRICS), """
                [mean]
                %s = %s
                [last]
                %s = %s
                [count]
                %s = %s
                """.formatted(key, mean, key, last, key, count));
        BuildMetrics.clearSessionAggregatesMemo();
        return SessionContext.where(
                Session.defaults().withWorkingDir(moduleDir),
                () -> EffortWeights.stepOkAvgMillisOwn(null, moduleDir.toString(), "compile-test"));
    }

    @Test
    void many_samples_price_the_trimmed_mean_not_the_contended_last() throws Exception {
        Path module = Files.createDirectories(state.resolve("engine"));
        assertThat(priced(module, "6815", "28056", "6")).isEqualTo(6815L);
    }

    @Test
    void one_sample_prices_that_sample() throws Exception {
        Path module = Files.createDirectories(state.resolve("fresh"));
        assertThat(priced(module, "4100", "4100", "1")).isEqualTo(4100L);
    }

    @Test
    void a_last_without_a_mean_still_prices() throws Exception {
        Path module = Files.createDirectories(state.resolve("lastonly"));
        Path home = ProjectBuilds.projectHome(JkDirs.builds(), null, module);
        Files.createDirectories(home);
        String key = "module." + AggregatedMetrics.sanitize(module.toString()) + ".task.compile-test.wall-ms";
        Files.writeString(home.resolve(ProjectBuilds.PROJECT_METRICS), "[last]\n" + key + " = 900\n");
        BuildMetrics.clearSessionAggregatesMemo();
        long wall = SessionContext.where(
                Session.defaults().withWorkingDir(module),
                () -> EffortWeights.stepOkAvgMillisOwn(null, module.toString(), "compile-test"));
        assertThat(wall).isEqualTo(900L);
    }
}
