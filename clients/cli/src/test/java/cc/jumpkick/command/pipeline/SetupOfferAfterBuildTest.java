// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Guest;
import cc.jumpkick.winhost.WindowsFromGuest;
import cc.jumpkick.wire.protocol.DecisionsAck;
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

    @Test
    void aWslGuestWithInteropAsksForJumpKickForWindows() {
        assertThat(SetupOfferAfterBuild.wslGuest(new Guest(Guest.Kind.WSL, "Ubuntu-26.10", true, "i")))
                .isTrue();
        assertThat(SetupOfferAfterBuild.wslGuest(new Guest(Guest.Kind.WSL, "Ubuntu-26.10", false, "i")))
                .isFalse();
        assertThat(SetupOfferAfterBuild.wslGuest(Guest.NONE)).isFalse();
    }

    /** The guest's engine answers with the decision it raised, at most once; only then is the hint printed. */
    @Test
    void theHintIsPrintedOnlyWhenTheGuestsEngineRaisedItsOffer() {
        DecisionsAck raised = new DecisionsAck(null, "[]", "{\"id\":\"d1\",\"kind\":\"setup\"}");
        DecisionsAck nothing = new DecisionsAck(null, "[]", null);

        assertThat(SetupOfferAfterBuild.hint(false, raised)).isEqualTo(WindowsFromGuest.HINT);
        assertThat(SetupOfferAfterBuild.hint(false, nothing)).isNull();
        assertThat(SetupOfferAfterBuild.hint(true, raised))
                .as("Windows prints no hint")
                .isNull();
        assertThat(SetupOfferAfterBuild.hint(false, DecisionsAck.error("no engine")))
                .isNull();
        assertThat(WindowsFromGuest.HINT).contains("jk self setup-windows");
    }
}
