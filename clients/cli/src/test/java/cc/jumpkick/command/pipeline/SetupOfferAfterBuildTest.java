// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class SetupOfferAfterBuildTest {

    @Test
    void onlyAPersonAtATerminalIsOffered() {
        Map<String, String> none = Map.of();

        assertThat(SetupOfferAfterBuild.person(false, false, true, none::get)).isTrue();
        assertThat(SetupOfferAfterBuild.person(true, false, true, none::get)).isFalse();
        assertThat(SetupOfferAfterBuild.person(false, true, true, none::get)).isFalse();
        assertThat(SetupOfferAfterBuild.person(false, false, false, none::get)).isFalse();
    }

    @Test
    void anAgentWithATerminalIsNotOffered() {
        Map<String, String> agent = Map.of("CLAUDECODE", "1");

        assertThat(SetupOfferAfterBuild.person(false, false, true, agent::get)).isFalse();
    }
}
