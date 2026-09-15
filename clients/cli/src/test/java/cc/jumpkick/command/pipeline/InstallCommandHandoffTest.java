// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
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
    void a_client_of_another_version_than_the_engine_it_installed_hands_the_pass_to_the_tree_s_client() {
        String notice = InstallCommand.handoverNotice("0.13.5", Optional.of("0.13.6"), Path.of("/home/u/.jk/bin/jk"));
        assertThat(notice)
                .contains("jk 0.13.6")
                .contains("this one is jk 0.13.5")
                .contains("run `/home/u/.jk/bin/jk install` once more");
        assertThat(InstallCommand.handoverNotice("0.13.5", Optional.of("0.13.6"), null))
                .as("no PATH client installed: the pass still belongs to a client of the tree's version")
                .contains("run `jk install` as jk 0.13.6 once more");
    }

    @Test
    void the_tree_s_own_client_runs_the_re_shelving_pass_itself() {
        assertThat(InstallCommand.handoverNotice("0.13.6", Optional.of("0.13.6"), Path.of("/h/bin/jk")))
                .isNull();
        assertThat(InstallCommand.handoverNotice("0.13.6", Optional.empty(), null))
                .as("a home naming no engine leaves nothing to hand over")
                .isNull();
    }

    @Test
    void a_side_without_an_identity_is_not_a_mismatch() {
        assertThat(InstallCommand.handoffMismatch("", Optional.of(POINTER))).isNull();
        assertThat(InstallCommand.handoffMismatch("ab12cd34ef56", Optional.empty()))
                .isNull();
    }
}
