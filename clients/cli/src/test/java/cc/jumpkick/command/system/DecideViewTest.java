// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** How {@code jk decide} reads the engine's list and prints a decision before asking it. */
class DecideViewTest {

    private static final String LIST = """
            [{"id":"3f9a0c1e","kind":"jdk-uninstall","question":"Uninstall temurin-21?","detail":["21.0.5"],
              "label":"claude-code 3f9a","project":"/ws/app","state":"open"},
             {"id":"3f9b7777","kind":"disk-clean","question":"Clean jk's cache?","detail":[],"state":"declined"}]
            """;

    @Test
    void the_table_lists_open_decisions_with_who_asked() {
        List<Map<?, ?>> all = DecideView.parse(LIST);
        assertThat(DecideView.table(DecideView.open(all)))
                .containsExactly(
                        "1 decision waits for you:",
                        "  3f9a0c1e  Uninstall temurin-21?  (jdk-uninstall, claude-code 3f9a)");
        assertThat(DecideView.table(List.of())).containsExactly("No decision is waiting for you.");
    }

    @Test
    void an_id_matches_whole_or_by_a_unique_prefix() {
        List<Map<?, ?>> all = DecideView.parse(LIST);
        assertThat(DecideView.find(all, "3f9b7777")).isNotNull();
        assertThat(String.valueOf(requireNonNull(DecideView.find(all, "3f9a")).get("kind")))
                .isEqualTo("jdk-uninstall");
        assertThat(DecideView.find(all, "3f9")).as("too short").isNull();
        assertThat(DecideView.find(all, "nope")).isNull();
    }

    @Test
    void the_card_names_the_question_the_asker_and_the_detail() {
        assertThat(DecideView.card(DecideView.parse(LIST).getFirst()))
                .containsExactly("Uninstall temurin-21?", "  asked by claude-code 3f9a in /ws/app", "  21.0.5");
    }

    @Test
    void without_a_terminal_y_accepts_and_d_declines_with_no_question_asked() {
        var accept = requireNonNull(DecideCommand.flagged("3f9a0c1e", false, true));
        assertThat(accept.action()).isEqualTo("accept");
        assertThat(accept.id()).isEqualTo("3f9a0c1e");
        assertThat(accept.answers()).isEqualTo("{\"answer\":\"yes\"}");
        assertThat(requireNonNull(DecideCommand.flagged("3f9a0c1e", true, true)).action())
                .as("-d wins: declining is the safe reading of both")
                .isEqualTo("decline");
        assertThat(DecideCommand.flagged("3f9a0c1e", false, false))
                .as("neither flag: the form is asked")
                .isNull();
    }
}
