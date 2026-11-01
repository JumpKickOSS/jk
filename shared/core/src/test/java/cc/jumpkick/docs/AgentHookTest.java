// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.docs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.MiniJson;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentHookTest {

    private static String bash(String command) {
        return MiniJson.write(Map.of("tool_name", "Bash", "tool_input", Map.of("command", command)));
    }

    @Test
    void maven_and_gradle_are_denied_in_any_position() {
        for (String c :
                new String[] {"mvn test", "./gradlew build", "cd x && mvn -q verify", "FOO=1 /usr/bin/gradle t"}) {
            assertThat(AgentHook.decide(bash(c))).as(c).contains("\"permissionDecision\":\"deny\"");
        }
    }

    @Test
    void jk_and_plain_commands_pass() {
        assertThat(AgentHook.decide(bash("jk -a test"))).isNull();
        assertThat(AgentHook.decide(bash("ls -la"))).isNull();
        assertThat(AgentHook.decide(bash("echo mvn"))).isNull();
    }

    @Test
    void a_grep_for_a_symbol_is_nudged_not_denied() {
        for (String c :
                new String[] {"grep -rn OrderService src", "rg \"\\bfooBar\\(\" .", "git grep -n Order.total"}) {
            String reply = AgentHook.decide(bash(c));
            assertThat(reply).as(c).contains("additionalContext").contains("jk graph callers");
            assertThat(reply).doesNotContain("deny");
        }
        assertThat(AgentHook.decide("{\"tool_name\":\"Grep\",\"tool_input\":{\"pattern\":\"OrderService.total\"}}"))
                .contains("jk graph callers OrderService.total");
    }

    @Test
    void a_grep_for_text_passes() {
        assertThat(AgentHook.decide(bash("grep -rn error src"))).isNull();
        assertThat(AgentHook.decide(bash("grep -rn 'TODO: fix' ."))).isNull();
        assertThat(AgentHook.decide("{\"tool_name\":\"Grep\",\"tool_input\":{\"pattern\":\"timeout\"}}"))
                .isNull();
    }

    @Test
    void input_that_is_not_a_tool_call_passes() {
        assertThat(AgentHook.decide("")).isNull();
        assertThat(AgentHook.decide("not json")).isNull();
        assertThat(AgentHook.decide("{\"tool_name\":\"Read\",\"tool_input\":{}}"))
                .isNull();
    }
}
