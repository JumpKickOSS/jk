// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The job knobs resolve from an explicit environment and file, so no test depends on the JVM's own. */
class JobLimitsTest {

    @Test
    void unset_environment_yields_the_documented_defaults() {
        JobLimits limits = JobLimits.resolve(k -> null);
        assertThat(limits).isEqualTo(JobLimits.DEFAULTS);
        assertThat(limits.heartbeatMs()).isEqualTo(registryDefault("JK_ENGINE_HEARTBEAT_MS"));
        assertThat(limits.deadlineMs()).isEqualTo(registryDefault("JK_ENGINE_JOB_DEADLINE_MS"));
        assertThat(limits.detachedDeadlineMs()).isEqualTo(registryDefault("JK_ENGINE_DETACHED_DEADLINE_MS"));
        assertThat(limits.deadlineGraceMs()).isEqualTo(registryDefault("JK_ENGINE_JOB_DEADLINE_GRACE_MS"));
        assertThat(limits.cancelGraceMs()).isEqualTo(registryDefault("JK_CANCEL_GRACE_MS"));
    }

    /**
     * A socket job's EOF is its deadline, so its wall cap is off by default; a detached job has
     * nothing but the clock, so its cap is on by default and is the one job knob with a file key.
     */
    @Test
    void only_the_detached_deadline_is_on_by_default() {
        assertThat(JobLimits.DEFAULTS.deadlineMs()).isZero();
        assertThat(JobLimits.DEFAULTS.detachedDeadlineMs())
                .isEqualTo(Duration.ofHours(1).toMillis());
        assertThat(EngineControls.TABLE.stream().map(EngineControls.Control::toml))
                .contains("detached-deadline-ms")
                .doesNotContain("job-deadline-ms");
    }

    @Test
    void the_detached_deadline_reads_env_over_file_over_default() {
        assertThat(JobLimits.resolve(k -> null, 20_000L).detachedDeadlineMs()).isEqualTo(20_000L);
        assertThat(JobLimits.resolve(Map.of("JK_ENGINE_DETACHED_DEADLINE_MS", "5000")::get, 20_000L)
                        .detachedDeadlineMs())
                .isEqualTo(5_000L);
        assertThat(JobLimits.resolve(Map.of("JK_ENGINE_DETACHED_DEADLINE_MS", "-5")::get, 20_000L)
                        .detachedDeadlineMs())
                .as("a negative env value falls through to the file layer")
                .isEqualTo(20_000L);
        assertThat(JobLimits.resolve(k -> null, -1L).detachedDeadlineMs())
                .as("a negative file value falls through to the default")
                .isEqualTo(JobLimits.DEFAULT_DETACHED_DEADLINE_MS);
        assertThat(JobLimits.fromFile(0L).detachedDeadlineMs())
                .as("0 lifts the cap")
                .isZero();
    }

    @Test
    void the_detached_deadline_rides_the_engine_config_file(@TempDir Path dir) throws IOException {
        Path toml = dir.resolve("config.toml");
        Files.writeString(toml, "[engine]\ndetached-deadline-ms = 90000\n");
        assertThat(JkEngineConfig.fromToml(toml).jobLimits().detachedDeadlineMs())
                .isEqualTo(90_000L);
        assertThat(JkEngineConfig.resolve(toml, k -> null).jobLimits().detachedDeadlineMs())
                .isEqualTo(90_000L);
        assertThat(JkEngineConfig.resolve(toml, Map.of("JK_ENGINE_DETACHED_DEADLINE_MS", "1")::get)
                        .jobLimits()
                        .detachedDeadlineMs())
                .isEqualTo(1L);
    }

    @Test
    void the_cancel_grace_is_clamped_to_its_ceiling_and_a_negative_falls_back() {
        assertThat(JobLimits.resolve(Map.of("JK_CANCEL_GRACE_MS", "60000")::get).cancelGraceMs())
                .isEqualTo(JobLimits.MAX_CANCEL_GRACE_MS);
        assertThat(JobLimits.resolve(Map.of("JK_CANCEL_GRACE_MS", "-1")::get).cancelGraceMs())
                .isEqualTo(JobLimits.DEFAULT_CANCEL_GRACE_MS);
        assertThat(JobLimits.resolve(Map.of("JK_CANCEL_GRACE_MS", "0")::get).cancelGraceMs())
                .isZero();
    }

    @Test
    void each_knob_reads_its_own_variable() {
        JobLimits limits = JobLimits.resolve(Map.of(
                "JK_ENGINE_HEARTBEAT_MS", "0",
                "JK_ENGINE_JOB_DEADLINE_MS", "50",
                "JK_ENGINE_JOB_DEADLINE_GRACE_MS", "100",
                "JK_CANCEL_GRACE_MS", "5")::get);
        assertThat(limits).isEqualTo(new JobLimits(0L, 50L, JobLimits.DEFAULT_DETACHED_DEADLINE_MS, 100L, 5L));
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
                        .withJobLimits(new JobLimits(1L, 2L, 4L, 3L, 500L))
                        .jobLimits())
                .isEqualTo(new JobLimits(1L, 2L, 4L, 3L, 500L));
    }

    /** The default {@code docs/user/engine.md} renders for {@code env}, so the two cannot disagree. */
    private static long registryDefault(String env) {
        return Stream.concat(EngineControls.PROCESS.stream(), EngineControls.TABLE.stream())
                .filter(c -> c.env().equals(env))
                .mapToLong(c -> Long.parseLong(c.defaultValue()))
                .findFirst()
                .orElseThrow();
    }
}
