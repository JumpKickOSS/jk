// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.wire.protocol.EnvAskFrame;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * This shell answering a job's question: the variables asked for that it sets, its names alone, or
 * all of it — and a request saying which names it looked up, so the engine reads one it did not
 * send as unset.
 */
class EnvAnswersTest {

    private static final Map<String, String> SHELL = Map.of("A", "1", "B", "2");

    @AfterEach
    void clear() {
        System.clearProperty("jk.env.ANSWERS_PROBE_VAR");
        ProjectInfos.forget();
    }

    @Test
    void a_question_by_name_is_answered_with_what_the_shell_sets() {
        String line = EnvAnswers.answer(new EnvAskFrame(7, List.of("A", "C"), null).encode(), SHELL);
        assertThat(EnvAskFrame.answerId(line)).isEqualTo(7);
        assertThat(EnvAskFrame.answerVars(line)).isEqualTo(Map.of("A", "1"));
    }

    @Test
    void a_names_question_carries_no_value_and_an_all_question_carries_every_one() {
        String names = EnvAnswers.answer(new EnvAskFrame(1, List.of(), EnvAskFrame.NAMES).encode(), SHELL);
        assertThat(EnvAskFrame.answerNames(names)).containsExactly("A", "B");
        assertThat(names).doesNotContain("=1").doesNotContain("=2");
        String all = EnvAnswers.answer(new EnvAskFrame(2, List.of(), EnvAskFrame.ALL).encode(), SHELL);
        assertThat(EnvAskFrame.answerVars(all)).isEqualTo(SHELL);
    }

    @Test
    void a_request_says_it_answers_and_which_names_it_looked_up() {
        System.setProperty("jk.env.ANSWERS_PROBE_VAR", "x");
        String request = EngineWire.withShell("{\"type\":\"status\"}");
        ProtoSession.ShellFacts facts = ProtoSession.shellFactsOf(request);
        assertThat(facts.answers()).isTrue();
        assertThat(ProtoSession.shellOf(request).containsKey("ANSWERS_PROBE_VAR"))
                .as("an undeclared variable rides only in a shell sent whole, which says so")
                .isEqualTo(facts.whole());
    }
}
