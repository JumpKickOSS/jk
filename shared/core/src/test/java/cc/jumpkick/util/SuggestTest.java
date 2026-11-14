// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class SuggestTest {

    @Test
    void a_swap_of_two_adjacent_letters_is_one_edit() {
        assertThat(Suggest.editDistance("stauts", "status")).isEqualTo(1);
        assertThat(Suggest.closest("stauts", List.of("start", "status", "stop")))
                .contains("status");
    }

    @Test
    void nothing_is_suggested_past_two_edits() {
        assertThat(Suggest.closest("nosuchverb", List.of("build", "test"))).isEmpty();
        assertThat(Suggest.closest("biuld", List.of("build", "test"))).contains("build");
    }
}
