// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The re-shelving pass runs only on the engine the home's pointer names: the install compares
 * the engine answering the endpoint with the pointer before starting the pass.
 */
class InstallCommandHandoffTest {

    private static final String POINTER = "ab12cd34ef56" + "0".repeat(52);

    @Test
    void the_pointed_engine_answering_is_no_mismatch() {
        assertThat(InstallCommand.handoffMismatch("ab12cd34ef56", Optional.of(POINTER)))
                .isNull();
        assertThat(InstallCommand.handoffMismatch("AB12CD34EF56", Optional.of(POINTER)))
                .isNull();
    }

    @Test
    void the_displaced_engine_answering_names_both_engines_and_the_remedy() {
        String why = InstallCommand.handoffMismatch("ffffffffffff", Optional.of(POINTER));
        assertThat(why)
                .contains("would run on engine ffffffffffff")
                .contains("home names engine ab12cd34ef56")
                .contains("`jk engine stop`")
                .contains("`jk install`");
    }

    @Test
    void a_side_without_an_identity_is_not_a_mismatch() {
        assertThat(InstallCommand.handoffMismatch("", Optional.of(POINTER))).isNull();
        assertThat(InstallCommand.handoffMismatch("ab12cd34ef56", Optional.empty()))
                .isNull();
    }
}
