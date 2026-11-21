// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The progress-token registry on its own: a numeric token resolves under its integral text form,
 * a full registry evicts finished and then the busiest session's tokens first, and forgetting a
 * session drops only its tokens.
 */
class ProgressTokenRegistryTest {

    @Test
    void a_full_registry_keeps_the_token_of_a_job_still_running() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.live(jid -> jid == 1L);
        tokens.bind(null, "running", 1L);
        for (int i = 0; i < 300; i++) tokens.bind(null, "t" + i, 100L + i);

        assertThat(tokens.resolve(null, "running")).isEqualTo(1L);
        assertThat(tokens.resolve(null, "t299")).isEqualTo(399L);
        assertThat(tokens.resolve(null, "t0"))
                .as("the oldest finished job's token goes first")
                .isNull();
        assertThat(tokens.size()).isLessThanOrEqualTo(ProgressTokenRegistry.MAX_ENTRIES);
    }

    @Test
    void resolve_canonicalizes_double_form_queries_and_leaves_strings_alone() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.bind(null, "7", 99L);
        assertThat(tokens.resolve(null, "7.0")).isEqualTo(99L);
        tokens.bind(null, "tok-1", 100L);
        assertThat(tokens.resolve(null, "tok-1")).isEqualTo(100L);
        assertThat(ProgressTokenRegistry.canonicalText("5.0")).isEqualTo("5");
        assertThat(ProgressTokenRegistry.canonicalText("-3.00")).isEqualTo("-3");
        assertThat(ProgressTokenRegistry.canonicalText("v1.0")).isEqualTo("v1.0");
        assertThat(ProgressTokenRegistry.canonicalText("5.5")).isEqualTo("5.5");
    }

    @Test
    void a_busy_session_evicts_its_own_running_tokens_before_another_sessions() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.live(jid -> true);
        tokens.bind("ab12", "7", 1L);
        for (int i = 0; i < 300; i++) tokens.bind("cd34", "t" + i, 100L + i);

        assertThat(tokens.resolve("ab12", "7")).isEqualTo(1L);
        assertThat(tokens.resolve("cd34", "t299")).isEqualTo(399L);
        assertThat(tokens.resolve("cd34", "t0")).isNull();
        assertThat(tokens.size()).isLessThanOrEqualTo(ProgressTokenRegistry.MAX_ENTRIES);
    }

    @Test
    void forgetting_a_session_drops_only_its_tokens() {
        ProgressTokenRegistry tokens = new ProgressTokenRegistry();
        tokens.bind("ab12", "7", 41L);
        tokens.bind("cd34", "7", 42L);
        tokens.bind(null, "7", 43L);
        tokens.forget("ab12");
        assertThat(tokens.resolve("ab12", "7")).isNull();
        assertThat(tokens.resolve("cd34", "7")).isEqualTo(42L);
        assertThat(tokens.resolve(null, "7")).isEqualTo(43L);
    }
}
