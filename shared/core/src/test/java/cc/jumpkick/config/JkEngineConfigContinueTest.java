// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [engine] continue} — keep going instead of stopping at the first failing module.
 *
 * <p>Its unset default is CI-conditional, like the heap's, because the two situations want
 * opposite answers: at a prompt the first failure is the one you are about to fix, while on CI a
 * run costs a queue slot and one failure per run turns an N-fault branch into N round trips.
 */
class JkEngineConfigContinueTest {

    @TempDir
    Path tmp;

    private static Function<String, @Nullable String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    void unset_is_fail_fast_at_a_prompt_and_keep_going_on_ci() throws Exception {
        Path none = tmp.resolve("absent.toml");
        assertThat(JkEngineConfig.resolve(none, env(Map.of())).keepGoing()).isFalse();
        assertThat(JkEngineConfig.resolve(none, env(Map.of("CI", "true"))).keepGoing())
                .isTrue();
        assertThat(JkEngineConfig.resolve(none, env(Map.of("CI", "1"))).keepGoing())
                .isTrue();
    }

    @Test
    void the_env_wins_over_the_ci_default_in_both_directions() throws Exception {
        Path none = tmp.resolve("absent.toml");
        assertThat(JkEngineConfig.resolve(none, env(Map.of("CI", "true", "JK_CONTINUE", "false")))
                        .keepGoing())
                .as("CI moves the floor; it does not overrule an explicit answer")
                .isFalse();
        assertThat(JkEngineConfig.resolve(none, env(Map.of("JK_CONTINUE", "true")))
                        .keepGoing())
                .isTrue();
    }

    @Test
    void the_config_file_is_read_and_the_env_outranks_it() throws Exception {
        Path cfg = tmp.resolve("config.toml");
        Files.writeString(cfg, "[engine]\ncontinue = true\n");

        assertThat(JkEngineConfig.resolve(cfg, env(Map.of())).keepGoing()).isTrue();
        assertThat(JkEngineConfig.resolve(cfg, env(Map.of("JK_CONTINUE", "false")))
                        .keepGoing())
                .isFalse();
    }

    @Test
    void a_malformed_value_falls_back_rather_than_failing_the_build() throws Exception {
        Path cfg = tmp.resolve("bad.toml");
        Files.writeString(cfg, "[engine]\ncontinue = \"yes-please\"\n");

        // This is read on the way to every build; a typo must not be the thing that stops one.
        assertThat(JkEngineConfig.resolve(cfg, env(Map.of())).keepGoing()).isFalse();
    }
}
