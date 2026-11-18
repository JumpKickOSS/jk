// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What the caller's shell says of a variable: what the request carried, a name the client looked up
 * and did not send as unset, and the rest from the client when it answers — else unknown, never
 * unset.
 */
class CallerEnvTest {

    @Test
    void a_name_the_client_looked_up_and_did_not_send_is_unset_and_one_it_never_looked_up_is_unknown() {
        RequestShell shell = RequestShell.of(Map.of("A", "1"), Set.of("A", "B"), false, null);
        CallerEnv env = CallerEnv.of(shell, null);
        assertThat(env.get("A")).isEqualTo(CallerEnv.Value.of("1"));
        assertThat(env.get("B")).as("looked up, not sent").isEqualTo(CallerEnv.Value.UNSET);
        assertThat(env.get("C")).as("never looked up, no one to ask").isEqualTo(CallerEnv.Value.UNKNOWN);
        assertThat(env.attached()).isTrue();
    }

    @Test
    void the_rest_is_asked_of_the_client_in_one_question() {
        Asker asker = new Asker(Map.of("C", "3"));
        CallerEnv env = CallerEnv.of(RequestShell.of(Map.of("A", "1"), Set.of("A"), false, null), asker);
        Map<String, CallerEnv.Value> values = env.values(List.of("A", "C", "D"));
        assertThat(values)
                .containsEntry("A", CallerEnv.Value.of("1"))
                .containsEntry("C", CallerEnv.Value.of("3"))
                .containsEntry("D", CallerEnv.Value.UNSET);
        assertThat(asker.questions)
                .as("one question for the two the request lacked")
                .containsExactly(List.of("C", "D"));
    }

    @Test
    void a_client_that_cannot_answer_leaves_the_names_unknown() {
        Asker gone = new Asker(null);
        CallerEnv env = CallerEnv.of(null, gone);
        assertThat(env.get("X")).isEqualTo(CallerEnv.Value.UNKNOWN);
        assertThat(env.names()).isNull();
        assertThat(env.all()).isNull();
    }

    @Test
    void a_whole_shell_answers_everything_and_its_names() {
        CallerEnv env = CallerEnv.of(RequestShell.of(Map.of("A", "1"), Set.of(), true, null), null);
        assertThat(env.get("Z")).isEqualTo(CallerEnv.Value.UNSET);
        assertThat(env.names()).containsExactly("A");
        assertThat(env.all()).containsEntry("A", "1");
    }

    @Test
    void names_the_client_sent_answer_absence_without_a_value() {
        CallerEnv env = CallerEnv.of(RequestShell.of(Map.of(), Set.of(), false, List.of("A", "B")), null);
        assertThat(env.names()).containsExactlyInAnyOrder("A", "B");
        assertThat(env.get("Q")).as("not among the names the shell sets").isEqualTo(CallerEnv.Value.UNSET);
        assertThat(env.get("A")).as("set, but its value was not sent").isEqualTo(CallerEnv.Value.UNKNOWN);
    }

    @Test
    void no_request_is_no_caller() {
        CallerEnv env = CallerEnv.current();
        assertThat(env.attached()).isFalse();
        assertThat(env.get("PATH")).isEqualTo(CallerEnv.Value.UNKNOWN);
        assertThat(CallerEnv.Value.of("secret").toString()).doesNotContain("secret");
    }

    private static final class Asker implements CallerAsker {
        private final @Nullable Map<String, String> vars;
        final List<List<String>> questions = new ArrayList<>();

        Asker(@Nullable Map<String, String> vars) {
            this.vars = vars;
        }

        @Override
        public @Nullable Map<String, String> values(Collection<String> names) {
            questions.add(List.copyOf(names));
            if (vars == null) return null;
            Map<String, String> out = new LinkedHashMap<>();
            for (String n : names) {
                String v = vars.get(n);
                if (v != null) out.put(n, v);
            }
            return out;
        }

        @Override
        public @Nullable Set<String> names() {
            return vars == null ? null : vars.keySet();
        }

        @Override
        public @Nullable Map<String, String> all() {
            return vars;
        }

        @Override
        public boolean lost() {
            return vars == null;
        }
    }
}
