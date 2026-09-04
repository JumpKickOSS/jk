// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The three job knobs resolve from an explicit environment, so no test depends on the JVM's own. */
class JobLimitsTest {

    @Test
    void unset_environment_yields_the_documented_defaults() {
        JobLimits limits = JobLimits.resolve(k -> null);
        assertThat(limits).isEqualTo(JobLimits.DEFAULTS);
        assertThat(limits.heartbeatMs()).isEqualTo(registryDefault("JK_ENGINE_HEARTBEAT_MS"));
        assertThat(limits.deadlineMs()).isEqualTo(registryDefault("JK_ENGINE_JOB_DEADLINE_MS"));
        assertThat(limits.deadlineGraceMs()).isEqualTo(registryDefault("JK_ENGINE_JOB_DEADLINE_GRACE_MS"));
    }

    @Test
    void each_knob_reads_its_own_variable() {
        JobLimits limits = JobLimits.resolve(Map.of(
                "JK_ENGINE_HEARTBEAT_MS", "0",
                "JK_ENGINE_JOB_DEADLINE_MS", "50",
                "JK_ENGINE_JOB_DEADLINE_GRACE_MS", "100")::get);
        assertThat(limits).isEqualTo(new JobLimits(0L, 50L, 100L));
    }

    @Test
    void malformed_or_negative_values_fall_back_to_the_default() {
        JobLimits limits =
                JobLimits.resolve(Map.of("JK_ENGINE_HEARTBEAT_MS", "soon", "JK_ENGINE_JOB_DEADLINE_MS", "-1")::get);
        assertThat(limits).isEqualTo(JobLimits.DEFAULTS);
    }

    @Test
    void the_knobs_ride_the_engine_config(@TempDir Path dir) {
        JkEngineConfig resolved =
                JkEngineConfig.resolve(dir.resolve("none.toml"), Map.of("JK_ENGINE_JOB_DEADLINE_MS", "50")::get);
        assertThat(resolved.jobLimits().deadlineMs()).isEqualTo(50L);
        assertThat(JkEngineConfig.DEFAULTS.jobLimits()).isEqualTo(JobLimits.DEFAULTS);
        assertThat(JkEngineConfig.DEFAULTS
                        .withJobLimits(new JobLimits(1L, 2L, 3L))
                        .jobLimits())
                .isEqualTo(new JobLimits(1L, 2L, 3L));
    }

    /** The default {@code docs/user/engine.md} renders for {@code env}, so the two cannot disagree. */
    private static long registryDefault(String env) {
        return EngineControls.PROCESS.stream()
                .filter(c -> c.env().equals(env))
                .mapToLong(c -> Long.parseLong(c.defaultValue()))
                .findFirst()
                .orElseThrow();
    }
}
