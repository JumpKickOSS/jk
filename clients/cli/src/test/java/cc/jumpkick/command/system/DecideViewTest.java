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
            [{"id":"3f9a0c1e","kind":"update","question":"Apply 1 move?","detail":["jackson 2.18.2 → 3.0.0"],
              "trust":true,"label":"claude-code 3f9a","project":"/ws/app","state":"open"},
             {"id":"3f9b7777","kind":"disk-clean","question":"Clean jk's cache?","detail":[],"trust":false,
              "state":"declined"}]
            """;

    @Test
    void the_table_lists_open_decisions_with_who_asked_and_whether_they_change_trust() {
        List<Map<?, ?>> all = DecideView.parse(LIST);
        assertThat(DecideView.table(DecideView.open(all)))
                .containsExactly(
                        "1 decision waits for you:",
                        "  3f9a0c1e  Apply 1 move?  (update, claude-code 3f9a, changes what the project trusts)");
        assertThat(DecideView.table(List.of())).containsExactly("No decision is waiting for you.");
    }

    @Test
    void an_id_matches_whole_or_by_a_unique_prefix() {
        List<Map<?, ?>> all = DecideView.parse(LIST);
        assertThat(DecideView.find(all, "3f9b7777")).isNotNull();
        assertThat(String.valueOf(requireNonNull(DecideView.find(all, "3f9a")).get("kind")))
                .isEqualTo("update");
        assertThat(DecideView.find(all, "3f9")).as("too short").isNull();
        assertThat(DecideView.find(all, "nope")).isNull();
    }

    @Test
    void the_card_names_the_question_the_asker_the_detail_and_the_trust() {
        assertThat(DecideView.card(DecideView.parse(LIST).getFirst()))
                .containsExactly(
                        "Apply 1 move?",
                        "  asked by claude-code 3f9a in /ws/app",
                        "  jackson 2.18.2 → 3.0.0",
                        "  This changes what the project trusts.");
    }
}
