// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/** Who a {@code jk} process says is running it: an agent, a terminal tab, a script. */
class CliSessionIdentityTest {

    private static final String LABEL_KEY = "[0-9a-f]{6}";

    private static CliSessionIdentity.Identity of(Map<String, String> env, @Nullable String stat) {
        return CliSessionIdentity.of(CliSessionIdentity.probe(env::get, stat));
    }

    /** {@code /proc/self/stat} of a process in session {@code sid} on tty {@code tty} (0 for none). */
    private static String stat(long sid, long tty) {
        return "4242 (jk run) S 4000 4242 " + sid + " " + tty + " 4242 4194560 1 0 0 0";
    }

    @Test
    void an_agents_shell_commands_are_one_session_per_agent_session() {
        long self = ProcessHandle.current().pid();
        var a = of(Map.of("CLAUDE_CODE_SESSION_ID", "81b7-aa", "CLAUDE_PID", String.valueOf(self)), stat(1, 34816));
        var again = of(Map.of("CLAUDE_CODE_SESSION_ID", "81b7-aa"), stat(2, 0));
        var other = of(Map.of("CLAUDE_CODE_SESSION_ID", "c0ff-ee"), stat(1, 34816));

        assertThat(a.label()).matches("claude-code \\(shell\\) " + LABEL_KEY);
        assertThat(again.label()).as("whatever shell the tool spawned").isEqualTo(a.label());
        assertThat(other.label()).isNotEqualTo(a.label());
        assertThat(a.leader()).as("led by the agent's process").startsWith(self + "@");
        assertThat(again.leader()).as("no CLAUDE_PID, no leader").isNull();

        var codex = of(Map.of("CODEX_THREAD_ID", "t-1"), stat(1, 0));
        assertThat(codex.label()).matches("codex \\(shell\\) " + LABEL_KEY);
        assertThat(codex.leader()).isNull();
    }

    @Test
    void on_linux_each_terminal_session_is_its_own_and_a_script_has_no_tty() {
        long leader = ProcessHandle.current().pid();
        var tab = of(Map.of(), stat(leader, 34816));
        var otherTab = of(Map.of(), stat(leader, 34817));
        var script = of(Map.of(), stat(leader, 0));

        assertThat(tab.label()).matches("terminal " + LABEL_KEY);
        assertThat(otherTab.label()).as("another tty is another tab").isNotEqualTo(tab.label());
        assertThat(script.label()).matches("script " + LABEL_KEY);
        assertThat(tab.leader()).startsWith(leader + "@");
        assertThat(tab.label())
                .as("no pid or tty reaches the label")
                .doesNotContain(String.valueOf(leader))
                .doesNotContain("34816");
        assertThat(of(Map.of(), stat(leader, 34816)))
                .as("stable across commands")
                .isEqualTo(tab);
    }

    @Test
    void a_process_name_with_spaces_and_parens_still_parses() {
        long leader = ProcessHandle.current().pid();
        String stat = "4242 (a) b (c) S 4000 4242 " + leader + " 34816 4242 0";
        assertThat(of(Map.of(), stat).label())
                .isEqualTo(of(Map.of(), stat(leader, 34816)).label());
    }

    @Test
    void off_linux_the_label_comes_from_the_process_tree() {
        var id = of(Map.of(), null);
        assertThat(id.label()).matches("(terminal|script) " + LABEL_KEY);
        assertThat(id.leader()).matches("\\d+@\\d+");
    }
}
