// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.task;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ChangedInputsTest {

    @Test
    void a_miss_names_the_tokens_that_moved_inside_a_packaging_steps_joined_inputs() {
        String a = "cp:store:repos/central/g/a/1/a-1.jar:" + "aa".repeat(32);
        String order = "cp-order:" + "bb".repeat(32);
        String was = String.join(";", a, "cp:app/target/classes:" + "cc".repeat(32), order, "shared:false");
        String now = String.join(";", a, "cp:app/target/classes:" + "dd".repeat(32), order, "shared:false");
        assertThat(ChangedInputs.of(
                        Map.of(ChangedInputs.JOINED_TOKENS, was, "jk", "1"),
                        Map.of(ChangedInputs.JOINED_TOKENS, now, "jk", "1")))
                .containsExactly("cp:app/target/classes");
        assertThat(ChangedInputs.of(Map.of("jk", "1"), Map.of("jk", "2"))).containsExactly("jk");
    }
}
