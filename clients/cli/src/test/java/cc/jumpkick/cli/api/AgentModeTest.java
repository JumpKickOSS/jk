// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Agent output is explicit, or a non-terminal spawned by a coding-agent CLI. A pipe alone is not. */
class AgentModeTest {

    @Test
    void a_pipe_without_an_agent_variable_stays_human() {
        assertThat(AgentMode.requested(false, env(null, null), false, false)).isFalse();
    }

    @Test
    void a_terminal_with_a_spawn_variable_stays_human() {
        assertThat(AgentMode.requested(false, env("GROK_AGENT", "1"), true, false))
                .isFalse();
    }

    @Test
    void a_pipe_with_a_spawn_variable_is_agent_output() {
        assertThat(AgentMode.requested(false, env("CLAUDECODE", "1"), false, false))
                .isTrue();
        assertThat(AgentMode.requested(false, env("AI_AGENT", "1"), false, false))
                .isTrue();
        assertThat(AgentMode.requested(false, env("CURSOR_AGENT", "1"), false, false))
                .isTrue();
        assertThat(AgentMode.requested(false, env("CODEX_THREAD_ID", "abc"), false, false))
                .isTrue();
        assertThat(AgentMode.requested(false, env("GEMINI_CLI", "1"), false, false))
                .isTrue();
    }

    @Test
    void the_flag_and_JK_AGENT_force_it_and_zero_disables_auto() {
        assertThat(AgentMode.requested(true, env(null, null), true, false)).isTrue();
        assertThat(AgentMode.requested(false, env("JK_AGENT", "1"), true, false))
                .isTrue();
        Map<String, String> off = new HashMap<>();
        off.put("JK_AGENT", "0");
        off.put("GROK_AGENT", "1");
        assertThat(AgentMode.requested(false, key -> off.get(key), false, false))
                .isFalse();
    }

    @Test
    void json_stdout_is_not_auto_selected() {
        assertThat(AgentMode.requested(false, env("GROK_AGENT", "1"), false, true))
                .isFalse();
        assertThat(AgentMode.requested(true, env("GROK_AGENT", "1"), false, true))
                .isTrue();
    }

    private static Function<String, @Nullable String> env(@Nullable String name, @Nullable String value) {
        return key -> name != null && name.equals(key) ? value : null;
    }
}
